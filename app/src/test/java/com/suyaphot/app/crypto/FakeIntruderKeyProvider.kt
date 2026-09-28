package com.suyaphot.app.crypto

import com.suyaphot.app.core.crypto.IntruderKeyProvider
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class FakeIntruderKeyProvider(
    private val keyBytes: ByteArray = ByteArray(32) { (it + 42).toByte() }
) : IntruderKeyProvider {
    override fun getOrCreateKey(): SecretKey = SecretKeySpec(keyBytes, "AES")
}
