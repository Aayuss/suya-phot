package com.suyaphot.app.domain.backup

import android.content.Context
import androidx.room.withTransaction
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.FolderLockEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

data class BackupRestoreResult(
    val vaultId: String,
    val mediaCount: Int,
    val folderCount: Int
)

class VaultBackupImporter(
    private val context: Context,
    private val database: SuyaDatabase,
    private val fileStore: VaultFileStore,
    private val keyManager: KeyManager,
    private val privacyCoordinator: FolderPrivacyCoordinator
) {

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Hex string must have an even length" }
        val result = ByteArray(length / 2)
        for (i in indices step 2) {
            result[i / 2] = substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    suspend fun restoreVault(
        inputStream: InputStream,
        recoveryCodeInput: String,
        newCredential: CharArray,
        newCredentialType: Int,
        onProgress: (bytesRead: Long, totalEstimatedBytes: Long, itemsRead: Int, totalItems: Int) -> Unit
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        val bufferedIn = BufferedInputStream(inputStream, BackupArchiveFormat.BUFFER_SIZE)
        val dis = DataInputStream(bufferedIn)

        // Read and verify header
        val magic = ByteArray(4)
        dis.readFully(magic)
        require(magic.contentEquals(BackupArchiveFormat.MAGIC)) { "Not a valid Suya Phot backup archive" }

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

        var totalBytesRead = 4L + 4 + 16 + 4 + envLen + 4 + 12 + manifestLen

        // Decrypt master key using recovery code
        val normalized = keyManager.normalizeRecoverySecret(recoveryCodeInput)
        val secretBytes = normalized.toByteArray(Charsets.UTF_8)
        val recoveryKek = ByteArray(32)
        val masterKey: ByteArray
        try {
            val derivedKek = HkdfSha256.derive(
                ikm = secretBytes,
                salt = salt,
                info = BackupArchiveFormat.KEK_INFO.toByteArray(Charsets.UTF_8),
                length = 32
            )
            System.arraycopy(derivedKek, 0, recoveryKek, 0, 32)
            derivedKek.fill(0)

            require(recoveryEnvelope.size >= 12 + 16) { "Truncated recovery envelope" }
            val envNonce = recoveryEnvelope.copyOfRange(0, 12)
            val envCiphertext = recoveryEnvelope.copyOfRange(12, recoveryEnvelope.size)

            masterKey = try {
                Aead.decrypt(
                    keyBytes = recoveryKek,
                    nonce = envNonce,
                    aad = BackupArchiveFormat.RECOVERY_AAD.toByteArray(Charsets.UTF_8),
                    ciphertext = envCiphertext
                )
            } catch (e: Exception) {
                throw IllegalArgumentException("Incorrect Recovery Code or corrupted backup", e)
            }
        } finally {
            secretBytes.fill(0)
            recoveryKek.fill(0)
        }

        // Decrypt manifest
        val manifestKey = ByteArray(32)
        val manifest: BackupManifest
        try {
            val derivedManifestKey = HkdfSha256.derive(
                ikm = masterKey,
                salt = salt,
                info = BackupArchiveFormat.MANIFEST_KEY_INFO.toByteArray(Charsets.UTF_8),
                length = 32
            )
            System.arraycopy(derivedManifestKey, 0, manifestKey, 0, 32)
            derivedManifestKey.fill(0)

            val manifestPlain = try {
                Aead.decrypt(
                    keyBytes = manifestKey,
                    nonce = manifestNonce,
                    aad = BackupArchiveFormat.MANIFEST_AAD.toByteArray(Charsets.UTF_8),
                    ciphertext = manifestCiphertext
                )
            } catch (e: Exception) {
                throw IllegalStateException("Failed to decrypt backup manifest", e)
            }

            try {
                manifest = BackupManifest.fromJsonString(manifestPlain.toString(Charsets.UTF_8))
            } finally {
                manifestPlain.fill(0)
            }
        } finally {
            manifestKey.fill(0)
        }

        val stagingDir = File(context.noBackupFilesDir, "staging_${manifest.archiveId}").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        var itemsRead = 0
        val totalExpectedItems = manifest.mediaItems.size
        var totalEstimatedBytes = totalBytesRead + manifest.mediaItems.sumOf { it.cipherSize + 32 }

        try {
            // Read entries
            val buffer = ByteArray(BackupArchiveFormat.BUFFER_SIZE)
            var reachedEnd = false

            while (!reachedEnd) {
                val nextByte = dis.read()
                if (nextByte == -1) {
                    throw IllegalStateException("Unexpected end of archive stream before end marker")
                }

                if (nextByte.toByte() == BackupArchiveFormat.END_MARKER[0]) {
                    val remainingEnd = ByteArray(3)
                    dis.readFully(remainingEnd)
                    val fullEnd = byteArrayOf(nextByte.toByte(), remainingEnd[0], remainingEnd[1], remainingEnd[2])
                    require(fullEnd.contentEquals(BackupArchiveFormat.END_MARKER)) {
                        "Corrupted archive termination marker"
                    }
                    totalBytesRead += 4
                    reachedEnd = true
                    break
                }

                val typeByte = nextByte.toByte()
                require(
                    typeByte == BackupArchiveFormat.ENTRY_TYPE_MEDIA ||
                    typeByte == BackupArchiveFormat.ENTRY_TYPE_THUMB ||
                    typeByte == BackupArchiveFormat.ENTRY_TYPE_PREVIEW
                ) { "Unknown entry type: $typeByte" }

                val idLen = dis.readShort().toInt()
                require(idLen in 1..256) { "Invalid entry id length: $idLen" }
                val idBytes = ByteArray(idLen)
                dis.readFully(idBytes)
                val id = String(idBytes, Charsets.UTF_8)

                val cipherLength = dis.readLong()
                require(cipherLength >= 0) { "Negative cipher length: $cipherLength" }
                totalBytesRead += 1 + 2 + idLen + 8

                val typePrefix = when (typeByte) {
                    BackupArchiveFormat.ENTRY_TYPE_MEDIA -> "media"
                    BackupArchiveFormat.ENTRY_TYPE_THUMB -> "thumb"
                    BackupArchiveFormat.ENTRY_TYPE_PREVIEW -> "preview"
                    else -> "unknown"
                }
                val stagedFile = File(stagingDir, "${typePrefix}_$id.bin")

                val digest = MessageDigest.getInstance("SHA-256")
                var remaining = cipherLength
                FileOutputStream(stagedFile).use { fos ->
                    while (remaining > 0) {
                        val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                        val count = dis.read(buffer, 0, toRead)
                        if (count == -1) throw IllegalStateException("Premature end of file reading $id")
                        fos.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        remaining -= count
                        totalBytesRead += count
                        onProgress(totalBytesRead, totalEstimatedBytes, itemsRead, totalExpectedItems)
                    }
                    fos.flush()
                }

                val expectedSha256 = ByteArray(32)
                dis.readFully(expectedSha256)
                totalBytesRead += 32
                val computedSha256 = digest.digest()

                require(computedSha256.contentEquals(expectedSha256)) {
                    "Integrity check failed: checksum mismatch for $typePrefix item $id"
                }

                if (typeByte == BackupArchiveFormat.ENTRY_TYPE_MEDIA) {
                    itemsRead++
                }
                onProgress(totalBytesRead, totalEstimatedBytes, itemsRead, totalExpectedItems)
            }

            // Create device-bound envelopes with hardware Keystore pepper
            val newPinEnvelope = keyManager.createPinEnvelope(masterKey, newCredential)
            val newRecoveryEnvelope = keyManager.createRecoveryEnvelope(masterKey, normalized)

            // Commit to database and file store inside transaction
            database.withTransaction {
                // If a vault with the same kind already exists on this device, remove it cleanly
                val existingVault = database.vaultDao().getVaultByKind(manifest.vaultKindCode)
                if (existingVault != null) {
                    // Delete existing vault's files from disk
                    val existingVaultDir = fileStore.getVaultDir(existingVault.id)
                    if (existingVaultDir.exists()) {
                        existingVaultDir.deleteRecursively()
                    }
                    // Remove from database (cascades to folders, locks, media)
                    database.openHelper.writableDatabase.execSQL("DELETE FROM vaults WHERE id = '${existingVault.id}'")
                }

                // Insert restored Vault
                database.vaultDao().insert(
                    VaultEntity(
                        id = manifest.vaultId,
                        kindCode = manifest.vaultKindCode,
                        createdAt = manifest.createdAt,
                        schemaVersion = manifest.schemaVersion,
                        pinEnvelope = newPinEnvelope.serialize(),
                        recoveryEnvelope = newRecoveryEnvelope.serialize(),
                        biometricEnvelope = null,
                        biometricIv = null,
                        credentialTypeCode = newCredentialType
                    )
                )

                // Insert Folders
                for (f in manifest.folders) {
                    database.folderDao().insert(
                        FolderEntity(
                            id = f.id,
                            vaultId = manifest.vaultId,
                            parentId = f.parentId,
                            encryptedName = f.encryptedNameHex.decodeHex(),
                            createdAt = f.createdAt,
                            updatedAt = f.updatedAt,
                            coverMediaId = null,
                            sortOrder = f.sortOrder,
                            directHidden = f.directHidden,
                            effectiveHidden = f.effectiveHidden,
                            lockId = f.lockId,
                            effectiveProtected = f.effectiveProtected
                        )
                    )
                }

                // Insert Folder Locks
                for (l in manifest.folderLocks) {
                    database.folderLockDao().insert(
                        FolderLockEntity(
                            id = l.id,
                            vaultId = manifest.vaultId,
                            folderId = l.folderId,
                            credentialTypeCode = l.credentialTypeCode,
                            credentialEnvelope = l.credentialEnvelopeHex.decodeHex(),
                            biometricEnvelope = null,
                            biometricIv = null,
                            recoveryEnvelope = l.recoveryEnvelopeHex?.decodeHex(),
                            createdAt = manifest.createdAt,
                            updatedAt = manifest.createdAt
                        )
                    )
                }

                // Insert Media Items and move staged files
                for (m in manifest.mediaItems) {
                    val mediaFile = fileStore.getMediaFile(manifest.vaultId, m.id)
                    val stagedMedia = File(stagingDir, "media_${m.id}.bin")
                    if (stagedMedia.exists()) {
                        mediaFile.parentFile?.mkdirs()
                        if (mediaFile.exists()) mediaFile.delete()
                        check(stagedMedia.renameTo(mediaFile)) { "Failed to commit media file ${m.id}" }
                    }

                    var thumbRelPath: String? = null
                    if (m.hasThumb) {
                        val thumbFile = fileStore.getThumbFile(manifest.vaultId, m.id)
                        val stagedThumb = File(stagingDir, "thumb_${m.id}.bin")
                        if (stagedThumb.exists()) {
                            thumbFile.parentFile?.mkdirs()
                            if (thumbFile.exists()) thumbFile.delete()
                            if (stagedThumb.renameTo(thumbFile)) {
                                thumbRelPath = thumbFile.name
                            }
                        }
                    }

                    var previewRelPath: String? = null
                    if (m.hasPreview) {
                        val previewFile = fileStore.getPreviewFile(manifest.vaultId, m.id)
                        val stagedPreview = File(stagingDir, "preview_${m.id}.bin")
                        if (stagedPreview.exists()) {
                            previewFile.parentFile?.mkdirs()
                            if (previewFile.exists()) previewFile.delete()
                            if (stagedPreview.renameTo(previewFile)) {
                                previewRelPath = previewFile.name
                            }
                        }
                    }

                    database.mediaItemDao().insert(
                        MediaItemEntity(
                            id = m.id,
                            vaultId = manifest.vaultId,
                            folderId = m.folderId,
                            mediaTypeCode = m.mediaTypeCode,
                            encryptedMetadata = m.encryptedMetadataHex.decodeHex(),
                            encryptedFileRelativePath = mediaFile.name,
                            encryptedThumbRelativePath = thumbRelPath,
                            plaintextSize = m.plaintextSize,
                            cipherSize = m.cipherSize,
                            sha256Hex = m.sha256Hex,
                            importedAt = m.importedAt,
                            updatedAt = m.updatedAt,
                            favorite = m.favorite,
                            deletedAt = m.deletedAt,
                            previousFolderId = m.previousFolderId,
                            dateTakenMs = m.dateTakenMs,
                            encryptedPreviewRelativePath = previewRelPath,
                            cleanupStateCode = m.cleanupStateCode,
                            concealed = m.concealed
                        )
                    )
                }

                // Recompute privacy and concealment
                privacyCoordinator.recomputeInsideTransaction(manifest.vaultId)
            }

            SafeLog.i("VaultBackupImporter", "Restore complete. Restored ${manifest.mediaItems.size} items")
            BackupRestoreResult(
                vaultId = manifest.vaultId,
                mediaCount = manifest.mediaItems.size,
                folderCount = manifest.folders.size
            )
        } finally {
            masterKey.fill(0)
            newCredential.fill('\u0000')
            stagingDir.deleteRecursively()
        }
    }
}
