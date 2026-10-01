package com.suyaphot.app.domain.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.media.ThumbnailGenerator
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Session-scoped, bounded decoded-thumbnail cache with operation-owned key copies. */
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

            var bitmap = generator.decryptThumbnail(
                fileStore.getThumbFile(vaultId, mediaId),
                lease.thumbSubkey,
                mediaId
            )

            // A preview may exist even when a historical thumbnail generation failed.
            if (bitmap == null) {
                bitmap = generator.decryptImagePreview(
                    fileStore.getPreviewFile(vaultId, mediaId),
                    lease.thumbSubkey,
                    mediaId
                )
            }

            // Self-heal older/missing derivatives from the authenticated vault original.
            // The temporary plaintext is private app cache and is always removed here.
            if (bitmap == null) {
                val entity = database.mediaItemDao().getItemForVault(mediaId, vaultId)
                    ?: return@withContext null
                if (entity.deletedAt != null) return@withContext null

                val extension = if (entity.mediaTypeCode == MediaType.VIDEO.code) "mp4" else "img"
                val temp = fileStore.createViewerTempFile(mediaId, extension)
                try {
                    val verified = vaultCrypto.decryptVerifiedToFile(
                        fileStore.getMediaFile(vaultId, mediaId),
                        lease.mediaSubkey,
                        mediaId,
                        temp
                    )
                    if (verified.plaintextSize != entity.plaintextSize) return@withContext null
                    val verifiedHex = verified.sha256.joinToString("") { "%02x".format(it) }
                    if (!verifiedHex.equals(entity.sha256Hex, ignoreCase = true)) return@withContext null

                    val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
                    val generated = if (entity.mediaTypeCode == MediaType.VIDEO.code) {
                        generator.generateAndEncryptVideoThumbnail(
                            Uri.fromFile(temp),
                            mediaId,
                            lease.thumbSubkey,
                            thumbFile
                        )
                    } else {
                        val orientation = runCatching {
                            val raw = Aead.decryptWithPrependedNonce(
                                lease.metaSubkey,
                                entity.encryptedMetadata,
                                mediaId.toByteArray(Charsets.UTF_8)
                            )
                            try {
                                PrivateMediaMetadata.deserialize(raw).orientation ?: 1
                            } finally {
                                raw.fill(0)
                            }
                        }.getOrDefault(1)

                        generator.generateAndEncryptImageThumbnail(
                            Uri.fromFile(temp),
                            mediaId,
                            lease.thumbSubkey,
                            thumbFile,
                            orientation
                        )
                    }

                    if (generated) {
                        bitmap = generator.decryptThumbnail(
                            thumbFile,
                            lease.thumbSubkey,
                            mediaId
                        )
                    }
                } finally {
                    temp.delete()
                }
            }

            val result = bitmap ?: return@withContext null
            synchronized(this@EncryptedThumbnailRepository) {
                if (generation != start || sessionManager.currentVaultId != vaultId) {
                    result.recycle()
                    return@withContext null
                }
                cache.put(cacheKey, result)
            }
            result
        } finally {
            lease.close()
        }
    }
}
