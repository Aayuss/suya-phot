package com.suyaphot.app.crypto

import com.suyaphot.app.core.crypto.Aead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.spec.SecretKeySpec
import javax.crypto.AEADBadTagException

class AeadTest {

    @Test
    fun secretKeyRoundTripDoesNotRequireEncodedBytesAtCallSite() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val aad = "keystore-style-aad".toByteArray()
        val plaintext = "intruder fixture".toByteArray()

        val encrypted = Aead.encryptWithPrependedNonce(key, plaintext, aad)
        val decrypted = Aead.decryptWithPrependedNonce(key, encrypted, aad)

        assertArrayEquals(plaintext, decrypted)
    }

    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun testEncryptDecryptRoundTrip() {
        val plaintext = "Hello Suya Phot Private Vault!".toByteArray(Charsets.UTF_8)
        val aad = "header-aad".toByteArray(Charsets.UTF_8)

        val encrypted = Aead.encryptWithPrependedNonce(key, plaintext, aad)
        val decrypted = Aead.decryptWithPrependedNonce(key, encrypted, aad)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testTamperedCiphertextThrowsException() {
        val plaintext = "Secret Media Data".toByteArray(Charsets.UTF_8)
        val encrypted = Aead.encryptWithPrependedNonce(key, plaintext)

        // Tamper with a single byte in the ciphertext payload
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1].toInt() xor 0xFF).toByte()

        assertThrows(AEADBadTagException::class.java) {
            Aead.decryptWithPrependedNonce(key, encrypted)
        }
    }

    @Test
    fun testTamperedAadThrowsException() {
        val plaintext = "Secret Metadata".toByteArray(Charsets.UTF_8)
        val aad = "item-id-12345".toByteArray(Charsets.UTF_8)
        val tamperedAad = "item-id-99999".toByteArray(Charsets.UTF_8)

        val encrypted = Aead.encryptWithPrependedNonce(key, plaintext, aad)

        assertThrows(AEADBadTagException::class.java) {
            Aead.decryptWithPrependedNonce(key, encrypted, tamperedAad)
        }
    }
}
