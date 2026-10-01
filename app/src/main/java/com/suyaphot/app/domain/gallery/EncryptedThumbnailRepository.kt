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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    private val regenerationGate = Semaphore(1)

    @Synchronized fun clear() {
        generation++
        cache.evictAll()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun scalePreview(preview: Bitmap): Bitmap {
        val maxSide = maxOf(preview.width, preview.height).coerceAtLeast(1)
        if (maxSide <= ThumbnailGenerator.TARGET_THUMB_SIZE) return preview
        val ratio = ThumbnailGenerator.TARGET_THUMB_SIZE.toFloat() / maxSide.toFloat()
        val result = Bitmap.createScaledBitmap(
            preview,
            (preview.width * ratio).toInt().coerceAtLeast(1),
            (preview.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (result !== preview) preview.recycle()
        return result
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

            // Fast path: normal encrypted thumbnail derivative.
            var bitmap = generator.decryptThumbnail(
                fileStore.getThumbFile(vaultId, mediaId),
                lease.thumbSubkey,
                mediaId
            )

            // Portable restore may intentionally omit a damaged thumbnail. A valid encrypted
            // preview is a safe authenticated fallback and avoids a blank gallery tile.
            if (bitmap == null) {
                generator.decryptImagePreview(
                    fileStore.getPreviewFile(vaultId, mediaId),
                    lease.thumbSubkey,
                    mediaId
                )?.let { bitmap = scalePreview(it) }
            }

            // Last-resort self-heal: if both derivatives are absent, authenticate the entire
            // original into a private temp file first, regenerate only the encrypted thumbnail,
            // then immediately delete the plaintext temp. No unauthenticated plaintext is rendered.
            if (bitmap == null) {
                val entity = database.mediaItemDao().getItemForVault(mediaId, vaultId)
                    ?: return@withContext null

                // Never create a multi-GB plaintext video temp merely to repair a replaceable
                // thumbnail. Normal imports generate video thumbs eagerly; image derivatives can
                // be rebuilt cheaply and serially when missing after an old/test restore.
                if (entity.mediaTypeCode != MediaType.IMAGE.code ||
                    entity.plaintextSize > 256L * 1024L * 1024L
                ) {
                    return@withContext null
                }

                bitmap = regenerationGate.withPermit {
                    // Another tile may have repaired this while we waited.
                    generator.decryptThumbnail(
                        fileStore.getThumbFile(vaultId, mediaId),
                        lease.thumbSubkey,
                        mediaId
                    )?.let { return@withPermit it }

                    val source = fileStore.getMediaFile(vaultId, mediaId)
                    if (!source.exists()) return@withPermit null

                    var metadata: PrivateMediaMetadata? = null
                    val metadataPlain = runCatching {
                        Aead.decryptWithPrependedNonce(
                            lease.metaSubkey,
                            entity.encryptedMetadata,
                            mediaId.toByteArray(Charsets.UTF_8)
                        )
                    }.getOrNull()
                    if (metadataPlain != null) {
                        try {
                            metadata = runCatching {
                                PrivateMediaMetadata.deserialize(metadataPlain)
                            }.getOrNull()
                        } finally {
                            metadataPlain.fill(0)
                        }
                    }

                    val extension = metadata?.originalFileExtension?.let { ".$it" } ?: ".jpg"
                    val temp = fileStore.createViewerTempFile(mediaId, extension)
                    try {
                        val verified = vaultCrypto.decryptVerifiedToFile(
                            source,
                            lease.mediaSubkey,
                            mediaId,
                            temp
                        )
                        val digestMatches = sha256Hex(verified.sha256)
                            .equals(entity.sha256Hex, ignoreCase = true)
                        verified.sha256.fill(0)
                        if (verified.plaintextSize != entity.plaintextSize || !digestMatches) {
                            return@withPermit null
                        }

                        val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
                        // A failed decrypt means this replaceable derivative is corrupt/stale.
                        runCatching { thumbFile.delete() }
                        val generated = generator.generateAndEncryptImageThumbnail(
                            Uri.fromFile(temp),
                            mediaId,
                            lease.thumbSubkey,
                            thumbFile,
                            metadata?.orientation ?: 0
                        )
                        if (!generated) return@withPermit null

                        generator.decryptThumbnail(
                            thumbFile,
                            lease.thumbSubkey,
                            mediaId
                        )
                    } finally {
                        runCatching { temp.delete() }
                    }
                }}
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
