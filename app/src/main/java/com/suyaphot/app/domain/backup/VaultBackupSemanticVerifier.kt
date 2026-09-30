package com.suyaphot.app.domain.backup

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.domain.folders.FolderManager
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object VaultBackupSemanticVerifier {

    fun verifyFolderName(
        folderId: String,
        encryptedName: ByteArray,
        metaSubkey: ByteArray
    ): String {
        val plain = try {
            Aead.decryptWithPrependedNonce(
                keyBytes = metaSubkey,
                payload = encryptedName,
                aad = folderId.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            throw BackupException(
                BackupError.VAULT_INTEGRITY_CHECK_FAILED,
                "Folder name ciphertext is corrupt or unauthenticated for folder $folderId: ${e.message}",
                cause = e
            )
        }

        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val decoded = try {
                decoder.decode(ByteBuffer.wrap(plain)).toString()
            } catch (e: Exception) {
                throw BackupException(
                    BackupError.VAULT_INTEGRITY_CHECK_FAILED,
                    "Folder name contains invalid UTF-8 for folder $folderId",
                    cause = e
                )
            }
            val normalized = try {
                FolderManager.normalizeFolderName(decoded)
            } catch (e: Exception) {
                throw BackupException(
                    BackupError.VAULT_INTEGRITY_CHECK_FAILED,
                    "Folder name is invalid after normalization for folder $folderId: ${e.message}",
                    cause = e
                )
            }
            return normalized
        } finally {
            plain.fill(0)
        }
    }

    fun verifyMediaMetadata(
        itemId: String,
        encryptedMetadata: ByteArray,
        metaSubkey: ByteArray
    ): PrivateMediaMetadata {
        val decryptedMeta = try {
            Aead.decryptWithPrependedNonce(
                keyBytes = metaSubkey,
                payload = encryptedMetadata,
                aad = itemId.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            throw BackupException(
                BackupError.VAULT_INTEGRITY_CHECK_FAILED,
                "Media metadata ciphertext is corrupt or unauthenticated for item $itemId: ${e.message}",
                cause = e
            )
        }

        return try {
            PrivateMediaMetadata.deserialize(decryptedMeta)
        } catch (e: Exception) {
            throw BackupException(
                BackupError.VAULT_INTEGRITY_CHECK_FAILED,
                "Media metadata deserialization failed for item $itemId: ${e.message}",
                cause = e
            )
        } finally {
            decryptedMeta.fill(0)
        }
    }

    fun verifyFolderRecoveryEnvelope(
        lockId: String,
        encryptedRecovery: ByteArray,
        metaSubkey: ByteArray
    ) {
        val token = try {
            Aead.decryptWithPrependedNonce(
                keyBytes = metaSubkey,
                payload = encryptedRecovery,
                aad = FolderLockCryptoFormat.recoveryAad(lockId)
            )
        } catch (e: Exception) {
            throw BackupException(
                BackupError.VAULT_INTEGRITY_CHECK_FAILED,
                "Protected folder lock $lockId has corrupt recovery envelope: ${e.message}",
                cause = e
            )
        }

        try {
            if (token.size != 32) {
                throw BackupException(
                    BackupError.VAULT_INTEGRITY_CHECK_FAILED,
                    "Protected folder lock $lockId has invalid recovery token size ${token.size}."
                )
            }
        } finally {
            token.fill(0)
        }
    }
}
