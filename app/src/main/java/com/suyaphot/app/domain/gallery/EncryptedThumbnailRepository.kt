package com.suyaphot.app.domain.gallery

import android.graphics.Bitmap
import android.util.LruCache
import com.suyaphot.app.core.media.ThumbnailGenerator
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Session-scoped, bounded decoded-thumbnail cache with operation-owned key copies. */
class EncryptedThumbnailRepository(
    private val sessionManager: SessionManager,
    private val fileStore: VaultFileStore,
    private val generator: ThumbnailGenerator
) {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private var generation = 0L

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
            var bitmap = generator.decryptThumbnail(
                fileStore.getThumbFile(vaultId, mediaId),
                lease.thumbSubkey,
                mediaId
            )

            // Self-healing display fallback: older/import-interrupted image items may
            // have a valid encrypted preview even if the dedicated 360px thumbnail is
            // missing or corrupt. Use the authenticated preview and downscale it rather
            // than showing a permanent grey tile.
            if (bitmap == null) {
                val preview = generator.decryptImagePreview(
                    fileStore.getPreviewFile(vaultId, mediaId),
                    lease.thumbSubkey,
                    mediaId
                )
                if (preview != null) {
                    val maxSide = maxOf(preview.width, preview.height).coerceAtLeast(1)
                    val scale = (ThumbnailGenerator.TARGET_THUMB_SIZE.toFloat() / maxSide)
                        .coerceAtMost(1f)
                    val targetW = (preview.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (preview.height * scale).toInt().coerceAtLeast(1)
                    bitmap = if (targetW == preview.width && targetH == preview.height) {
                        preview
                    } else {
                        Bitmap.createScaledBitmap(preview, targetW, targetH, true).also {
                            preview.recycle()
                        }
                    }
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
