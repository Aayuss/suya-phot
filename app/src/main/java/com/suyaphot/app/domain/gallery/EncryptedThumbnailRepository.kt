package com.suyaphot.app.domain.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.media.ThumbnailGenerator
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Session-scoped, bounded decoded-thumbnail cache.
 *
 * Missing/corrupt encrypted derivatives are replaceable. The repository lazily rebuilds them
 * from a fully authenticated vault original, which is important after a restore where optional
 * derivatives may intentionally have been discarded.
 */
class EncryptedThumbnailRepository(
    private val sessionManager: SessionManager,
    private val fileStore: VaultFileStore,
    private val generator: ThumbnailGenerator,
    private val database: SuyaDatabase,
    private val vaultCrypto: VaultCrypto
) {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val repairMutex = Mutex()
    private var generation = 0L

    @Synchronized
    fun clear() {
        generation++
        cache.evictAll()
    }

    suspend fun load(vaultId: String, mediaId: String, revision: Long): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "$vaultId:$mediaId:$revision"
        val start = synchronized(this@EncryptedThumbnailRepository) {
            cache.get(cacheKey)?.let { return@withContext it }
            generation
        }

        val lease = sessionManager.acquireOperationKeyLease() ?: return@withContext null
        try {
            if (lease.vaultId != vaultId) return@withContext null

            val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
            val existing = generator.decryptThumbnail(thumbFile, lease.thumbSubkey, mediaId)
            val bitmap = existing ?: repairMutex.withLock {
                // Another tile/request may have repaired this derivative while we were waiting.
                generator.decryptThumbnail(thumbFile, lease.thumbSubkey, mediaId)
                    ?: regenerateThumbnail(vaultId, mediaId, thumbFile, lease.mediaSubkey, lease.metaSubkey, lease.thumbSubkey)
            } ?: return@withContext null

            synchronized(this@EncryptedThumbnailRepository) {
                if (generation != start || sessionManager.currentVaultId != vaultId) {
                    if (bitmap !== existing) bitmap.recycle()
                    return@withContext null
                }
                cache.put(cacheKey, bitmap)
            }
            bitmap
        } finally {
            lease.close()
        }
    }

    private suspend fun regenerateThumbnail(
        vaultId: String,
        mediaId: String,
        thumbFile: java.io.File,
        mediaSubkey: ByteArray,
        metaSubkey: ByteArray,
        thumbSubkey: ByteArray
    ): Bitmap? {
        val entity = database.mediaItemDao().getItemForVault(mediaId, vaultId) ?: return null
        val source = fileStore.getMediaFile(vaultId, mediaId)
        if (!source.exists()) return null

        val metadata = runCatching {
            val plain = Aead.decryptWithPrependedNonce(
                metaSubkey,
                entity.encryptedMetadata,
                mediaId.toByteArray(Charsets.UTF_8)
            )
            try {
                PrivateMediaMetadata.deserialize(plain)
            } finally {
                plain.fill(0)
            }
        }.getOrNull()

        val extension = metadata?.originalFileExtension
            ?: if (entity.mediaTypeCode == MediaType.VIDEO.code) "mp4" else "jpg"
        val temp = fileStore.createViewerTempFile(mediaId, extension)

        return try {
            val verified = vaultCrypto.decryptVerifiedToFile(
                encryptedFile = source,
                mediaSubkey = mediaSubkey,
                itemId = mediaId,
                outputFile = temp
            )
            val verifiedSha = verified.sha256.joinToString("") { "%02x".format(it) }
            check(verified.plaintextSize == entity.plaintextSize)
            check(verifiedSha.equals(entity.sha256Hex, ignoreCase = true))

            // A corrupt derivative is safe to discard because the authenticated original is intact.
            if (thumbFile.exists() && !thumbFile.delete()) {
                SafeLog.w("EncryptedThumbnailRepository", "Could not remove corrupt thumbnail derivative")
                return null
            }

            val generated = if (entity.mediaTypeCode == MediaType.IMAGE.code) {
                generator.generateAndEncryptImageThumbnail(
                    imageUri = Uri.fromFile(temp),
                    itemId = mediaId,
                    thumbSubkey = thumbSubkey,
                    outputThumbFile = thumbFile,
                    orientation = metadata?.orientation ?: 1
                )
            } else {
                generator.generateAndEncryptVideoThumbnail(
                    videoUri = Uri.fromFile(temp),
                    itemId = mediaId,
                    thumbSubkey = thumbSubkey,
                    outputThumbFile = thumbFile
                )
            }

            if (!generated) return null
            database.mediaItemDao().setThumbPathForVault(vaultId, mediaId, thumbFile.name)
            generator.decryptThumbnail(thumbFile, thumbSubkey, mediaId)
        } catch (e: Exception) {
            SafeLog.w("EncryptedThumbnailRepository", "Could not regenerate encrypted thumbnail")
            null
        } finally {
            temp.delete()
        }
    }
}
