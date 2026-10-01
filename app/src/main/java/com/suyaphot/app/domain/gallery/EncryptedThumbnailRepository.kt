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
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EncryptedThumbnailRepository(
    private val sessionManager: SessionManager,
    private val fileStore: VaultFileStore,
    private val generator: ThumbnailGenerator,
    private val database: SuyaDatabase,
    private val vaultCrypto: VaultCrypto
) {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private var generation = 0L

    @Synchronized
    fun clear() {
        generation++
        cache.evictAll()
    }

    suspend fun load(vaultId: String, mediaId: String, revision: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            val cacheKey = "$vaultId:$mediaId:$revision"
            val start = synchronized(this@EncryptedThumbnailRepository) {
                cache.get(cacheKey)?.let { return@withContext it }
                generation
            }

            val lease = sessionManager.acquireOperationKeyLease() ?: return@withContext null
            try {
                if (lease.vaultId != vaultId) return@withContext null
                val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
                var bitmap = generator.decryptThumbnail(thumbFile, lease.thumbSubkey, mediaId)

                if (bitmap == null) {
                    val entity = database.mediaItemDao().getItemForVault(mediaId, vaultId)
                        ?: return@withContext null
                    val mediaFile = fileStore.getMediaFile(vaultId, mediaId)
                    if (!mediaFile.exists()) return@withContext null

                    var metadata: PrivateMediaMetadata? = null
                    val metadataBytes = runCatching {
                        Aead.decryptWithPrependedNonce(
                            lease.metaSubkey,
                            entity.encryptedMetadata,
                            mediaId.toByteArray(Charsets.UTF_8)
                        )
                    }.getOrNull()
                    if (metadataBytes != null) {
                        try {
                            metadata = runCatching { PrivateMediaMetadata.deserialize(metadataBytes) }.getOrNull()
                        } finally {
                            metadataBytes.fill(0)
                        }
                    }

                    val temp = fileStore.createViewerTempFile(
                        mediaId,
                        metadata?.originalFileExtension
                            ?: if (entity.mediaTypeCode == MediaType.VIDEO.code) "mp4" else "img"
                    )
                    try {
                        val verified = vaultCrypto.decryptVerifiedToFile(mediaFile, lease.mediaSubkey, mediaId, temp)
                        val shaHex = verified.sha256.joinToString("") { "%02x".format(it) }
                        if (verified.plaintextSize != entity.plaintextSize ||
                            !shaHex.equals(entity.sha256Hex, ignoreCase = true)) return@withContext null

                        if (thumbFile.exists()) thumbFile.delete()
                        val generated = if (entity.mediaTypeCode == MediaType.VIDEO.code) {
                            generator.generateAndEncryptVideoThumbnail(Uri.fromFile(temp), mediaId, lease.thumbSubkey, thumbFile)
                        } else {
                            generator.generateAndEncryptImageThumbnail(
                                Uri.fromFile(temp), mediaId, lease.thumbSubkey, thumbFile,
                                metadata?.orientation ?: 1
                            )
                        }
                        if (generated) bitmap = generator.decryptThumbnail(thumbFile, lease.thumbSubkey, mediaId)
                    } finally {
                        temp.delete()
                    }
                }

                val finalBitmap = bitmap ?: return@withContext null
                synchronized(this@EncryptedThumbnailRepository) {
                    if (generation != start || sessionManager.currentVaultId != vaultId) {
                        finalBitmap.recycle()
                        return@withContext null
                    }
                    cache.put(cacheKey, finalBitmap)
                }
                finalBitmap
            } finally {
                lease.close()
            }
        }
}
