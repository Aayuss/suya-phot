package com.suyaphot.app.crypto

import com.suyaphot.app.core.crypto.KeyManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyManagerTest {

    private val keyManager = KeyManager(object : android.content.ContextWrapper(null) {})

    @Test
    fun testMasterKeyGeneration() {
        val key = keyManager.generateMasterKey()
        assertEquals(32, key.size)
    }

    @Test
    fun testRecoverySecretFormat() {
        val secret = keyManager.generateRecoverySecret()
        // e.g. "7K9P-4X2B-W8MN-3C5R"
        val parts = secret.split("-")
        assertEquals(4, parts.size)
        assertEquals(4, parts[0].length)
        assertEquals(4, parts[1].length)
        assertEquals(4, parts[2].length)
        assertEquals(4, parts[3].length)

        val normalized = keyManager.normalizeRecoverySecret(secret)
        assertEquals(16, normalized.length)
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

        val wrongUnwrapped = keyManager.unwrapRecoveryEnvelope(deserialized, "WRONG-CODE-1234")
        assertNull(wrongUnwrapped)
    }
}
