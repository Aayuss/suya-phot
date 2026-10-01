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
            // Normal fast path: encrypted thumbnail derivative.
            var bitmap = generator.decryptThumbnail(
                fileStore.getThumbFile(vaultId, mediaId),
                lease.thumbSubkey,
                mediaId
            )

            // Self-heal the grid when a thumbnail derivative is missing/corrupt (for
            // example after a portable restore). The encrypted viewer preview is also
            // item-bound AEAD data and is safe to decode only after authentication.
            if (bitmap == null) {
                val preview = generator.decryptImagePreview(
                    fileStore.getPreviewFile(vaultId, mediaId),
                    lease.thumbSubkey,
                    mediaId
                )
                if (preview != null) {
                    val maxSide = maxOf(preview.width, preview.height).coerceAtLeast(1)
                    if (maxSide > ThumbnailGenerator.TARGET_THUMB_SIZE) {
                        val scale = ThumbnailGenerator.TARGET_THUMB_SIZE.toFloat() / maxSide.toFloat()
                        val targetWidth = (preview.width * scale).toInt().coerceAtLeast(1)
                        val targetHeight = (preview.height * scale).toInt().coerceAtLeast(1)
                        bitmap = Bitmap.createScaledBitmap(preview, targetWidth, targetHeight, true)
                        if (bitmap !== preview) preview.recycle()
                    } else {
                        bitmap = preview
                    }
                }
            }

            val loaded = bitmap ?: return@withContext null
            synchronized(this@EncryptedThumbnailRepository) {
                if (generation != start || sessionManager.currentVaultId != vaultId) {
                    loaded.recycle()
                    return@withContext null
                }
                cache.put(cacheKey, loaded)
            }
            loaded
        } finally {
            lease.close()
        }
    }
}
