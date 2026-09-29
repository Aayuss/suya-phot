package com.suyaphot.app.backup

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.crypto.FakePepperProvider
import com.suyaphot.app.domain.backup.BackupError
import com.suyaphot.app.domain.backup.BackupException
import com.suyaphot.app.domain.backup.FolderLockCryptoFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class FolderLockPortabilityTest {

    private val random = SecureRandom()

    private fun generateRandomBytes(size: Int): ByteArray {
        val b = ByteArray(size)
        random.nextBytes(b)
        return b
    }

    @Test
    fun testRecoveryAadIsConsistent() {
        val lockId = "lock_abc123"
        val aad = FolderLockCryptoFormat.recoveryAad(lockId)
        val expected = "folder-recovery:lock_abc123:v1".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, aad)
    }

    @Test
    fun testValidRecoveryEnvelopeDecrypts32ByteToken() {
        val metaSubkey = generateRandomBytes(32)
        val folderToken = generateRandomBytes(32)
        val lockId = "lock_1"

        val envelope = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = folderToken,
            aad = FolderLockCryptoFormat.recoveryAad(lockId)
        )

        // Decrypt
        val decrypted = Aead.decryptWithPrependedNonce(
            keyBytes = metaSubkey,
            payload = envelope,
            aad = FolderLockCryptoFormat.recoveryAad(lockId)
        )

        assertEquals(32, decrypted.size)
        assertArrayEquals(folderToken, decrypted)
    }

    @Test
    fun testCorruptRecoveryEnvelopeFailsAead() {
        val metaSubkey = generateRandomBytes(32)
        val folderToken = generateRandomBytes(32)
        val lockId = "lock_1"

        val envelope = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = folderToken,
            aad = FolderLockCryptoFormat.recoveryAad(lockId)
        )

        // Flip a byte in ciphertext / tag
        envelope[envelope.size - 1] = (envelope[envelope.size - 1].toInt() xor 0xFF).toByte()

        try {
            Aead.decryptWithPrependedNonce(
                keyBytes = metaSubkey,
                payload = envelope,
                aad = FolderLockCryptoFormat.recoveryAad(lockId)
            )
            fail("Expected AEAD failure on corrupt envelope")
        } catch (e: Exception) {
            // Expected AEAD failure
        }
    }

    @Test
    fun testWrongLockIdAadFailsDecryption() {
        val metaSubkey = generateRandomBytes(32)
        val folderToken = generateRandomBytes(32)
        val lockId = "lock_1"

        val envelope = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = folderToken,
            aad = FolderLockCryptoFormat.recoveryAad(lockId)
        )

        try {
            Aead.decryptWithPrependedNonce(
                keyBytes = metaSubkey,
                payload = envelope,
                aad = FolderLockCryptoFormat.recoveryAad("different_lock_id")
            )
            fail("Expected AEAD failure on mismatched AAD")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun testCrossDevicePepperSimulation() {
        // Device A
        val pepperA = FakePepperProvider()
        val metaSubkeyA = generateRandomBytes(32)
        val folderToken = generateRandomBytes(32)
        val lockId = "lock_shared"

        // On Device A: create portable recovery envelope using metaSubkeyA
        val portableRecoveryEnvelope = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkeyA,
            plaintext = folderToken,
            aad = FolderLockCryptoFormat.recoveryAad(lockId)
        )

        // On Device A: local credential envelope peppered with Pepper A
        val pinChars = "654321".toCharArray()
        val pepperedKeyA = pepperA.hmacSha256(String(pinChars).toByteArray())
        val deviceACredentialEnvelope = Aead.encryptWithPrependedNonce(
            keyBytes = pepperedKeyA,
            plaintext = folderToken,
            aad = "folder-credential:$lockId:v1".toByteArray()
        )

        // Restore to Device B (new device with Pepper B and restored metaSubkey)
        val pepperB = object : com.suyaphot.app.core.crypto.PepperProvider {
            override fun hmacSha256(input: ByteArray): ByteArray {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                md.update("PEPPER_B_SALT".toByteArray())
                return md.digest(input)
            }
        }

        // 1. Attempting old PIN on Device B fails because Pepper A != Pepper B
        val pepperedKeyB_OldPin = pepperB.hmacSha256(String(pinChars).toByteArray())
        try {
            Aead.decryptWithPrependedNonce(
                keyBytes = pepperedKeyB_OldPin,
                payload = deviceACredentialEnvelope,
                aad = "folder-credential:$lockId:v1".toByteArray()
            )
            fail("Old credential envelope must fail under new device pepper")
        } catch (_: Exception) {
            // Expected!
        }

        // 2. Recovery reset unwraps folderToken using portable recovery envelope + metaSubkey
        val recoveredToken = Aead.decryptWithPrependedNonce(
            keyBytes = metaSubkeyA,
            payload = portableRecoveryEnvelope,
            aad = FolderLockCryptoFormat.recoveryAad(lockId)
        )
        assertArrayEquals(folderToken, recoveredToken)

        // 3. Re-encrypt with new PIN on Device B
        val newPinChars = "112233".toCharArray()
        val pepperedKeyB_NewPin = pepperB.hmacSha256(String(newPinChars).toByteArray())
        val newCredentialEnvelope = Aead.encryptWithPrependedNonce(
            keyBytes = pepperedKeyB_NewPin,
            plaintext = recoveredToken,
            aad = "folder-credential:$lockId:v1".toByteArray()
        )

        // 4. New PIN successfully decrypts the folder token on Device B
        val unlockedToken = Aead.decryptWithPrependedNonce(
            keyBytes = pepperedKeyB_NewPin,
            payload = newCredentialEnvelope,
            aad = "folder-credential:$lockId:v1".toByteArray()
        )
        assertArrayEquals(folderToken, unlockedToken)
    }
}
