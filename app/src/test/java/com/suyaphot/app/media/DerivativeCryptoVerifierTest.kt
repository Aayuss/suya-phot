package com.suyaphot.app.media

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import com.suyaphot.app.core.media.DerivativeCryptoVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.UUID

class DerivativeCryptoVerifierTest {

    private val random = SecureRandom()

    private fun generateRandomBytes(size: Int): ByteArray {
        val b = ByteArray(size)
        random.nextBytes(b)
        return b
    }

    @Test
    fun testValidThumbnailCiphertextVerifies() {
        val thumbSubkey = generateRandomBytes(32)
        val itemId = "item_123"
        val plaintext = "test_thumbnail_bytes".toByteArray()

        val ciphertext = Aead.encryptWithPrependedNonce(
            keyBytes = thumbSubkey,
            plaintext = plaintext,
            aad = "suya-phot:thumbnail:v1:$itemId".toByteArray(Charsets.UTF_8)
        )

        val tempFile = File.createTempFile("thumb_test", ".sth")
        try {
            tempFile.writeBytes(ciphertext)
            val isValid = DerivativeCryptoVerifier.verifyThumbnailCiphertext(tempFile, thumbSubkey, itemId)
            assertTrue(isValid)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testCorruptThumbnailCiphertextFails() {
        val thumbSubkey = generateRandomBytes(32)
        val itemId = "item_123"
        val plaintext = "test_thumbnail_bytes".toByteArray()

        val ciphertext = Aead.encryptWithPrependedNonce(
            keyBytes = thumbSubkey,
            plaintext = plaintext,
            aad = "suya-phot:thumbnail:v1:$itemId".toByteArray(Charsets.UTF_8)
        )
        // Corrupt a byte
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1].toInt() xor 0xFF).toByte()

        val tempFile = File.createTempFile("thumb_test_corrupt", ".sth")
        try {
            tempFile.writeBytes(ciphertext)
            val isValid = DerivativeCryptoVerifier.verifyThumbnailCiphertext(tempFile, thumbSubkey, itemId)
            assertFalse(isValid)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testThumbnailWrongItemIdFails() {
        val thumbSubkey = generateRandomBytes(32)
        val itemId = "item_123"
        val plaintext = "test_thumbnail_bytes".toByteArray()

        val ciphertext = Aead.encryptWithPrependedNonce(
            keyBytes = thumbSubkey,
            plaintext = plaintext,
            aad = "suya-phot:thumbnail:v1:$itemId".toByteArray(Charsets.UTF_8)
        )

        val tempFile = File.createTempFile("thumb_test_wrong_id", ".sth")
        try {
            tempFile.writeBytes(ciphertext)
            val isValid = DerivativeCryptoVerifier.verifyThumbnailCiphertext(tempFile, thumbSubkey, "different_item_id")
            assertFalse(isValid)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testValidPreviewCiphertextVerifies() {
        val thumbSubkey = generateRandomBytes(32)
        val itemId = "item_preview_123"
        val plaintext = "test_preview_bytes".toByteArray()

        val previewKey = HkdfSha256.derive(
            ikm = thumbSubkey,
            salt = ByteArray(0),
            info = "suya-phot-preview-key-v1".toByteArray(Charsets.UTF_8),
            length = 32
        )

        val ciphertext = Aead.encryptWithPrependedNonce(
            keyBytes = previewKey,
            plaintext = plaintext,
            aad = "suya-phot:preview:v1:$itemId".toByteArray(Charsets.UTF_8)
        )

        val tempFile = File.createTempFile("preview_test", ".spr")
        try {
            tempFile.writeBytes(ciphertext)
            val isValid = DerivativeCryptoVerifier.verifyPreviewCiphertext(tempFile, thumbSubkey, itemId)
            assertTrue(isValid)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testCorruptPreviewCiphertextFails() {
        val thumbSubkey = generateRandomBytes(32)
        val itemId = "item_preview_123"
        val plaintext = "test_preview_bytes".toByteArray()

        val previewKey = HkdfSha256.derive(
            ikm = thumbSubkey,
            salt = ByteArray(0),
            info = "suya-phot-preview-key-v1".toByteArray(Charsets.UTF_8),
            length = 32
        )

        val ciphertext = Aead.encryptWithPrependedNonce(
            keyBytes = previewKey,
            plaintext = plaintext,
            aad = "suya-phot:preview:v1:$itemId".toByteArray(Charsets.UTF_8)
        )
        // Corrupt a byte
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1].toInt() xor 0xFF).toByte()

        val tempFile = File.createTempFile("preview_test_corrupt", ".spr")
        try {
            tempFile.writeBytes(ciphertext)
            val isValid = DerivativeCryptoVerifier.verifyPreviewCiphertext(tempFile, thumbSubkey, itemId)
            assertFalse(isValid)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testThumbnailExceedingMaxBoundRejected() {
        val thumbSubkey = generateRandomBytes(32)
        val itemId = "item_123"
        val tempFile = File.createTempFile("thumb_oversized", ".sth")
        try {
            // Write a dummy file that exceeds 8MB
            val dummyBytes = ByteArray(1024)
            tempFile.outputStream().use { os ->
                repeat(8 * 1024 + 1) {
                    os.write(dummyBytes)
                }
            }
            val isValid = DerivativeCryptoVerifier.verifyThumbnailCiphertext(tempFile, thumbSubkey, itemId)
            assertFalse(isValid)
        } finally {
            tempFile.delete()
        }
    }
}
