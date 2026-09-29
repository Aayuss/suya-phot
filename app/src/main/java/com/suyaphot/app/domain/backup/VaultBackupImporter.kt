package com.suyaphot.app.domain.backup

import android.content.Context
import androidx.room.withTransaction
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.FolderLockEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.LockReason
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

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

        // 3. Validate untrusted manifest structure
        validateManifestStructure(manifest)

        val stagingDir = File(context.noBackupFilesDir, "staging_${manifest.archiveId}").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val descriptorMap = manifest.descriptors.associateBy { Pair(it.typeCode, it.itemId) }
        val seenEntries = HashSet<Pair<Byte, String>>()
        val stagedMediaFiles = HashMap<String, File>()
        val stagedThumbFiles = HashMap<String, File>()
        val stagedPreviewFiles = HashMap<String, File>()

        val buffer = ByteArray(BackupArchiveFormat.BUFFER_SIZE)
        var reachedEnd = false
        var bytesRead = 0L
        val totalExpectedItems = manifest.mediaItems.size
        var totalEstimatedBytes = manifest.mediaItems.sumOf { it.cipherSize + 32 }
        var itemsRead = 0

        try {
            // 4. Stream entries and stage to temporary files
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
                if (entryLength < 0 || entryLength > 100_000_000_000L) {
                    throw BackupException(BackupError.INVALID_ARCHIVE, "Invalid entry body length: $entryLength")
                }
                bytesRead += 1 + 2 + idLength + 8

                val key = Pair(entryType, entryId)
                if (!seenEntries.add(key)) {
                    throw BackupException(BackupError.INVALID_ARCHIVE, "Duplicate entry in backup: type=$entryType, id=$entryId")
                }

                val expectedDesc = descriptorMap[key]
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
                }

                val stagePrefix = when (entryType) {
                    BackupArchiveFormat.ENTRY_TYPE_MEDIA -> "media_"
                    BackupArchiveFormat.ENTRY_TYPE_THUMB -> "thumb_"
                    BackupArchiveFormat.ENTRY_TYPE_PREVIEW -> "preview_"
                    else -> throw BackupException(BackupError.INVALID_ARCHIVE, "Unknown entry type $entryType")
                }
                val stagedFile = File(stagingDir, "${stagePrefix}$entryId.bin")
                val digest = MessageDigest.getInstance("SHA-256")

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

            // Check for unexpected trailing bytes
            if (dis.read() != -1) {
                throw BackupException(BackupError.INVALID_ARCHIVE, "Unexpected trailing bytes after archive end marker")
            }

            // Verify that all required media items have bodies
            for (m in manifest.mediaItems) {
                if (!stagedMediaFiles.containsKey(m.id)) {
                    throw BackupException(BackupError.MISSING_MEDIA, "Missing required media body for item ${m.id}")
                }
            }

            // 5. Derive keys and perform full cryptographic verification of all staged media and metadata
            val mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
            val metaSubkey = vaultCrypto.deriveMetaSubkey(masterKey)

            try {
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
                        val decryptedMeta = Aead.decryptWithPrependedNonce(
                            keyBytes = metaSubkey,
                            payload = metaBytes,
                            aad = m.id.toByteArray(Charsets.UTF_8)
                        )
                        try {
                            PrivateMediaMetadata.deserialize(decryptedMeta)
                        } finally {
                            decryptedMeta.fill(0.toByte())
                        }
                    } catch (e: Exception) {
                        throw BackupException(BackupError.CORRUPT_MEDIA, "Metadata AEAD verification failed for ${m.id}", e)
                    }
                }
            } finally {
                mediaSubkey.fill(0)
                metaSubkey.fill(0)
            }

            // 6. Wrap master key with the user's NEW credential and recovery code
            val normalizedRecovery = keyManager.normalizeRecoverySecret(recoveryCodeInput)
            val newPinEnvelope = keyManager.createPinEnvelope(masterKey, newCredential)
            val newRecoveryEnvelope = keyManager.createRecoveryEnvelope(masterKey, normalizedRecovery)

            // 7. Commit to database and move files inside Room transaction
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

            // 8. Post-restore security cleanup: lock session, clear folder grants and caches
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
    }

    private fun validateManifestStructure(manifest: BackupManifest) {
        val folderIds = HashSet<String>()
        val lockIds = HashSet<String>()
        val mediaIds = HashSet<String>()

        for (f in manifest.folders) {
            require(f.id.isNotBlank()) { "Empty folder ID in manifest" }
            require(folderIds.add(f.id)) { "Duplicate folder ID: ${f.id}" }
            if (f.parentId != null) {
                require(f.parentId != f.id) { "Folder cannot be its own parent: ${f.id}" }
            }
        }

        // Check for folder cycles and depth
        for (f in manifest.folders) {
            var curr = f.parentId
            var depth = 0
            while (curr != null) {
                require(depth < 20) { "Folder hierarchy too deep or cycle detected at ${f.id}" }
                require(curr != f.id) { "Folder cycle detected at ${f.id}" }
                val parent = manifest.folders.find { it.id == curr }
                require(parent != null) { "Missing parent folder $curr for ${f.id}" }
                curr = parent.parentId
                depth++
            }
        }

        for (l in manifest.folderLocks) {
            require(l.id.isNotBlank()) { "Empty lock ID in manifest" }
            require(lockIds.add(l.id)) { "Duplicate lock ID: ${l.id}" }
            require(folderIds.contains(l.folderId)) { "Folder lock references non-existent folder: ${l.folderId}" }
        }

        for (m in manifest.mediaItems) {
            require(m.id.isNotBlank()) { "Empty media ID in manifest" }
            require(mediaIds.add(m.id)) { "Duplicate media ID: ${m.id}" }
            if (m.folderId != null) {
                require(folderIds.contains(m.folderId)) { "Media references non-existent folder: ${m.folderId}" }
            }
            require(m.plaintextSize >= 0) { "Negative plaintextSize for ${m.id}" }
            require(m.cipherSize >= 0) { "Negative cipherSize for ${m.id}" }
            require(m.sha256Hex.length == 64) { "Invalid SHA256 hex length for ${m.id}" }
        }
    }
}
