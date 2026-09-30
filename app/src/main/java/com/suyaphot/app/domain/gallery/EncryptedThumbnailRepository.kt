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
            val bitmap = generator.decryptThumbnail(
                fileStore.getThumbFile(vaultId, mediaId),
                lease.thumbSubkey,
                mediaId
            ) ?: generator.decryptImagePreview(
                fileStore.getPreviewFile(vaultId, mediaId),
                lease.thumbSubkey,
                mediaId
            )?.let { preview ->
                val maxSide = 512
                val largest = maxOf(preview.width, preview.height)
                if (largest <= maxSide) {
                    preview
                } else {
                    val ratio = maxSide.toFloat() / largest.toFloat()
                    val scaled = Bitmap.createScaledBitmap(
                        preview,
                        (preview.width * ratio).toInt().coerceAtLeast(1),
                        (preview.height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                    if (scaled !== preview) preview.recycle()
                    scaled
                }
            } ?: return@withContext null
            synchronized(this@EncryptedThumbnailRepository) {
                if (generation != start || sessionManager.currentVaultId != vaultId) return@withContext null
                cache.put(cacheKey, bitmap)
            }
            bitmap
        } finally {
            lease.close()
        }
    }
}
