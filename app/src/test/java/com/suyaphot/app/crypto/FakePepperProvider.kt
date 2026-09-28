package com.suyaphot.app.crypto

import com.suyaphot.app.core.crypto.PepperProvider
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic test implementation of PepperProvider for unit tests.
 */
class FakePepperProvider(
    private val key: ByteArray = ByteArray(32) { (it + 1).toByte() }
) : PepperProvider {

    override fun hmacSha256(input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }
}
