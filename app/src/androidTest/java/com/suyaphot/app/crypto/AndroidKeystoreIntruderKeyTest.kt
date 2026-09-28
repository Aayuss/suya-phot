package com.suyaphot.app.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.AndroidKeystoreIntruderKeyProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreIntruderKeyTest {
    @Test
    fun nonExportableKeystoreKeyEncryptDecryptRoundTrip() {
        val key = AndroidKeystoreIntruderKeyProvider().getOrCreateKey()
        assertNull(key.encoded)

        val aad = "intruder-test".toByteArray()
        val plain = "camera fixture".toByteArray()
        val encrypted = Aead.encryptWithPrependedNonce(key, plain, aad)

        assertArrayEquals(plain, Aead.decryptWithPrependedNonce(key, encrypted, aad))
    }
}
