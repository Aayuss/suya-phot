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

/**
 * Session-scoped, bounded decoded-thumbnail cache.
 *
 * Missing/corrupt derivatives are repaired lazily from the authenticated encrypted original,
 * so an old failed thumbnail generation never leaves a permanent grey tile.
 */
class EncryptedThumbnailRepository(
    private val sessionManager: SessionManager,
    private val fileStore: VaultFileStore,
    private val generator: ThumbnailGenerator,
    private val database: SuyaDatabase,
    private val vaultCrypto: VaultCrypto,
    private val accessManager: FolderAccessManager
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
            if (entity.concealed) {
                val folderId = entity.folderId ?: return@withContext null
                if (!accessManager.canOpen(vaultId, folderId)) return@withContext null
            }

            val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
            var bitmap = generator.decryptThumbnail(
                thumbFile,
                lease.thumbSubkey,
                mediaId
            )

            if (bitmap == null) {
                bitmap = repairThumbnail(
                    vaultId = vaultId,
                    mediaId = mediaId,
                    entity = entity,
                    mediaSubkey = lease.mediaSubkey,
                    metaSubkey = lease.metaSubkey,
                    thumbSubkey = lease.thumbSubkey
                )
            }

            bitmap ?: return@withContext null

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

    private suspend fun repairThumbnail(
        vaultId: String,
        mediaId: String,
        entity: com.suyaphot.app.core.database.entity.MediaItemEntity,
        mediaSubkey: ByteArray,
        metaSubkey: ByteArray,
        thumbSubkey: ByteArray
    ): Bitmap? {
        val metadata = runCatching {
            val plain = Aead.decryptWithPrependedNonce(
                keyBytes = metaSubkey,
                payload = entity.encryptedMetadata,
                aad = mediaId.toByteArray(Charsets.UTF_8)
            )
            try {
                PrivateMediaMetadata.deserialize(plain)
            } finally {
                plain.fill(0)
            }
        }.getOrNull()

        val extension = metadata?.originalFileExtension?.takeIf { it.isNotBlank() }
            ?: if (entity.mediaTypeCode == MediaType.VIDEO.code) "mp4" else "img"

        val temp = fileStore.createViewerTempFile(mediaId, extension)
        val thumbFile = fileStore.getThumbFile(vaultId, mediaId)

        return try {
            val verified = vaultCrypto.decryptVerifiedToFile(
                sourceEncryptedFile = fileStore.getMediaFile(vaultId, mediaId),
                mediaSubkey = mediaSubkey,
                itemId = mediaId,
                destinationTemp = temp
            )

            if (verified.plaintextSize != entity.plaintextSize) return null
            val verifiedSha = verified.sha256.joinToString("") { "%02x".format(it) }
            if (!verifiedSha.equals(entity.sha256Hex, ignoreCase = true)) return null

            // A corrupt derivative is replaceable. The authenticated original is not.
            if (thumbFile.exists()) {
                thumbFile.delete()
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

            database.mediaItemDao().setThumbPathForVault(
                vaultId = vaultId,
                id = mediaId,
                path = thumbFile.name
            )

            generator.decryptThumbnail(
                thumbFile,
                thumbSubkey,
                mediaId
            )
        } finally {
            temp.delete()
        }
    }
}
