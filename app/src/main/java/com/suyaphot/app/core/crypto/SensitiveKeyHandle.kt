package com.suyaphot.app.core.crypto

import java.io.Closeable
import java.util.Arrays

/**
 * Container for sensitive in-memory key bytes.
 * Safely clears byte array when closed or discarded.
 */
class SensitiveKeyHandle(
    private var keyBytes: ByteArray?
) : Closeable {

    init {
        require(keyBytes != null && keyBytes!!.isNotEmpty()) { "Key bytes cannot be null or empty" }
    }

    val isClosed: Boolean
        get() = keyBytes == null

    /**
     * Executes the given block with the underlying key bytes.
     * Throws IllegalStateException if the handle has already been closed.
     */
    @Synchronized
    fun <T> useBytes(block: (ByteArray) -> T): T {
        val key = checkNotNull(keyBytes) { "SensitiveKeyHandle has already been closed" }
        return block(key)
    }

    /**
     * Creates an independent copy of the handle.
     */
    @Synchronized
    fun duplicate(): SensitiveKeyHandle {
        val key = checkNotNull(keyBytes) { "SensitiveKeyHandle has already been closed" }
        val copy = Arrays.copyOf(key, key.size)
        return SensitiveKeyHandle(copy)
    }

    @Synchronized
    override fun close() {
        keyBytes?.let {
            Arrays.fill(it, 0.toByte())
        }
        keyBytes = null
    }
}
