package com.suyaphot.app.domain.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.media.ThumbnailGenerator
import com.suyaphot.app.core.model.MediaType
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
 * If an older/imported row is missing its encrypted thumbnail, [load] repairs the derivative lazily
 * from the already-authenticated vault ciphertext. The repair never trusts/render plaintext before
 * full SUPH authentication and always deletes its private temporary file.
 */
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

    private val repairMutex = Mutex()
    private var generation = 0L

    @Synchronized
    fun clear() {
        generation++
        cache.evictAll()
    }

    suspend fun load(
        vaultId: String,
        mediaId: String,
        revision: Long
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "$vaultId:$mediaId:$revision"
        val start = synchronized(this@EncryptedThumbnailRepository) {
            cache.get(cacheKey)?.let { return@withContext it }
            generation
        }

        val lease = sessionManager.acquireOperationKeyLease()
            ?: return@withContext null

        try {
            if (lease.vaultId != vaultId) return@withContext null

            val thumbFile = thumbFile
            var bitmap = generator.decryptThumbnail(
                thumbFile,
                lease.thumbSubkey,
                mediaId
            )

            if (bitmap == null) {
                bitmap = repairMutex.withLock {
                    // Another tile/request may have repaired it while this request waited.
                    generator.decryptThumbnail(
                        thumbFile,
                        lease.thumbSubkey,
                        mediaId
                    ) ?: repairThumbnail(
                        vaultId = vaultId,
                        mediaId = mediaId,
                        mediaSubkey = lease.mediaSubkey,
                        thumbSubkey = lease.thumbSubkey
                    )
                }
            }

            bitmap ?: return@withContext null

            synchronized(this@EncryptedThumbnailRepository) {
                if (
                    generation != start ||
                    sessionManager.currentVaultId != vaultId
                ) {
                    bitmap.recycle()
                    return@withContext null
                }
                cache.put(cacheKey, bitmap)
            }

            bitmap
        } finally {
            lease.close()
        }
    }

    private suspend fun repairThumbnail(
        vaultId: String,
        mediaId: String,
        mediaSubkey: ByteArray,
        thumbSubkey: ByteArray
    ): Bitmap? {
        val entity = database.mediaItemDao().getItemForVault(mediaId, vaultId)
            ?: return null

        val encryptedMedia = fileStore.getMediaFile(vaultId, mediaId)
        if (!encryptedMedia.exists()) return null

        val extension = if (entity.mediaTypeCode == MediaType.VIDEO.code) "mp4" else "jpg"
        val temp = fileStore.createViewerTempFile(mediaId, extension)

        // If load() reached repair, an existing thumbnail failed authenticated decryption.
        // Thumbnails are replaceable derivatives, so remove only that bad derivative before
        // regenerating it from the verified original.
        val thumbFile = thumbFile
        if (thumbFile.exists()) {
            runCatching { thumbFile.delete() }
        }

        return try {
            val verified = vaultCrypto.decryptVerifiedToFile(
                sourceEncryptedFile = encryptedMedia,
                mediaSubkey = mediaSubkey,
                itemId = mediaId,
                destinationTemp = temp
            )
            val verifiedSha = verified.sha256.joinToString("") { "%02x".format(it) }
            if (
                verified.plaintextSize != entity.plaintextSize ||
                !verifiedSha.equals(entity.sha256Hex, ignoreCase = true)
            ) {
                SafeLog.w("EncryptedThumbnailRepository", "Thumbnail repair rejected media integrity mismatch")
                return null
            }

            val generated = if (entity.mediaTypeCode == MediaType.VIDEO.code) {
                generator.generateAndEncryptVideoThumbnail(
                    videoUri = Uri.fromFile(temp),
                    itemId = mediaId,
                    thumbSubkey = thumbSubkey,
                    outputThumbFile = thumbFile
                )
            } else {
                val orientation = runCatching {
                    ExifInterface(temp.absolutePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

                val thumbGenerated = generator.generateAndEncryptImageThumbnail(
                    imageUri = Uri.fromFile(temp),
                    itemId = mediaId,
                    thumbSubkey = thumbSubkey,
                    outputThumbFile = thumbFile,
                    orientation = orientation
                )

                // Also repair the encrypted viewer preview if it is absent.
                val previewFile = fileStore.getPreviewFile(vaultId, mediaId)
                if (!previewFile.exists()) {
                    generator.generateAndEncryptImagePreview(
                        imageUri = Uri.fromFile(temp),
                        itemId = mediaId,
                        thumbSubkey = thumbSubkey,
                        outputPreviewFile = previewFile,
                        orientation = orientation
                    )
                    if (previewFile.exists()) {
                        database.mediaItemDao().setPreviewPathForVault(
                            vaultId,
                            mediaId,
                            previewFile.name
                        )
                    }
                }

                thumbGenerated
            }

            if (!generated) return null

            val repairedThumb = thumbFile
            if (repairedThumb.exists()) {
                database.mediaItemDao().setThumbPathForVault(
                    vaultId,
                    mediaId,
                    repairedThumb.name
                )
            }

            generator.decryptThumbnail(
                repairedThumb,
                thumbSubkey,
                mediaId
            )
        } catch (_: Exception) {
            SafeLog.w("EncryptedThumbnailRepository", "Could not lazily repair encrypted thumbnail")
            null
        } finally {
            temp.delete()
        }
    }
}
