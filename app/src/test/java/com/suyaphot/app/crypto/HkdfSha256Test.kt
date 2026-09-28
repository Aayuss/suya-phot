package com.suyaphot.app.crypto

import com.suyaphot.app.core.crypto.HkdfSha256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HkdfSha256Test {

    @Test
    fun testHkdfDerivationConsistency() {
        val ikm = "test-input-key-material-256".toByteArray(Charsets.UTF_8)
        val salt = "test-salt-123".toByteArray(Charsets.UTF_8)
        val info = "suya-phot-test-info".toByteArray(Charsets.UTF_8)

        val key1 = HkdfSha256.derive(ikm, salt, info, 32)
        val key2 = HkdfSha256.derive(ikm, salt, info, 32)

        assertEquals(32, key1.size)
        assertArrayEquals(key1, key2)
    }

    @Test
    fun testHkdfDomainSeparation() {
        val ikm = "shared-master-key".toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16) { it.toByte() }

        val mediaKey = HkdfSha256.derive(ikm, salt, "suya-phot-media-key".toByteArray(), 32)
        val metaKey = HkdfSha256.derive(ikm, salt, "suya-phot-meta-key".toByteArray(), 32)
        val thumbKey = HkdfSha256.derive(ikm, salt, "suya-phot-thumb-key".toByteArray(), 32)

        assertFalse(mediaKey.contentEquals(metaKey))
        assertFalse(mediaKey.contentEquals(thumbKey))
        assertFalse(metaKey.contentEquals(thumbKey))
    }
}
