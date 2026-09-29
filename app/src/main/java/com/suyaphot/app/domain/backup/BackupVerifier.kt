package com.suyaphot.app.domain.backup

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import com.suyaphot.app.core.crypto.KeyManager
import java.io.DataInputStream
import java.io.InputStream

data class BackupArchiveHeader(
    val version: Int,
    val salt: ByteArray,
    val recoveryEnvelope: ByteArray,
    val manifestNonce: ByteArray,
    val manifestCiphertext: ByteArray
)

data class BackupSummary(
    val archiveId: String,
    val version: Int,
    val createdAt: Long,
    val schemaVersion: Int,
    val vaultId: String,
    val vaultKindCode: Int,
    val folderCount: Int,
    val lockCount: Int,
    val mediaCount: Int,
    val totalPlaintextSize: Long
)

class BackupVerifier(private val keyManager: KeyManager) {

    /**
     * Reads and parses only the header and encrypted manifest without consuming entries.
     */
    fun readHeader(inputStream: InputStream): BackupArchiveHeader {
        val dis = DataInputStream(inputStream)
        val magic = ByteArray(4)
        dis.readFully(magic)
        require(magic.contentEquals(BackupArchiveFormat.MAGIC)) { "Not a valid Suya Phot backup file" }

        val version = dis.readInt()
        require(version == BackupArchiveFormat.CURRENT_VERSION) { "Unsupported backup version: $version" }

        val salt = ByteArray(16)
        dis.readFully(salt)

        val envLen = dis.readInt()
        require(envLen in 48..256) { "Invalid recovery envelope length: $envLen" }
        val recoveryEnvelope = ByteArray(envLen)
        dis.readFully(recoveryEnvelope)

        val manifestLen = dis.readInt()
        require(manifestLen in 16..10_000_000) { "Invalid manifest length: $manifestLen" }
        val manifestNonce = ByteArray(12)
        dis.readFully(manifestNonce)
        val manifestCiphertext = ByteArray(manifestLen)
        dis.readFully(manifestCiphertext)

        return BackupArchiveHeader(
            version = version,
            salt = salt,
            recoveryEnvelope = recoveryEnvelope,
            manifestNonce = manifestNonce,
            manifestCiphertext = manifestCiphertext
        )
    }

    /**
     * Unwraps master key and decrypts the backup manifest using the user's Recovery Code.
     * Returns a BackupSummary detailing what will be restored.
     */
    fun verifyAndInspect(inputStream: InputStream, recoveryCodeInput: String): BackupSummary {
        val header = readHeader(inputStream)
        val normalized = keyManager.normalizeRecoverySecret(recoveryCodeInput)
        val secretBytes = normalized.toByteArray(Charsets.UTF_8)
        val recoveryKek = ByteArray(32)
        val masterKey: ByteArray
        try {
            val derived = HkdfSha256.derive(
                ikm = secretBytes,
                salt = header.salt,
                info = BackupArchiveFormat.KEK_INFO.toByteArray(Charsets.UTF_8),
                length = 32
            )
            System.arraycopy(derived, 0, recoveryKek, 0, 32)
            derived.fill(0)

            require(header.recoveryEnvelope.size >= 12 + 16) { "Corrupt recovery envelope" }
            val envNonce = header.recoveryEnvelope.copyOfRange(0, 12)
            val envCiphertext = header.recoveryEnvelope.copyOfRange(12, header.recoveryEnvelope.size)

            masterKey = try {
                Aead.decrypt(
                    keyBytes = recoveryKek,
                    nonce = envNonce,
                    aad = BackupArchiveFormat.RECOVERY_AAD.toByteArray(Charsets.UTF_8),
                    ciphertext = envCiphertext
                )
            } catch (e: Exception) {
                throw IllegalArgumentException("Incorrect Recovery Code or corrupt backup header", e)
            }
        } finally {
            secretBytes.fill(0)
            recoveryKek.fill(0)
        }

        val manifestKey = ByteArray(32)
        val manifestJsonString: String
        try {
            val derived = HkdfSha256.derive(
                ikm = masterKey,
                salt = header.salt,
                info = BackupArchiveFormat.MANIFEST_KEY_INFO.toByteArray(Charsets.UTF_8),
                length = 32
            )
            System.arraycopy(derived, 0, manifestKey, 0, 32)
            derived.fill(0)

            val manifestBytes = try {
                Aead.decrypt(
                    keyBytes = manifestKey,
                    nonce = header.manifestNonce,
                    aad = BackupArchiveFormat.MANIFEST_AAD.toByteArray(Charsets.UTF_8),
                    ciphertext = header.manifestCiphertext
                )
            } catch (e: Exception) {
                throw IllegalStateException("Failed to decrypt backup manifest", e)
            }
            try {
                manifestJsonString = manifestBytes.toString(Charsets.UTF_8)
            } finally {
                manifestBytes.fill(0)
            }
        } finally {
            masterKey.fill(0)
            manifestKey.fill(0)
        }

        val manifest = BackupManifest.fromJsonString(manifestJsonString)
        val totalPlaintext = manifest.mediaItems.sumOf { it.plaintextSize }

        return BackupSummary(
            archiveId = manifest.archiveId,
            version = manifest.version,
            createdAt = manifest.createdAt,
            schemaVersion = manifest.schemaVersion,
            vaultId = manifest.vaultId,
            vaultKindCode = manifest.vaultKindCode,
            folderCount = manifest.folders.size,
            lockCount = manifest.folderLocks.size,
            mediaCount = manifest.mediaItems.size,
            totalPlaintextSize = totalPlaintext
        )
    }
}
