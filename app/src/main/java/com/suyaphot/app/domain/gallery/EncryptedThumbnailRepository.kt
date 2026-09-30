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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Session-scoped, bounded decoded-thumbnail cache with safe on-demand derivative repair. */
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
    private val repairSemaphore = Semaphore(2)

    @Synchronized fun clear() {
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
            val entity = database.mediaItemDao().getItemForVault(mediaId, vaultId) ?: return@withContext null
            if (entity.deletedAt == null && entity.concealed &&
                (entity.folderId == null || !folderAccessManager.canOpen(vaultId, entity.folderId))) {
                return@withContext null
            }

            val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
            var bitmap = generator.decryptThumbnail(thumbFile, lease.thumbSubkey, mediaId)

            // Old/corrupt/missing encrypted derivatives should self-heal from the authenticated
            // original instead of leaving permanent blank grid tiles.
            if (bitmap == null) {
                repairSemaphore.withPermit {
                    // Another visible consumer may have repaired this derivative while we
                    // were waiting for the bounded repair slot.
                    bitmap = generator.decryptThumbnail(thumbFile, lease.thumbSubkey, mediaId)
                    if (bitmap == null) {
                        runCatching {
                            if (thumbFile.exists()) thumbFile.delete()

                            var orientation = 1
                            var extension = if (entity.mediaTypeCode == MediaType.VIDEO.code) "mp4" else "img"
                            val metadataBytes = Aead.decryptWithPrependedNonce(
                                lease.metaSubkey,
                                entity.encryptedMetadata,
                                mediaId.toByteArray(Charsets.UTF_8)
                            )
                            try {
                                val metadata = PrivateMediaMetadata.deserialize(metadataBytes)
                                orientation = metadata.orientation ?: 1
                                extension = metadata.originalFileExtension ?: extension
                            } finally {
                                metadataBytes.fill(0)
                            }

                            val temp = fileStore.createViewerTempFile(mediaId, extension)
                            try {
                                val verification = vaultCrypto.decryptVerifiedToFile(
                                    fileStore.getMediaFile(vaultId, mediaId),
                                    lease.mediaSubkey,
                                    mediaId,
                                    temp
                                )
                                check(verification.plaintextSize == entity.plaintextSize)
                                val shaHex = verification.sha256.joinToString("") { "%02x".format(it) }
                                check(shaHex.equals(entity.sha256Hex, ignoreCase = true))
                                check(sessionManager.currentVaultId == vaultId)

                                val generated = if (entity.mediaTypeCode == MediaType.IMAGE.code) {
                                    generator.generateAndEncryptImageThumbnail(
                                        Uri.fromFile(temp),
                                        mediaId,
                                        lease.thumbSubkey,
                                        thumbFile,
                                        orientation
                                    )
                                } else {
                                    generator.generateAndEncryptVideoThumbnail(
                                        Uri.fromFile(temp),
                                        mediaId,
                                        lease.thumbSubkey,
                                        thumbFile
                                    )
                                }
                                if (generated) {
                                    bitmap = generator.decryptThumbnail(thumbFile, lease.thumbSubkey, mediaId)
                                }
                            } finally {
                                temp.delete()
                            }
                        }
                    }
                }
            }

            val finalBitmap = bitmap ?: return@withContext null
            synchronized(this@EncryptedThumbnailRepository) {
                if (generation != start || sessionManager.currentVaultId != vaultId) return@withContext null
                cache.put(cacheKey, finalBitmap)
            }
            finalBitmap
        } finally {
            lease.close()
        }
    }
}
