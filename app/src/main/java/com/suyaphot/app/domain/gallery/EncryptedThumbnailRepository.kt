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
import com.suyaphot.app.domain.folders.FolderAccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Session-scoped, bounded decoded-thumbnail cache.
 *
 * Thumbnails/previews are replaceable derivatives. If an image derivative is absent
 * (for example after a legacy restore or a previous generation failure), this repository
 * can recreate it from the fully authenticated encrypted original without exposing
 * plaintext outside the app-private viewer cache.
 */
class EncryptedThumbnailRepository(
    private val sessionManager: SessionManager,
    private val fileStore: VaultFileStore,
    private val generator: ThumbnailGenerator,
    private val database: SuyaDatabase,
    private val vaultCrypto: VaultCrypto,
    private val folderAccessManager: FolderAccessManager
) {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private var generation = 0L
    // Regenerating a missing derivative verifies/decrypts the original to private cache.
    // Serialize repairs so a grid of legacy items cannot create many large plaintext temps at once.
    private val repairSemaphore = Semaphore(1)

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
        val startGeneration = synchronized(this@EncryptedThumbnailRepository) {
            cache.get(cacheKey)?.let { return@withContext it }
            generation
        }

        val lease = sessionManager.acquireOperationKeyLease()
            ?: return@withContext null

        try {
            if (lease.vaultId != vaultId) return@withContext null

            val entity = database.mediaItemDao().getItemForVault(mediaId, vaultId)
                ?: return@withContext null
            if (entity.deletedAt != null) return@withContext null
            if (
                entity.concealed &&
                (
                    entity.folderId == null ||
                        !folderAccessManager.canOpen(vaultId, entity.folderId)
                    )
            ) {
                return@withContext null
            }

            val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
            var bitmap = generator.decryptThumbnail(
                thumbFile,
                lease.thumbSubkey,
                mediaId
            )

            // A valid encrypted preview is already authenticated and is more useful
            // than a blank grid tile while the small derivative is being repaired.
            if (bitmap == null) {
                bitmap = generator.decryptImagePreview(
                    fileStore.getPreviewFile(vaultId, mediaId),
                    lease.thumbSubkey,
                    mediaId
                )
            }

            // Image thumbnails are cheap to repair from the encrypted original.
            // Avoid doing this automatically for videos because a missing video thumb
            // could require decrypting a multi-gigabyte file merely to draw a grid tile.
            if (bitmap == null && entity.mediaTypeCode == MediaType.IMAGE.code) {
                bitmap = repairSemaphore.withPermit {
                    // Another visible tile may have repaired it while this request waited.
                    generator.decryptThumbnail(
                        fileStore.getThumbFile(vaultId, mediaId),
                        lease.thumbSubkey,
                        mediaId
                    ) ?: regenerateImageThumbnail(
                        vaultId = vaultId,
                        mediaId = mediaId,
                        lease = lease,
                        encryptedMetadata = entity.encryptedMetadata,
                        expectedPlaintextSize = entity.plaintextSize,
                        expectedSha256Hex = entity.sha256Hex
                    )
                }
            }

            if (bitmap == null) return@withContext null

            synchronized(this@EncryptedThumbnailRepository) {
                if (
                    generation != startGeneration ||
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

    private suspend fun regenerateImageThumbnail(
        vaultId: String,
        mediaId: String,
        lease: com.suyaphot.app.domain.auth.OperationKeyLease,
        encryptedMetadata: ByteArray,
        expectedPlaintextSize: Long,
        expectedSha256Hex: String
    ): Bitmap? {
        val metadata = runCatching {
            val bytes = Aead.decryptWithPrependedNonce(
                lease.metaSubkey,
                encryptedMetadata,
                mediaId.toByteArray(Charsets.UTF_8)
            )
            try {
                PrivateMediaMetadata.deserialize(bytes)
            } finally {
                bytes.fill(0)
            }
        }.getOrNull()

        val temp = fileStore.createViewerTempFile(
            mediaId,
            metadata?.originalFileExtension ?: "jpg"
        )

        return try {
            val verification = vaultCrypto.decryptVerifiedToFile(
                sourceEncryptedFile = fileStore.getMediaFile(vaultId, mediaId),
                mediaSubkey = lease.mediaSubkey,
                itemId = mediaId,
                destinationTemp = temp
            )
            if (verification.plaintextSize != expectedPlaintextSize) return null
            val actualSha = verification.sha256.joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(expectedSha256Hex, ignoreCase = true)) return null

            if (sessionManager.currentVaultId != vaultId) return null

            val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
            // A corrupt replaceable derivative may be discarded before regeneration.
            if (thumbFile.exists()) thumbFile.delete()

            val generated = generator.generateAndEncryptImageThumbnail(
                imageUri = Uri.fromFile(temp),
                itemId = mediaId,
                thumbSubkey = lease.thumbSubkey,
                outputThumbFile = thumbFile,
                orientation = metadata?.orientation ?: 1
            )

            if (!generated) return null

            database.mediaItemDao().setThumbnailPathForVault(
                vaultId = vaultId,
                id = mediaId,
                path = thumbFile.name
            )

            generator.decryptThumbnail(
                thumbFile,
                lease.thumbSubkey,
                mediaId
            )
        } finally {
            temp.delete()
        }
    }
}
