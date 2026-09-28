package com.suyaphot.app.core.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles binary media encryption and decryption in the SUPH v1 format.
 */
class VaultCrypto {

    companion object {
        val MAGIC_BYTES = byteArrayOf('S'.code.toByte(), 'U'.code.toByte(), 'P'.code.toByte(), 'H'.code.toByte())
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_SIZE = 44
        const val BUFFER_SIZE = 256 * 1024 // 256 KiB buffer

        private val INFO_MEDIA_SUBKEY = "suya-phot-media-key".toByteArray(Charsets.UTF_8)
        private val INFO_META_SUBKEY = "suya-phot-meta-key".toByteArray(Charsets.UTF_8)
        private val INFO_THUMB_SUBKEY = "suya-phot-thumb-key".toByteArray(Charsets.UTF_8)
    }

    data class VerificationResult(
        val plaintextSize: Long,
        val sha256: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VerificationResult) return false
            return plaintextSize == other.plaintextSize && sha256.contentEquals(other.sha256)
        }

        override fun hashCode(): Int {
            var result = plaintextSize.hashCode()
            result = 31 * result + sha256.contentHashCode()
            return result
        }
    }

    /**
     * Derives the media subkey from the vault master key.
     */
    fun deriveMediaSubkey(masterKey: ByteArray): ByteArray {
        return HkdfSha256.derive(masterKey, salt = ByteArray(0), info = INFO_MEDIA_SUBKEY, length = 32)
    }

    /**
     * Derives the metadata encryption subkey from the vault master key.
     */
    fun deriveMetaSubkey(masterKey: ByteArray): ByteArray {
        return HkdfSha256.derive(masterKey, salt = ByteArray(0), info = INFO_META_SUBKEY, length = 32)
    }

    /**
     * Derives the thumbnail encryption subkey from the vault master key.
     */
    fun deriveThumbSubkey(masterKey: ByteArray): ByteArray {
        return HkdfSha256.derive(masterKey, salt = ByteArray(0), info = INFO_THUMB_SUBKEY, length = 32)
    }

    /**
     * Derives a per-item key from the media subkey using the item salt and item UUID.
     */
    fun deriveItemKey(mediaSubkey: ByteArray, itemSalt: ByteArray, itemId: String): ByteArray {
        val info = itemId.toByteArray(Charsets.UTF_8)
        return HkdfSha256.derive(mediaSubkey, salt = itemSalt, info = info, length = 32)
    }

    /**
     * Streams media from [input], computes SHA-256 and writes an encrypted SUPH v1 file to [outputFile].
     *
     * @param input Stream of plaintext media.
     * @param outputFile Partial destination file.
     * @param mediaSubkey Derived media subkey.
     * @param itemId Unique ID of the media item.
     * @param isVideo True if video, false if image.
     * @param plaintextSize Known plaintext size, or -1 if unknown.
     * @param onProgress Callback invoked periodically with (bytesProcessed, totalExpectedBytes).
     * @return SHA-256 digest and total plaintext bytes read.
     */
    fun encryptStream(
        input: InputStream,
        outputFile: File,
        mediaSubkey: ByteArray,
        itemId: String,
        isVideo: Boolean,
        plaintextSize: Long,
        onProgress: ((bytesProcessed: Long, totalBytes: Long) -> Unit)? = null
    ): VerificationResult {
        outputFile.parentFile?.mkdirs()

        val itemSalt = ByteArray(16)
        SecureRandom().nextBytes(itemSalt)

        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)

        val itemKey = deriveItemKey(mediaSubkey, itemSalt, itemId)

        val headerPlaintextSize = if (plaintextSize >= 0L) plaintextSize else -1L
        val headerBuffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        headerBuffer.put(MAGIC_BYTES)
        headerBuffer.put(FORMAT_VERSION)
        headerBuffer.put(if (isVideo) 1.toByte() else 0.toByte())
        headerBuffer.putShort(0.toShort()) // reserved
        headerBuffer.put(itemSalt)
        headerBuffer.put(nonce)
        headerBuffer.putLong(headerPlaintextSize)
        val headerBytes = headerBuffer.array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(itemKey, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(headerBytes)

        val sha256Digest = MessageDigest.getInstance("SHA-256")
        var totalRead = 0L
        var lastProgressTime = 0L

        try {
            FileOutputStream(outputFile).use { fos ->
                fos.write(headerBytes)
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    sha256Digest.update(buffer, 0, read)
                    val encrypted = cipher.update(buffer, 0, read)
                    if (encrypted != null && encrypted.isNotEmpty()) {
                        fos.write(encrypted)
                    }
                    totalRead += read

                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime >= 150) {
                        lastProgressTime = now
                        onProgress?.invoke(totalRead, plaintextSize)
                    }
                }

                val finalBytes = cipher.doFinal()
                if (finalBytes != null && finalBytes.isNotEmpty()) {
                    fos.write(finalBytes)
                }

                fos.flush()
                runCatching { fos.fd.sync() }
            }
        } finally {
            itemKey.fill(0)
        }

        return VerificationResult(
            plaintextSize = totalRead,
            sha256 = sha256Digest.digest()
        )
    }

    /**
     * Verifies that the encrypted file can be authenticated and decrypted cleanly,
     * returning its plaintext size and computed SHA-256 without loading the full file in memory.
     */
    fun verifyAndHash(
        file: File,
        mediaSubkey: ByteArray,
        itemId: String
    ): VerificationResult {
        require(file.exists() && file.length() >= HEADER_SIZE + 16) { "Encrypted file does not exist or is truncated: ${file.name}" }

        FileInputStream(file).use { fis ->
            val headerBytes = ByteArray(HEADER_SIZE)
            var headerRead = 0
            while (headerRead < HEADER_SIZE) {
                val r = fis.read(headerBytes, headerRead, HEADER_SIZE - headerRead)
                check(r != -1) { "Unexpected EOF while reading header" }
                headerRead += r
            }

            check(headerBytes[0] == MAGIC_BYTES[0] &&
                    headerBytes[1] == MAGIC_BYTES[1] &&
                    headerBytes[2] == MAGIC_BYTES[2] &&
                    headerBytes[3] == MAGIC_BYTES[3]) { "Invalid magic bytes in vault file" }
            check(headerBytes[4] == FORMAT_VERSION) { "Unsupported vault file version: ${headerBytes[4]}" }

            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
            buf.position(8)
            val itemSalt = ByteArray(16)
            buf.get(itemSalt)

            val nonce = ByteArray(12)
            buf.get(nonce)

            val expectedPlaintextSize = buf.getLong()

            val itemKey = deriveItemKey(mediaSubkey, itemSalt, itemId)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(itemKey, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(headerBytes)

                val sha256Digest = MessageDigest.getInstance("SHA-256")
                val inputBuffer = ByteArray(BUFFER_SIZE)
                var totalPlaintext = 0L

                while (true) {
                    val read = fis.read(inputBuffer)
                    if (read < 0) break
                    val plain = cipher.update(inputBuffer, 0, read)
                    if (plain != null && plain.isNotEmpty()) {
                        sha256Digest.update(plain)
                        totalPlaintext += plain.size
                        plain.fill(0)
                    }
                }

                val finalPlain = cipher.doFinal()
                if (finalPlain != null && finalPlain.isNotEmpty()) {
                    sha256Digest.update(finalPlain)
                    totalPlaintext += finalPlain.size
                    finalPlain.fill(0)
                }

                if (expectedPlaintextSize >= 0L) {
                    check(totalPlaintext == expectedPlaintextSize) {
                        "Plaintext size mismatch: expected $expectedPlaintextSize, got $totalPlaintext"
                    }
                }

                return VerificationResult(
                    plaintextSize = totalPlaintext,
                    sha256 = sha256Digest.digest()
                )
            } finally {
                itemKey.fill(0)
            }
        }
    }

    /**
     * Streams decrypted media from [sourceEncryptedFile] into [outputStream].
     */
    fun decryptTo(
        sourceEncryptedFile: File,
        mediaSubkey: ByteArray,
        itemId: String,
        outputStream: OutputStream,
        onProgress: ((bytesDecrypted: Long, totalBytes: Long) -> Unit)? = null
    ): VerificationResult {
        FileInputStream(sourceEncryptedFile).use { fis ->
            val headerBytes = ByteArray(HEADER_SIZE)
            var headerRead = 0
            while (headerRead < HEADER_SIZE) {
                val r = fis.read(headerBytes, headerRead, HEADER_SIZE - headerRead)
                check(r != -1) { "Unexpected EOF reading header" }
                headerRead += r
            }

            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            check(magic.contentEquals(MAGIC_BYTES)) { "Invalid magic bytes in vault file" }
            val version = buf.get()
            check(version == FORMAT_VERSION) { "Unsupported format version: $version" }
            buf.get() // type flag
            buf.getShort() // reserved
            val itemSalt = ByteArray(16)
            buf.get(itemSalt)
            val nonce = ByteArray(12)
            buf.get(nonce)
            val expectedPlaintextSize = buf.getLong()

            val itemKey = deriveItemKey(mediaSubkey, itemSalt, itemId)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(itemKey, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(headerBytes)

                val sha256Digest = MessageDigest.getInstance("SHA-256")
                var plainBytesWritten = 0L
                var lastProgressTime = 0L
                val inputBuffer = ByteArray(BUFFER_SIZE)

                while (true) {
                    val read = fis.read(inputBuffer)
                    if (read < 0) break
                    val plain = cipher.update(inputBuffer, 0, read)
                    if (plain != null && plain.isNotEmpty()) {
                        outputStream.write(plain)
                        sha256Digest.update(plain)
                        plainBytesWritten += plain.size
                        val now = System.currentTimeMillis()
                        if (now - lastProgressTime >= 150) {
                            lastProgressTime = now
                            onProgress?.invoke(plainBytesWritten, expectedPlaintextSize)
                        }
                    }
                }

                val finalPlain = cipher.doFinal()
                if (finalPlain != null && finalPlain.isNotEmpty()) {
                    outputStream.write(finalPlain)
                    sha256Digest.update(finalPlain)
                    plainBytesWritten += finalPlain.size
                }
                outputStream.flush()

                if (expectedPlaintextSize >= 0L) {
                    check(plainBytesWritten == expectedPlaintextSize) {
                        "Plaintext size mismatch: expected $expectedPlaintextSize, got $plainBytesWritten"
                    }
                }

                return VerificationResult(
                    plaintextSize = plainBytesWritten,
                    sha256 = sha256Digest.digest()
                )
            } finally {
                itemKey.fill(0)
            }
        }
    }

    /**
     * Opens a streaming InputStream that decrypts on the fly for viewing media or generating thumbnails.
     */
    fun openDecryptedStream(
        sourceEncryptedFile: File,
        mediaSubkey: ByteArray,
        itemId: String
    ): InputStream {
        val fis = FileInputStream(sourceEncryptedFile)
        try {
            val headerBytes = ByteArray(HEADER_SIZE)
            var headerRead = 0
            while (headerRead < HEADER_SIZE) {
                val r = fis.read(headerBytes, headerRead, HEADER_SIZE - headerRead)
                check(r != -1) { "Unexpected EOF in header" }
                headerRead += r
            }

            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            check(magic.contentEquals(MAGIC_BYTES)) { "Invalid magic bytes in vault file" }
            val version = buf.get()
            check(version == FORMAT_VERSION) { "Unsupported format version: $version" }
            buf.get() // type flag
            buf.getShort() // reserved
            val itemSalt = ByteArray(16)
            buf.get(itemSalt)
            val nonce = ByteArray(12)
            buf.get(nonce)

            val itemKey = deriveItemKey(mediaSubkey, itemSalt, itemId)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(itemKey, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(headerBytes)

                return CipherInputStream(fis, cipher)
            } finally {
                itemKey.fill(0)
            }
        } catch (e: Exception) {
            fis.close()
            throw e
        }
    }
}
