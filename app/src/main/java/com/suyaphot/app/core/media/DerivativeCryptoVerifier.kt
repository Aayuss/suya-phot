package com.suyaphot.app.core.media

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import java.io.File

/**
 * Pure cryptographic authentication for cached/optional thumbnails and previews.
 * Verifies AEAD authenticity without decoding Bitmaps or causing large heap allocations.
 */
object DerivativeCryptoVerifier {
    private const val MAX_THUMB_SIZE = 5L * 1024 * 1024 // 5 MB conservative bound
    private const val MAX_PREVIEW_SIZE = 32L * 1024 * 1024 // 32 MB conservative bound

    fun verifyThumbnailCiphertext(
        file: File,
        thumbSubkey: ByteArray,
        itemId: String
    ): Boolean {
        if (!file.exists() || !file.isFile) return false
        val len = file.length()
        // 12 bytes nonce + 16 bytes tag minimum
        if (len <= 28 || len > MAX_THUMB_SIZE) return false
        return try {
            val encrypted = file.readBytes()
            val plain = try {
                Aead.decryptWithPrependedNonce(
                    keyBytes = thumbSubkey,
                    payload = encrypted,
                    aad = "suya-phot:thumbnail:v1:$itemId".toByteArray(Charsets.UTF_8)
                )
            } finally {
                encrypted.fill(0)
            }
            try {
                plain.isNotEmpty()
            } finally {
                plain.fill(0)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun verifyPreviewCiphertext(
        file: File,
        thumbSubkey: ByteArray,
        itemId: String
    ): Boolean {
        if (!file.exists() || !file.isFile) return false
        val len = file.length()
        if (len <= 28 || len > MAX_PREVIEW_SIZE) return false
        val key = HkdfSha256.derive(
            ikm = thumbSubkey,
            salt = ByteArray(0),
            info = "suya-phot-preview-key-v1".toByteArray(Charsets.UTF_8),
            length = 32
        )
        return try {
            val encrypted = file.readBytes()
            val plain = try {
                Aead.decryptWithPrependedNonce(
                    keyBytes = key,
                    payload = encrypted,
                    aad = "suya-phot:preview:v1:$itemId".toByteArray(Charsets.UTF_8)
                )
            } finally {
                encrypted.fill(0)
            }
            try {
                plain.isNotEmpty()
            } finally {
                plain.fill(0)
            }
        } catch (_: Exception) {
            false
        } finally {
            key.fill(0)
        }
    }
}
