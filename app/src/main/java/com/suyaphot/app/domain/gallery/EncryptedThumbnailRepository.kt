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
 * Missing/corrupt derivatives are replaceable: the repository lazily regenerates them
 * from the authenticated encrypted original so restored/older items never remain as
 * permanent grey placeholders.
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

            val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
            var bitmap = generator.decryptThumbnail(thumbFile, lease.thumbSubkey, mediaId)

            if (bitmap == null) {
                val entity = database.mediaItemDao().getItemForVault(mediaId, vaultId)
                    ?: return@withContext null
                if (entity.deletedAt != null) return@withContext null
                if (entity.concealed) {
                    val folderId = entity.folderId ?: return@withContext null
                    if (!folderAccessManager.canOpen(vaultId, folderId)) return@withContext null
                }

                val metadata = runCatching {
                    val plain = Aead.decryptWithPrependedNonce(
                        lease.metaSubkey,
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
                try {
                    val verified = vaultCrypto.decryptVerifiedToFile(
                        fileStore.getMediaFile(vaultId, mediaId),
                        lease.mediaSubkey,
                        mediaId,
                        temp
                    )
                    val verifiedHash = verified.sha256.joinToString("") { "%02x".format(it) }
                    if (verified.plaintextSize != entity.plaintextSize ||
                        !verifiedHash.equals(entity.sha256Hex, ignoreCase = true)) {
                        return@withContext null
                    }
                    if (sessionManager.currentVaultId != vaultId) return@withContext null
                    if (entity.concealed) {
                        val folderId = entity.folderId ?: return@withContext null
                        if (!folderAccessManager.canOpen(vaultId, folderId)) return@withContext null
                    }

                    // Replace only the derivative. The authenticated original is never modified.
                    if (thumbFile.exists()) thumbFile.delete()
                    val generated = if (entity.mediaTypeCode == MediaType.IMAGE.code) {
                        generator.generateAndEncryptImageThumbnail(
                            imageUri = Uri.fromFile(temp),
                            itemId = mediaId,
                            thumbSubkey = lease.thumbSubkey,
                            outputThumbFile = thumbFile,
                            orientation = metadata?.orientation ?: 1
                        )
                    } else {
                        generator.generateAndEncryptVideoThumbnail(
                            videoUri = Uri.fromFile(temp),
                            itemId = mediaId,
                            thumbSubkey = lease.thumbSubkey,
                            outputThumbFile = thumbFile
                        )
                    }

                    if (generated) {
                        database.mediaItemDao().setThumbPathForVault(vaultId, mediaId, thumbFile.name)
                        bitmap = generator.decryptThumbnail(thumbFile, lease.thumbSubkey, mediaId)
                    }
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
