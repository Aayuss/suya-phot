package com.suyaphot.app.domain.backup

import android.content.Context
import android.os.StatFs
import androidx.room.withTransaction
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.FolderLockEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.media.DerivativeCryptoVerifier
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.LockReason
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultCredentialValidator
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.suyaphot.app.core.util.InternalId
import com.suyaphot.app.domain.folders.FolderManager
import kotlinx.coroutines.sync.Mutex
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
    private val privacyCoordinator: FolderPrivacyCoordinator,
    private val vaultCrypto: VaultCrypto = VaultCrypto(),
    private val sessionManager: SessionManager? = null,
    private val accessManager: FolderAccessManager? = null
) {
    companion object {
        private val restoreMutex = Mutex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Hex string must have an even length" }
        val result = ByteArray(length / 2)
        for (i in indices step 2) {
            result[i / 2] = substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    private fun verifyFolderRecoveryEnvelope(
        entry: BackupFolderLockEntry,
        metaSubkey: ByteArray
    ) {
        val hex = entry.recoveryEnvelopeHex
            ?: throw BackupException(
                BackupError.FOLDER_LOCK_RECOVERY_NOT_READY,
                "Protected folder lock ${entry.id} lacks portable recovery envelope."
            )

        val encrypted = try {
            hex.decodeHex()
        } catch (e: Exception) {
            throw BackupException(
                BackupError.INVALID_ARCHIVE,
                "Failed to decode recovery envelope hex for lock ${entry.id}",
                cause = e
            )
        }

        try {
            VaultBackupSemanticVerifier.verifyFolderRecoveryEnvelope(entry.id, encrypted, metaSubkey)
        } catch (e: BackupException) {
            if (e.error == BackupError.VAULT_INTEGRITY_CHECK_FAILED) {
                throw BackupException(
                    BackupError.FOLDER_LOCK_RECOVERY_CORRUPT,
                    "Corrupt recovery envelope for lock ${entry.id}: ${e.message}",
                    cause = e
                )
            }
            throw e
        } finally {
            encrypted.fill(0)
        }
    }

    private fun verifyFolderNames(
        folders: List<BackupFolderEntry>,
        metaSubkey: ByteArray
    ) {
        val siblingsByParent = HashMap<String?, HashSet<String>>()

        for (f in folders) {
            val encrypted = try {
                f.encryptedNameHex.decodeHex()
            } catch (e: Exception) {
                throw BackupException(BackupError.INVALID_ARCHIVE, "Invalid folder name hex", e)
            }

            val name = try {
                VaultBackupSemanticVerifier.verifyFolderName(f.id, encrypted, metaSubkey)
            } catch (e: BackupException) {
                if (e.error == BackupError.VAULT_INTEGRITY_CHECK_FAILED) {
                    throw BackupException(
                        BackupError.INVALID_ARCHIVE,
                        "Folder name authentication failed for folder ${f.id}: ${e.message}",
                        cause = e
                    )
                }
                throw e
            } finally {
                encrypted.fill(0)
            }

            val siblings = siblingsByParent.getOrPut(f.parentId) { HashSet() }
            if (!siblings.add(name.lowercase())) {
                throw BackupException(
                    BackupError.INVALID_ARCHIVE,
                    "Duplicate sibling folder name '$name' under parent ${f.parentId}"
                )
            }
        }
    }

    suspend fun restoreVault(
        inputStream: InputStream,
        recoveryCodeInput: String,
        newCredential: CharArray,
        newCredentialType: Int,
        onProgress: (bytesRead: Long, totalEstimatedBytes: Long, itemsRead: Int, totalItems: Int) -> Unit
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        if (!restoreMutex.tryLock()) {
            throw BackupException(
                BackupError.RESTORE_ALREADY_RUNNING,
                "A restore operation is already in progress."
            )
        }
        try {
            // P1: Validate new credential format upfront before any file or DB operations
            if (!VaultCredentialValidator.isValid(newCredential, newCredentialType)) {
                newCredential.fill('\u0000')
                throw BackupException(
                    BackupError.INVALID_NEW_CREDENTIAL,
                    "The new lock credential is not valid for this vault type."
                )
            }

            val bufferedIn = BufferedInputStream(inputStream, BackupArchiveFormat.BUFFER_SIZE)
            val dis = DataInputStream(bufferedIn)
            val verifier = BackupVerifier(keyManager)

            // 1. Decrypt header, master key, and manifest
            val (manifest, masterKey) = verifier.decryptManifestAndMasterKey(dis, recoveryCodeInput)

            // 2. FAIL-SAFE: reject restore immediately if a vault of the same kind already exists
            val existingVault = database.vaultDao().getVaultByKind(manifest.vaultKindCode)
            if (existingVault != null) {
                masterKey.fill(0)
                newCredential.fill('\u0000')
                throw BackupException(
                    BackupError.RESTORE_REQUIRES_EMPTY_VAULT,
                    "A vault already exists on this device.\n\nFor safety, Suya Phot will not overwrite an existing vault during restore. Export the current vault first and restore the backup on a fresh installation or another device."
                )
            }

            // P1: Reject DB ID collision
            if (database.vaultDao().getVault(manifest.vaultId) != null) {
                masterKey.fill(0)
                newCredential.fill('\u0000')
                throw BackupException(
                    BackupError.RESTORE_TARGET_COLLISION,
                    "A vault with ID ${manifest.vaultId} already exists in the database."
                )
            }

            // P1: Defensive Safety: Reject pre-existing target vault directory on disk
            val finalVaultDir = fileStore.vaultDirPath(manifest.vaultId)
            if (finalVaultDir.exists()) {
                masterKey.fill(0)
                newCredential.fill('\u0000')
                throw BackupException(
                    BackupError.RESTORE_TARGET_COLLISION,
                    "Target storage directory for vault ${manifest.vaultId} already exists."
                )
            }

        // 3. Strict manifest validation
        val validated = try {
            BackupManifestValidator.validate(manifest.version, manifest)
        } catch (e: Exception) {
            masterKey.fill(0)
            newCredential.fill('\u0000')
            throw e
        }

        // 4. Free-space precheck
        val stat = StatFs(context.noBackupFilesDir.absolutePath)
        val availableBytes = stat.availableBytes
        val declaredBodyBytes = validated.declaredBodyBytes
        val safetyMargin = maxOf(256L * 1024 * 1024, declaredBodyBytes / 20L)
        val requiredBytes = try {
            Math.addExact(declaredBodyBytes, safetyMargin)
        } catch (_: ArithmeticException) {
            masterKey.fill(0)
            newCredential.fill('\u0000')
            throw BackupException(BackupError.INVALID_ARCHIVE)
        }

        if (availableBytes < requiredBytes) {
            masterKey.fill(0)
            newCredential.fill('\u0000')
            throw BackupException(
                BackupError.NOT_ENOUGH_SPACE,
                "Not enough storage to restore this vault. Required: ${requiredBytes / (1024 * 1024)} MB, Available: ${availableBytes / (1024 * 1024)} MB"
            )
        }

        // 5. Create secure restore staging directory with local random UUID
        val stagingDir = fileStore.createBackupRestoreStagingDir()
        var stagedSequence = 0L

        fun newStagedBodyFile(entryType: Byte): File {
            val type = when (entryType) {
                BackupArchiveFormat.ENTRY_TYPE_MEDIA -> "media"
                BackupArchiveFormat.ENTRY_TYPE_THUMB -> "thumb"
                BackupArchiveFormat.ENTRY_TYPE_PREVIEW -> "preview"
                else -> "other"
            }
            return File(stagingDir, "%08d_%s.bin".format(stagedSequence++, type))
        }

        val seenEntries = HashSet<BackupEntryKey>()
        val stagedMediaFiles = HashMap<String, File>()
        val stagedThumbFiles = HashMap<String, File>()
        val stagedPreviewFiles = HashMap<String, File>()

        val buffer = ByteArray(BackupArchiveFormat.BUFFER_SIZE)
        var reachedEnd = false
        var bytesRead = 0L
        val totalExpectedItems = manifest.mediaItems.size
        val totalEstimatedBytes = declaredBodyBytes
        var itemsRead = 0

        try {
            // 6. Stream entries and stage to temporary sequence files
            while (!reachedEnd) {
                val nextByte = dis.read()
                if (nextByte == -1) {
                    throw BackupException(BackupError.INVALID_ARCHIVE, "Unexpected end of archive stream before end marker")
                }

                if (nextByte.toByte() == BackupArchiveFormat.END_MARKER[0]) {
                    val remainingEnd = ByteArray(3)
                    dis.readFully(remainingEnd)
                    val fullEnd = byteArrayOf(nextByte.toByte(), remainingEnd[0], remainingEnd[1], remainingEnd[2])
                    if (!fullEnd.contentEquals(BackupArchiveFormat.END_MARKER)) {
                        throw BackupException(BackupError.INVALID_ARCHIVE, "Corrupted archive termination marker")
                    }
                    bytesRead += 4
                    reachedEnd = true
                    break
                }

                val entryType = nextByte.toByte()
                val idLength = dis.readShort().toInt() and 0xFFFF
                if (idLength !in 1..256) {
                    throw BackupException(BackupError.INVALID_ARCHIVE, "Invalid entry ID length: $idLength")
                }
                val idBytes = ByteArray(idLength)
                dis.readFully(idBytes)
                val entryId = idBytes.toString(Charsets.UTF_8)
                val entryLength = dis.readLong()
                if (entryLength < 0 || entryLength > BackupLimits.MAX_SINGLE_MEDIA_BYTES) {
                    throw BackupException(BackupError.INVALID_ARCHIVE, "Invalid entry body length: $entryLength")
                }
                bytesRead += 1 + 2 + idLength + 8

                if (!InternalId.isValid(entryId)) {
                    throw BackupException(BackupError.INVALID_ARCHIVE, "Invalid entry ID format: $entryId")
                }

                val key = BackupEntryKey(entryType, entryId)
                if (!seenEntries.add(key)) {
                    throw BackupException(BackupError.INVALID_ARCHIVE, "Duplicate entry in backup: type=$entryType, id=$entryId")
                }

                val expectedDesc = validated.descriptorsByKey[key]
                if (manifest.version >= BackupArchiveFormat.VERSION_2) {
                    if (expectedDesc == null) {
                        throw BackupException(
                            BackupError.INVALID_ARCHIVE,
                            "Body entry ($entryType, $entryId) was not declared in authenticated descriptors"
                        )
                    }
                    if (expectedDesc.cipherLength != entryLength) {
                        throw BackupException(
                            BackupError.CORRUPT_MEDIA,
                            "Declared body length ${expectedDesc.cipherLength} != entry length $entryLength for $entryId"
                        )
                    }
                } else {
                    // P0-C: Strict V1 body grammar before reading or staging body
                    if (entryType !in listOf(
                            BackupArchiveFormat.ENTRY_TYPE_MEDIA,
                            BackupArchiveFormat.ENTRY_TYPE_THUMB,
                            BackupArchiveFormat.ENTRY_TYPE_PREVIEW
                        )
                    ) {
                        throw BackupException(BackupError.INVALID_ARCHIVE, "Unknown V1 entry type: $entryType")
                    }

                    val media = validated.mediaById[entryId]
                        ?: throw BackupException(BackupError.INVALID_ARCHIVE, "Undeclared V1 media item ID: $entryId")

                    when (entryType) {
                        BackupArchiveFormat.ENTRY_TYPE_MEDIA -> {
                            if (entryLength != media.cipherSize) {
                                throw BackupException(
                                    BackupError.CORRUPT_MEDIA,
                                    "Declared V1 cipher size ${media.cipherSize} != entry length $entryLength for $entryId"
                                )
                            }
                        }
                        BackupArchiveFormat.ENTRY_TYPE_THUMB -> {
                            if (!media.hasThumb) {
                                throw BackupException(BackupError.INVALID_ARCHIVE, "Undeclared V1 thumbnail for $entryId")
                            }
                            if (entryLength > BackupLimits.MAX_V1_THUMB_BYTES) {
                                throw BackupException(BackupError.INVALID_ARCHIVE, "Oversized V1 thumbnail for $entryId: $entryLength")
                            }
                        }
                        BackupArchiveFormat.ENTRY_TYPE_PREVIEW -> {
                            if (!media.hasPreview) {
                                throw BackupException(BackupError.INVALID_ARCHIVE, "Undeclared V1 preview for $entryId")
                            }
                            if (entryLength > BackupLimits.MAX_V1_PREVIEW_BYTES) {
                                throw BackupException(BackupError.INVALID_ARCHIVE, "Oversized V1 preview for $entryId: $entryLength")
                            }
                        }
                    }
                }

                val digest = MessageDigest.getInstance("SHA-256")
                val isV1Derivative = manifest.version == BackupArchiveFormat.VERSION_1 && entryType != BackupArchiveFormat.ENTRY_TYPE_MEDIA

                if (isV1Derivative) {
                    // P0-C: Discard V1 optional derivatives into buffer without staging to disk
                    var remaining = entryLength
                    while (remaining > 0L) {
                        val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                        val r = dis.read(buffer, 0, toRead)
                        if (r == -1) {
                            throw BackupException(BackupError.INVALID_ARCHIVE, "Premature EOF in entry body for $entryId")
                        }
                        digest.update(buffer, 0, r)
                        bytesRead += r
                        remaining -= r
                    }
                } else {
                    val stagedFile = newStagedBodyFile(entryType)
                    FileOutputStream(stagedFile).use { fos ->
                        var remaining = entryLength
                        while (remaining > 0L) {
                            val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                            val r = dis.read(buffer, 0, toRead)
                            if (r == -1) {
                                throw BackupException(BackupError.INVALID_ARCHIVE, "Premature EOF in entry body for $entryId")
                            }
                            fos.write(buffer, 0, r)
                            digest.update(buffer, 0, r)
                            bytesRead += r
                            remaining -= r
                        }
                    }

                    when (entryType) {
                        BackupArchiveFormat.ENTRY_TYPE_MEDIA -> {
                            stagedMediaFiles[entryId] = stagedFile
                            itemsRead++
                            onProgress(bytesRead, totalEstimatedBytes, itemsRead, totalExpectedItems)
                        }
                        BackupArchiveFormat.ENTRY_TYPE_THUMB -> stagedThumbFiles[entryId] = stagedFile
                        BackupArchiveFormat.ENTRY_TYPE_PREVIEW -> stagedPreviewFiles[entryId] = stagedFile
                    }
                }

                val computedSha256 = digest.digest().toHex()
                val trailingSha = ByteArray(32)
                dis.readFully(trailingSha)
                bytesRead += 32
                val trailingShaHex = trailingSha.toHex()

                if (!computedSha256.equals(trailingShaHex, ignoreCase = true)) {
                    throw BackupException(BackupError.CORRUPT_MEDIA, "Body SHA-256 mismatch for entry $entryId")
                }

                if (expectedDesc != null) {
                    if (!expectedDesc.cipherSha256Hex.equals(computedSha256, ignoreCase = true)) {
                        throw BackupException(
                            BackupError.CORRUPT_MEDIA,
                            "Authenticated descriptor SHA-256 mismatch for entry $entryId"
                        )
                    }
                }
            }

            // Check for unexpected trailing bytes
            if (dis.read() != -1) {
                throw BackupException(BackupError.INVALID_ARCHIVE, "Unexpected trailing bytes after archive end marker")
            }

            // Verify body completeness
            if (manifest.version >= BackupArchiveFormat.VERSION_2) {
                if (seenEntries != validated.descriptorsByKey.keys) {
                    throw BackupException(
                        BackupError.INVALID_ARCHIVE,
                        "Archive body entries do not match authenticated descriptors"
                    )
                }
            } else {
                for (m in manifest.mediaItems) {
                    val requiredKey = BackupEntryKey(BackupArchiveFormat.ENTRY_TYPE_MEDIA, m.id)
                    if (requiredKey !in seenEntries) {
                        throw BackupException(BackupError.MISSING_MEDIA, "Missing required media body for item ${m.id}")
                    }
                }
            }

            // 7. Derive keys and perform full cryptographic verification of all staged media and metadata
            val mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
            val metaSubkey = vaultCrypto.deriveMetaSubkey(masterKey)
            val thumbSubkey = vaultCrypto.deriveThumbSubkey(masterKey)

            try {
                // P0-D: Decrypt and validate all folder names under metaSubkey before commit
                verifyFolderNames(manifest.folders, metaSubkey)

                // P0-B: Verify every folder recovery envelope under metaSubkey before commit
                for (lock in manifest.folderLocks) {
                    verifyFolderRecoveryEnvelope(lock, metaSubkey)
                }

                for (m in manifest.mediaItems) {
                    val stagedMedia = stagedMediaFiles[m.id]
                        ?: throw BackupException(BackupError.MISSING_MEDIA, "Missing staged media for ${m.id}")

                    // Verify SUPH ciphertext integrity and plaintext hash
                    val verified = try {
                        vaultCrypto.verifyAndHash(stagedMedia, mediaSubkey, m.id)
                    } catch (e: Exception) {
                        throw BackupException(BackupError.CORRUPT_MEDIA, "Media SUPH verification failed for ${m.id}", e)
                    }

                    if (verified.plaintextSize != m.plaintextSize) {
                        throw BackupException(
                            BackupError.CORRUPT_MEDIA,
                            "Verified plaintextSize ${verified.plaintextSize} != manifest ${m.plaintextSize} for ${m.id}"
                        )
                    }
                    if (!verified.sha256.toHex().equals(m.sha256Hex, ignoreCase = true)) {
                        throw BackupException(
                            BackupError.CORRUPT_MEDIA,
                            "Verified plaintext sha256 mismatch for ${m.id}"
                        )
                    }

                    // Verify encrypted metadata blob
                    val metaBytes = m.encryptedMetadataHex.decodeHex()
                    try {
                        VaultBackupSemanticVerifier.verifyMediaMetadata(m.id, metaBytes, metaSubkey)
                    } catch (e: BackupException) {
                        if (e.error == BackupError.VAULT_INTEGRITY_CHECK_FAILED) {
                            throw BackupException(
                                BackupError.CORRUPT_MEDIA,
                                "Metadata AEAD verification failed for ${m.id}: ${e.message}",
                                cause = e
                            )
                        }
                        throw e
                    } finally {
                        metaBytes.fill(0)
                    }

                    // Verify optional thumbnail crypto (drop if corrupt)
                    val stagedThumb = stagedThumbFiles[m.id]
                    if (stagedThumb != null && stagedThumb.exists()) {
                        if (!DerivativeCryptoVerifier.verifyThumbnailCiphertext(stagedThumb, thumbSubkey, m.id)) {
                            stagedThumb.delete()
                            stagedThumbFiles.remove(m.id)
                            SafeLog.w("VaultBackupImporter", "Dropped corrupt restored thumbnail for ${m.id}")
                        }
                    }

                    // Verify optional preview crypto (drop if corrupt)
                    val stagedPreview = stagedPreviewFiles[m.id]
                    if (stagedPreview != null && stagedPreview.exists()) {
                        if (!DerivativeCryptoVerifier.verifyPreviewCiphertext(stagedPreview, thumbSubkey, m.id)) {
                            stagedPreview.delete()
                            stagedPreviewFiles.remove(m.id)
                            SafeLog.w("VaultBackupImporter", "Dropped corrupt restored preview for ${m.id}")
                        }
                    }
                }
            } finally {
                mediaSubkey.fill(0)
                metaSubkey.fill(0)
                thumbSubkey.fill(0)
            }

            // 8. Wrap master key with the user's NEW credential and recovery code
            val normalizedRecovery = keyManager.normalizeRecoverySecret(recoveryCodeInput)
            val newPinEnvelope = keyManager.createPinEnvelope(masterKey, newCredential)
            val newRecoveryEnvelope = keyManager.createRecoveryEnvelope(masterKey, normalizedRecovery)

            // 9. Commit to database and move files inside Room transaction with crash reconciliation
            var finalVaultDirCreatedByThisRestore = false
            try {
                database.withTransaction {
                    // Insert restored Vault Entity
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

                    // Insert Folder Locks: cross-device restore marks requiresCredentialReset = true, biometric = null
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
                                updatedAt = manifest.createdAt,
                                requiresCredentialReset = true
                            )
                        )
                    }

                    // Insert Media Items and move verified staged files into permanent vault directory
                    for (m in manifest.mediaItems) {
                        finalVaultDirCreatedByThisRestore = true
                        val mediaFile = fileStore.getMediaFile(manifest.vaultId, m.id)
                        val stagedMedia = stagedMediaFiles[m.id]
                        if (stagedMedia != null && stagedMedia.exists()) {
                            mediaFile.parentFile?.mkdirs()
                            if (mediaFile.exists()) mediaFile.delete()
                            check(stagedMedia.renameTo(mediaFile)) { "Failed to commit media file ${m.id}" }
                        }

                        var thumbRelPath: String? = null
                        val stagedThumb = stagedThumbFiles[m.id]
                        if (stagedThumb != null && stagedThumb.exists()) {
                            val thumbFile = fileStore.getThumbFile(manifest.vaultId, m.id)
                            thumbFile.parentFile?.mkdirs()
                            if (thumbFile.exists()) thumbFile.delete()
                            if (stagedThumb.renameTo(thumbFile)) {
                                thumbRelPath = thumbFile.name
                            }
                        }

                        var previewRelPath: String? = null
                        val stagedPreview = stagedPreviewFiles[m.id]
                        if (stagedPreview != null && stagedPreview.exists()) {
                            val previewFile = fileStore.getPreviewFile(manifest.vaultId, m.id)
                            previewFile.parentFile?.mkdirs()
                            if (previewFile.exists()) previewFile.delete()
                            if (stagedPreview.renameTo(previewFile)) {
                                previewRelPath = previewFile.name
                            }
                        }

                        // P0 Privacy: Use archived concealed state only when privacy cannot be derived from folder
                        val safePreviousFolderId =
                            m.previousFolderId?.takeIf {
                                validated.folderById.containsKey(it)
                            }

                        val initialConcealed =
                            when {
                                // Active item: current folder tree is authoritative.
                                m.deletedAt == null ->
                                    false

                                // Trash still points to a restored folder:
                                // recompute from that folder.
                                safePreviousFolderId != null ->
                                    false

                                // Deleted item whose provenance folder no longer exists:
                                // preserve conservative archived privacy.
                                else ->
                                    m.concealed
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
                                previousFolderId = safePreviousFolderId,
                                dateTakenMs = m.dateTakenMs,
                                encryptedPreviewRelativePath = previewRelPath,
                                cleanupStateCode = 0, // P0-A: Never restore active cleanup journal
                                concealed = initialConcealed // P0: Preserve archived privacy for orphaned Trash
                            )
                        )
                    }

                    // Recompute privacy and concealment
                    privacyCoordinator.recomputeInsideTransaction(manifest.vaultId)
                }
            } catch (e: Exception) {
                // Synchronous DB transaction rollback cleanup: delete only permanent files created by this restore
                if (finalVaultDirCreatedByThisRestore) {
                    runCatching {
                        fileStore.vaultDirPath(manifest.vaultId).deleteRecursively()
                    }
                }
                throw e
            }

            // 10. Post-restore security cleanup: lock session, clear folder grants and caches
            sessionManager?.lock(LockReason.Explicit)
            accessManager?.clear()
            fileStore.clearEphemeralPlaintextCaches()

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
        } finally {
            restoreMutex.unlock()
        }
    }
}
