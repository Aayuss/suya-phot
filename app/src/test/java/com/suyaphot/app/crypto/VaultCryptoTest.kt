package com.suyaphot.app.crypto

import com.suyaphot.app.core.crypto.VaultCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class VaultCryptoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val vaultCrypto = VaultCrypto()
    private val masterKey = ByteArray(32) { (it * 7).toByte() }

    @Test
    fun testStreamEncryptionAndVerification() {
        val mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
        val itemId = "test-item-uuid-1234"
        val originalText = "This is a test photo or 4K video stream containing sensitive pixels."
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)

        val encryptedFile = File(tempFolder.root, "encrypted.sph")

        val expectedSha = MessageDigest.getInstance("SHA-256").digest(originalBytes)

        val encryptResult = vaultCrypto.encryptStream(
            input = ByteArrayInputStream(originalBytes),
            outputFile = encryptedFile,
            mediaSubkey = mediaSubkey,
            itemId = itemId,
            isVideo = false,
            plaintextSize = originalBytes.size.toLong()
        )

        assertEquals(originalBytes.size.toLong(), encryptResult.plaintextSize)
        assertArrayEquals(expectedSha, encryptResult.sha256)
        assertTrue(encryptedFile.exists())
        assertTrue(encryptedFile.length() > originalBytes.size)

        // Verify and hash directly from encrypted file
        val verifyResult = vaultCrypto.verifyAndHash(encryptedFile, mediaSubkey, itemId)
        assertEquals(originalBytes.size.toLong(), verifyResult.plaintextSize)
        assertArrayEquals(expectedSha, verifyResult.sha256)

        // Decrypt to output stream
        val baos = ByteArrayOutputStream()
        val decryptResult = vaultCrypto.decryptTo(encryptedFile, mediaSubkey, itemId, baos)
        assertEquals(originalBytes.size.toLong(), decryptResult.plaintextSize)
        assertArrayEquals(expectedSha, decryptResult.sha256)
        assertArrayEquals(originalBytes, baos.toByteArray())
    }

    @Test
    fun testTamperedEncryptedMediaFailsVerification() {
        val mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
        val itemId = "test-item-tamper"
        val originalBytes = "Data".toByteArray()
        val encryptedFile = File(tempFolder.root, "tampered.sph")

        vaultCrypto.encryptStream(
            input = ByteArrayInputStream(originalBytes),
            outputFile = encryptedFile,
            mediaSubkey = mediaSubkey,
            itemId = itemId,
            isVideo = false,
            plaintextSize = originalBytes.size.toLong()
        )

        // Tamper with ciphertext byte
        val bytes = encryptedFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x55).toByte()
        encryptedFile.writeBytes(bytes)

        var failed = false
        try {
            vaultCrypto.verifyAndHash(encryptedFile, mediaSubkey, itemId)
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("Tampered media must fail verification", failed)
    }
}
