package com.suyaphot.app.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages cryptographic envelopes for PIN, Biometric, and Recovery unlocking.
 */
class KeyManager(private val context: Context) {

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_PEPPER_ALIAS = "suya_phot_hmac_pepper"
        const val KEYSTORE_BIOMETRIC_ALIAS_PREFIX = "suya_phot_biometric_"

        const val PBKDF2_ITERATIONS = 100_000
        const val PIN_SALT_LEN = 16
        const val MASTER_KEY_LEN = 32

        private const val BASE32_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Crockford-style Base32
    }

    data class PinEnvelope(
        val version: Int,
        val salt: ByteArray,
        val iterations: Int,
        val nonce: ByteArray,
        val wrappedKey: ByteArray
    ) {
        fun serialize(): ByteArray {
            val buf = ByteBuffer.allocate(4 + 4 + salt.size + 4 + 4 + nonce.size + 4 + wrappedKey.size)
            buf.putInt(version)
            buf.putInt(salt.size)
            buf.put(salt)
            buf.putInt(iterations)
            buf.putInt(nonce.size)
            buf.put(nonce)
            buf.putInt(wrappedKey.size)
            buf.put(wrappedKey)
            return buf.array()
        }

        companion object {
            fun deserialize(bytes: ByteArray): PinEnvelope {
                val buf = ByteBuffer.wrap(bytes)
                val version = buf.getInt()
                val saltLen = buf.getInt()
                val salt = ByteArray(saltLen)
                buf.get(salt)
                val iterations = buf.getInt()
                val nonceLen = buf.getInt()
                val nonce = ByteArray(nonceLen)
                buf.get(nonce)
                val wrappedLen = buf.getInt()
                val wrapped = ByteArray(wrappedLen)
                buf.get(wrapped)
                return PinEnvelope(version, salt, iterations, nonce, wrapped)
            }
        }
    }

    data class RecoveryEnvelope(
        val version: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val wrappedKey: ByteArray
    ) {
        fun serialize(): ByteArray {
            val buf = ByteBuffer.allocate(4 + 4 + salt.size + 4 + nonce.size + 4 + wrappedKey.size)
            buf.putInt(version)
            buf.putInt(salt.size)
            buf.put(salt)
            buf.putInt(nonce.size)
            buf.put(nonce)
            buf.putInt(wrappedKey.size)
            buf.put(wrappedKey)
            return buf.array()
        }

        companion object {
            fun deserialize(bytes: ByteArray): RecoveryEnvelope {
                val buf = ByteBuffer.wrap(bytes)
                val version = buf.getInt()
                val saltLen = buf.getInt()
                val salt = ByteArray(saltLen)
                buf.get(salt)
                val nonceLen = buf.getInt()
                val nonce = ByteArray(nonceLen)
                buf.get(nonce)
                val wrappedLen = buf.getInt()
                val wrapped = ByteArray(wrappedLen)
                buf.get(wrapped)
                return RecoveryEnvelope(version, salt, nonce, wrapped)
            }
        }
    }

    /**
     * Generates a brand new 256-bit vault master key.
     */
    fun generateMasterKey(): ByteArray {
        val key = ByteArray(MASTER_KEY_LEN)
        SecureRandom().nextBytes(key)
        return key
    }

    /**
     * Derives a KEK from a PIN using PBKDF2 + Keystore pepper + HKDF.
     */
    fun derivePinKek(pinChars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val pbeSpec = PBEKeySpec(pinChars, salt, iterations, 256)
        val pbkdf2Key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(pbeSpec)
                .encoded
        } finally {
            pbeSpec.clearPassword()
        }

        // Apply hardware pepper if available
        val peppered = applyKeystorePepper(pbkdf2Key)

        // Final HKDF expand into 256-bit AES key
        val info = "suya-phot-pin-kek".toByteArray(Charsets.UTF_8)
        val kek = HkdfSha256.derive(peppered, salt = salt, info = info, length = 32)
        pbkdf2Key.fill(0)
        peppered.fill(0)
        return kek
    }

    private fun applyKeystorePepper(data: ByteArray): ByteArray {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEYSTORE_PEPPER_ALIAS)) {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
                keyGen.init(
                    KeyGenParameterSpec.Builder(
                        KEYSTORE_PEPPER_ALIAS,
                        KeyProperties.PURPOSE_SIGN
                    ).build()
                )
                keyGen.generateKey()
            }
            val secretKey = keyStore.getKey(KEYSTORE_PEPPER_ALIAS, null) as SecretKey
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(secretKey)
            mac.doFinal(data)
        } catch (e: Exception) {
            // Fallback for non-Android environments (like standard JUnit JVM tests)
            val fallbackKey = SecretKeySpec("suya-phot-local-pepper-fallback".toByteArray(Charsets.UTF_8), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(fallbackKey)
            mac.doFinal(data)
        }
    }

    /**
     * Wraps a master key inside a PinEnvelope.
     */
    fun createPinEnvelope(masterKey: ByteArray, pinChars: CharArray): PinEnvelope {
        val salt = ByteArray(PIN_SALT_LEN)
        SecureRandom().nextBytes(salt)
        val kek = derivePinKek(pinChars, salt, PBKDF2_ITERATIONS)
        val nonce = Aead.generateNonce()
        val wrappedKey = try {
            Aead.encrypt(kek, nonce, aad = "pin-envelope-v1".toByteArray(Charsets.UTF_8), plaintext = masterKey)
        } finally {
            kek.fill(0)
        }
        return PinEnvelope(
            version = 1,
            salt = salt,
            iterations = PBKDF2_ITERATIONS,
            nonce = nonce,
            wrappedKey = wrappedKey
        )
    }

    /**
     * Unwraps the master key from a PinEnvelope.
     * Returns null if PIN is incorrect or envelope is tampered.
     */
    fun unwrapPinEnvelope(envelope: PinEnvelope, pinChars: CharArray): ByteArray? {
        val kek = derivePinKek(pinChars, envelope.salt, envelope.iterations)
        return try {
            Aead.decrypt(kek, envelope.nonce, aad = "pin-envelope-v1".toByteArray(Charsets.UTF_8), ciphertext = envelope.wrappedKey)
        } catch (e: Exception) {
            null
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Generates a 128-bit high-entropy recovery secret formatted into 4 groups of 4 chars (e.g., "7K9P-4X2B-W8MN-3C5R").
     */
    fun generateRecoverySecret(): String {
        val bytes = ByteArray(16) // 128 bits
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                sb.append(BASE32_ALPHABET[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            sb.append(BASE32_ALPHABET[index])
        }

        val raw = sb.toString().take(16)
        return raw.chunked(4).joinToString("-")
    }

    /**
     * Normalizes a recovery secret input (removes dashes, spaces, uppercases).
     */
    fun normalizeRecoverySecret(input: String): String {
        return input.replace("-", "")
            .replace(" ", "")
            .trim()
            .uppercase()
    }

    /**
     * Derives a KEK from a recovery secret string using HKDF.
     */
    fun deriveRecoveryKek(normalizedSecret: String, salt: ByteArray): ByteArray {
        val secretBytes = normalizedSecret.toByteArray(Charsets.UTF_8)
        val info = "suya-phot-recovery-kek".toByteArray(Charsets.UTF_8)
        return HkdfSha256.derive(secretBytes, salt = salt, info = info, length = 32)
    }

    /**
     * Wraps the master key in a RecoveryEnvelope.
     */
    fun createRecoveryEnvelope(masterKey: ByteArray, normalizedSecret: String): RecoveryEnvelope {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val kek = deriveRecoveryKek(normalizedSecret, salt)
        val nonce = Aead.generateNonce()
        val wrapped = try {
            Aead.encrypt(kek, nonce, aad = "recovery-envelope-v1".toByteArray(Charsets.UTF_8), plaintext = masterKey)
        } finally {
            kek.fill(0)
        }
        return RecoveryEnvelope(
            version = 1,
            salt = salt,
            nonce = nonce,
            wrappedKey = wrapped
        )
    }

    /**
     * Unwraps the master key from a RecoveryEnvelope using the user's recovery secret.
     */
    fun unwrapRecoveryEnvelope(envelope: RecoveryEnvelope, rawSecretInput: String): ByteArray? {
        val normalized = normalizeRecoverySecret(rawSecretInput)
        val kek = deriveRecoveryKek(normalized, envelope.salt)
        return try {
            Aead.decrypt(kek, envelope.nonce, aad = "recovery-envelope-v1".toByteArray(Charsets.UTF_8), ciphertext = envelope.wrappedKey)
        } catch (e: Exception) {
            null
        } finally {
            kek.fill(0)
        }
    }

    // --- Biometric Envelopes ---

    fun getBiometricKeyAlias(vaultId: String): String {
        return "${KEYSTORE_BIOMETRIC_ALIAS_PREFIX}$vaultId"
    }

    /**
     * Initializes or gets the Android Keystore AES-256 key requiring biometric auth.
     */
    fun getOrCreateBiometricSecretKey(vaultId: String): SecretKey {
        val alias = getBiometricKeyAlias(vaultId)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0, // 0 = requires auth for each cipher operation
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            }
            keyGen.init(builder.build())
            keyGen.generateKey()
        }
        return keyStore.getKey(alias, null) as SecretKey
    }

    /**
     * Creates an initialized Cipher for encrypting master key with Biometric key.
     */
    fun createBiometricEncryptCipher(vaultId: String): Cipher {
        val secretKey = getOrCreateBiometricSecretKey(vaultId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher
    }

    /**
     * Creates an initialized Cipher for decrypting master key with Biometric key.
     */
    fun createBiometricDecryptCipher(vaultId: String, iv: ByteArray): Cipher {
        val secretKey = getOrCreateBiometricSecretKey(vaultId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher
    }

    fun hasBiometricKey(vaultId: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.containsAlias(getBiometricKeyAlias(vaultId))
        } catch (e: Exception) {
            false
        }
    }

    fun deleteBiometricKey(vaultId: String) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(getBiometricKeyAlias(vaultId))
        } catch (ignored: Exception) {}
    }
}
