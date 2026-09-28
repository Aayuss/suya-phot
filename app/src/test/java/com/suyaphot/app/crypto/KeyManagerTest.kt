package com.suyaphot.app.crypto

import com.suyaphot.app.core.crypto.KeyManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.suyaphot.app.core.crypto.VaultCrypto
import java.io.ByteArrayInputStream
import java.io.File

class KeyManagerTest {

    private val fakePepperProvider = FakePepperProvider()
    private val keyManager = KeyManager(
        context = object : android.content.ContextWrapper(null) {},
        pepperProvider = fakePepperProvider
    )

    @Test
    fun testMasterKeyGeneration() {
        val key = keyManager.generateMasterKey()
        assertEquals(32, key.size)
    }

    @Test
    fun testRecoverySecretFormat() {
        val secret = keyManager.generateRecoverySecret()
        // 26 Base32 chars formatted as XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XX
        val parts = secret.split("-")
        assertEquals(7, parts.size)
        for (i in 0 until 6) {
            assertEquals(4, parts[i].length)
        }
        assertEquals(2, parts[6].length)

        val normalized = keyManager.normalizeRecoverySecret(secret)
        assertEquals(26, normalized.length)
    }

    @Test
    fun testRecoveryEnvelopeRoundTrip() {
        val masterKey = keyManager.generateMasterKey()
        val secret = keyManager.generateRecoverySecret()
        val normalized = keyManager.normalizeRecoverySecret(secret)

        val envelope = keyManager.createRecoveryEnvelope(masterKey, normalized)
        val serialized = envelope.serialize()
        val deserialized = KeyManager.RecoveryEnvelope.deserialize(serialized)

        val unwrapped = keyManager.unwrapRecoveryEnvelope(deserialized, secret)
        assertNotNull(unwrapped)
        assertArrayEquals(masterKey, unwrapped)

        val wrongUnwrapped = keyManager.unwrapRecoveryEnvelope(deserialized, "XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XX")
        assertNull(wrongUnwrapped)
    }

    @Test
    fun testPinEnvelopeRoundTripAndPepperProtection() {
        val masterKey = keyManager.generateMasterKey()
        val pin = "123456".toCharArray()

        val envelope = keyManager.createPinEnvelope(masterKey, pin, iterations = 100_000)
        val serialized = envelope.serialize()
        val deserialized = KeyManager.PinEnvelope.deserialize(serialized)

        // Correct PIN and pepper unwraps successfully
        val unwrapped = keyManager.unwrapPinEnvelope(deserialized, "123456".toCharArray())
        assertNotNull(unwrapped)
        assertArrayEquals(masterKey, unwrapped)

        // Wrong PIN fails
        val wrongPinUnwrapped = keyManager.unwrapPinEnvelope(deserialized, "654321".toCharArray())
        assertNull(wrongPinUnwrapped)

        // Different pepper provider fails to unwrap even with correct PIN
        val differentPepperProvider = FakePepperProvider(ByteArray(32) { 0x99.toByte() })
        val otherKeyManager = KeyManager(
            context = object : android.content.ContextWrapper(null) {},
            pepperProvider = differentPepperProvider
        )
        val differentPepperUnwrapped = otherKeyManager.unwrapPinEnvelope(deserialized, "123456".toCharArray())
        assertNull(differentPepperUnwrapped)
    }

    @Test
    fun secondaryPinRotationRewrapsSameMasterKeyAndPreservesMedia() {
        val vaultCrypto = VaultCrypto()
        val masterKey = keyManager.generateMasterKey()
        val originalPin = "123456".toCharArray()
        val newPin = "654321".toCharArray()
        val originalEnvelope = keyManager.createPinEnvelope(masterKey, originalPin, iterations = 100_000)
        val mediaKey = vaultCrypto.deriveMediaSubkey(masterKey)
        val fixture = ByteArray(4096) { (it % 251).toByte() }
        val encrypted = File.createTempFile("secondary-pin-", ".sph")
        try {
            val before = vaultCrypto.encryptStream(
                ByteArrayInputStream(fixture), encrypted, mediaKey, "item-1", false, fixture.size.toLong()
            )
            val unwrappedExisting = keyManager.unwrapPinEnvelope(originalEnvelope, originalPin)!!
            val replacement = keyManager.createPinEnvelope(unwrappedExisting, newPin, iterations = 100_000)
            unwrappedExisting.fill(0)

            assertNull(keyManager.unwrapPinEnvelope(replacement, originalPin))
            val afterMaster = keyManager.unwrapPinEnvelope(replacement, newPin)!!
            assertArrayEquals(masterKey, afterMaster)
            val afterMediaKey = vaultCrypto.deriveMediaSubkey(afterMaster)
            val after = vaultCrypto.verifyAndHash(encrypted, afterMediaKey, "item-1")
            assertArrayEquals(before.sha256, after.sha256)
            assertEquals(before.plaintextSize, after.plaintextSize)
            afterMaster.fill(0)
            afterMediaKey.fill(0)
        } finally {
            mediaKey.fill(0)
            masterKey.fill(0)
            originalPin.fill('\u0000')
            newPin.fill('\u0000')
            encrypted.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testPinEnvelopeRejectsTrailingBytes() {
        val masterKey = keyManager.generateMasterKey()
        val envelope = keyManager.createPinEnvelope(masterKey, "123456".toCharArray(), iterations = 100_000)
        val bytes = envelope.serialize()
        val withTrailing = bytes + byteArrayOf(0x01, 0x02)
        KeyManager.PinEnvelope.deserialize(withTrailing)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRecoveryEnvelopeRejectsTrailingBytes() {
        val masterKey = keyManager.generateMasterKey()
        val secret = keyManager.generateRecoverySecret()
        val envelope = keyManager.createRecoveryEnvelope(masterKey, secret)
        val bytes = envelope.serialize()
        val withTrailing = bytes + byteArrayOf(0x01)
        KeyManager.RecoveryEnvelope.deserialize(withTrailing)
    }
}
