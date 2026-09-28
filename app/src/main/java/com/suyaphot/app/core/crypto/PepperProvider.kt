package com.suyaphot.app.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Interface providing hardware-backed HMAC-SHA256 device pepper operations.
 */
interface PepperProvider {
    fun hmacSha256(input: ByteArray): ByteArray
}

/**
 * Production implementation backed by Android Keystore.
 * Fails closed if hardware keystore is unavailable.
 */
class AndroidKeystorePepperProvider : PepperProvider {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "suya_phot_hmac_pepper_v1"
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN
            )
                .build()
        )

        return generator.generateKey()
    }

    override fun hmacSha256(input: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(input)
    }
}
