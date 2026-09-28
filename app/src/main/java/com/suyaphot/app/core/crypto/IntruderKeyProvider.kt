package com.suyaphot.app.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

interface IntruderKeyProvider {
    fun getOrCreateKey(): SecretKey
}

class AndroidKeystoreIntruderKeyProvider : IntruderKeyProvider {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "suya_phot_intruder_log_aes_v1"
    }

    override fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )

        return generator.generateKey()
    }
}

class FakeIntruderKeyProvider(
    private val keyBytes: ByteArray = ByteArray(32) { (it + 42).toByte() }
) : IntruderKeyProvider {
    override fun getOrCreateKey(): SecretKey {
        return SecretKeySpec(keyBytes, "AES")
    }
}
