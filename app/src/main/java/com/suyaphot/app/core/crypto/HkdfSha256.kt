package com.suyaphot.app.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 compliant HKDF-SHA256 implementation.
 */
object HkdfSha256 {
    private const val HASH_LEN = 32

    fun derive(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        require(length in 1..(255 * HASH_LEN)) { "Derived key length must be between 1 and ${255 * HASH_LEN} bytes" }

        val mac = Mac.getInstance("HmacSHA256")
        val effectiveSalt = if (salt.isNotEmpty()) salt else ByteArray(HASH_LEN)
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val okm = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1

        try {
            while (written < length) {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                val block = mac.doFinal()

                val copy = minOf(block.size, length - written)
                System.arraycopy(block, 0, okm, written, copy)
                written += copy

                previous.fill(0)
                previous = block
                counter++
            }
            return okm
        } finally {
            prk.fill(0)
            previous.fill(0)
        }
    }
}
