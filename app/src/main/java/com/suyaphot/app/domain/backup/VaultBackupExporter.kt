package com.suyaphot.app.domain.backup

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderLockEntity
import com.suyaphot.app.core.media.DerivativeCryptoVerifier
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class BackupExportResult(
    val archiveId: String,
    val mediaCount: Int,
    val folderCount: Int,
    val totalBytesWritten: Long
)

class VaultBackupExporter(
    private val database: SuyaDatabase,
    private val fileStore: VaultFileStore,
    private val keyManager: KeyManager,
    private val sessionManager: SessionManager,
    private val vaultCrypto: VaultCrypto = VaultCrypto()
) {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyPortableFolderRecovery(
        lock: FolderLockEntity,
        metaSubkey: ByteArray
    ) {
        val encrypted = lock.recoveryEnvelope
            ?: throw BackupException(
                BackupError.FOLDER_LOCK_RECOVERY_NOT_READY,
                "Protected folder lock ${lock.id} lacks portable recovery information."
            )

        val token = try {
            Aead.decryptWithPrependedNonce(
                keyBytes = metaSubkey,
                payload = encrypted,
                aad = FolderLockCryptoFormat.recoveryAad(lock.id)
            )
        } catch (e: Exception) {
            throw BackupException(
                BackupError.FOLDER_LOCK_RECOVERY_CORRUPT,
                "Protected folder lock ${lock.id} has corrupt recovery envelope: ${e.message}",
                cause = e
            )
        }

        try {
            if (token.size != 32) {
                throw BackupException(
                    BackupError.FOLDER_LOCK_RECOVERY_CORRUPT,
                    "Protected folder lock ${lock.id} has invalid recovery token size ${token.size}."
                )
            }
        } finally {
            token.fill(0)
        }
    }

    suspend fun exportVault(
        outputStream: OutputStream,
        recoveryCodeInput: String,
        onProgress: (bytesWritten: Long, totalEstimatedBytes: Long, itemsWritten: Int, totalItems: Int) -> Unit
    ): BackupExportResult = withContext(Dispatchers.IO) {
        val session = sessionManager.sessionState.value as? VaultSession.Unlocked
            ?: throw IllegalStateException("Vault is locked or uninitialized")

        if (session.kind != VaultKind.REAL) {
            throw BackupException(
                BackupError.UNKNOWN,
                "Backup is only supported for the primary vault."
            )
        }

        val vaultId = session.vaultId
        val vaultEntity = database.vaultDao().getVault(vaultId)
            ?: throw IllegalStateException("Vault entity not found")

        // 1. Verify entered Recovery Code format
        if (!keyManager.isValidRecoverySecret(recoveryCodeInput)) {
            throw BackupException(
                BackupError.INVALID_RECOVERY_FORMAT,
                "Recovery code must be exactly 26 Base32 characters."
            )
        }

        // 2. Cryptographically verify entered Recovery Code against current vault's recovery envelope
        val serializedRecovery = vaultEntity.recoveryEnvelope
            ?: throw BackupException(
                BackupError.RECOVERY_NOT_CONFIGURED,
                "Recovery is not configured for this vault."
            )

        val recoveryEnvelope = KeyManager.RecoveryEnvelope.deserialize(serializedRecovery)
        val recoveredMasterKey = keyManager.unwrapRecoveryEnvelope(recoveryEnvelope, recoveryCodeInput)
            ?: throw BackupException(
                BackupError.INCORRECT_RECOVERY_CODE,
                "That Recovery Code does not match this vault."
            )

        try {
            val matches = session.masterKeyHandle.useBytes { current ->
                MessageDigest.isEqual(current, recoveredMasterKey)
            }
            if (!matches) {
                throw BackupException(
                    BackupError.INCORRECT_RECOVERY_CODE,
                    "That Recovery Code does not match this vault."
                )
            }
        } finally {
            recoveredMasterKey.fill(0)
        }

        val folders = database.folderDao().getFoldersForVaultOnce(vaultId)
        val locks = database.folderLockDao().getAllForVault(vaultId)

        // P0-2: Verify every folder lock has portable recovery material
        val missingPortableRecovery = locks.filter { it.recoveryEnvelope == null }
        if (missingPortableRecovery.isNotEmpty()) {
            throw BackupException(
                BackupError.FOLDER_LOCK_RECOVERY_NOT_READY,
                "${missingPortableRecovery.size} protected folder(s) lack portable recovery information. Unlock them once to prepare backup."
            )
        }

        session.masterKeyHandle.useBytes { masterKey ->
            val metaSubkey = vaultCrypto.deriveMetaSubkey(masterKey)
            try {
                for (lock in locks) {
                    verifyPortableFolderRecovery(lock, metaSubkey)
                }
            } finally {
                metaSubkey.fill(0)
            }
        }

        if (folders.size > BackupLimits.MAX_FOLDERS) {
            throw BackupException(BackupError.INVALID_ARCHIVE, "Vault exceeds maximum folder count.")
        }
        if (locks.size > BackupLimits.MAX_LOCKS) {
            throw BackupException(BackupError.INVALID_ARCHIVE, "Vault exceeds maximum lock count.")
        }

        // P0-A: Block export if any trash permanent cleanup is pending
        val pendingCleanup = database.mediaItemDao().countPendingTrashCleanup(vaultId)
        if (pendingCleanup > 0) {
            throw BackupException(
                BackupError.BACKUP_PENDING_LOCAL_CLEANUP,
                "Trash cleanup is still pending. Cannot export backup while permanent deletion is unfinished."
            )
        }

        val mediaItems = database.mediaItemDao().getAllForIntegrityCheck(vaultId)
        if (mediaItems.size > BackupLimits.MAX_MEDIA_ITEMS_V2) {
            throw BackupException(BackupError.INVALID_ARCHIVE, "Vault exceeds maximum media item count.")
        }

        val archiveId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 3. Preflight every required media file before writing any output
        val descriptors = ArrayList<BackupFileDescriptor>()
        val mediaEntries = ArrayList<BackupMediaItemEntry>(mediaItems.size)

        session.masterKeyHandle.useBytes { masterKey ->
            val mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
            val thumbSubkey = vaultCrypto.deriveThumbSubkey(masterKey)
            try {
                for (m in mediaItems) {
                    val mediaFile = fileStore.getMediaFile(vaultId, m.id)
                    if (!mediaFile.exists()) {
                        throw BackupException(
                            BackupError.MISSING_MEDIA,
                            "Missing required media file for item ${m.id}"
                        )
                    }

                    // Verify file can be authenticated and decrypted cleanly
                    val verified = try {
                        vaultCrypto.verifyAndHash(mediaFile, mediaSubkey, m.id)
                    } catch (e: Exception) {
                        throw BackupException(
                            BackupError.CORRUPT_MEDIA,
                            "Encrypted media file is corrupt for item ${m.id}: ${e.message}",
                            e
                        )
                    }

                    if (verified.plaintextSize != m.plaintextSize) {
                        throw BackupException(
                            BackupError.CORRUPT_MEDIA,
                            "Plaintext size mismatch for item ${m.id}: expected ${m.plaintextSize}, got ${verified.plaintextSize}"
                        )
                    }

                    val verifiedHex = verified.sha256.joinToString("") { "%02x".format(it) }
                    if (!verifiedHex.equals(m.sha256Hex, ignoreCase = true)) {
                        throw BackupException(
                            BackupError.CORRUPT_MEDIA,
                            "Plaintext SHA-256 mismatch for item ${m.id}: expected ${m.sha256Hex}, got $verifiedHex"
                        )
                    }

                    if (mediaFile.length() != m.cipherSize) {
                        throw BackupException(
                            BackupError.CORRUPT_MEDIA,
                            "Cipher size mismatch for item ${m.id}: expected ${m.cipherSize}, got ${mediaFile.length()}"
                        )
                    }

                    val mediaCipherSha256 = computeFileSha256(mediaFile)
                    descriptors.add(
                        BackupFileDescriptor(
                            typeCode = BackupArchiveFormat.ENTRY_TYPE_MEDIA,
                            itemId = m.id,
                            cipherLength = mediaFile.length(),
                            cipherSha256Hex = mediaCipherSha256
                        )
                    )

                    var hasThumb = false
                    if (m.encryptedThumbRelativePath != null) {
                        val thumbFile = fileStore.getThumbFile(vaultId, m.id)
                        if (thumbFile.exists() && thumbFile.length() > 0) {
                            if (DerivativeCryptoVerifier.verifyThumbnailCiphertext(thumbFile, thumbSubkey, m.id)) {
                                hasThumb = true
                                descriptors.add(
                                    BackupFileDescriptor(
                                        typeCode = BackupArchiveFormat.ENTRY_TYPE_THUMB,
                                        itemId = m.id,
                                        cipherLength = thumbFile.length(),
                                        cipherSha256Hex = computeFileSha256(thumbFile)
                                    )
                                )
                            } else {
                                SafeLog.w("VaultBackupExporter", "Corrupt thumbnail omitted for item ${m.id}")
                            }
                        }
                    }

                    var hasPreview = false
                    if (m.encryptedPreviewRelativePath != null) {
                        val prevFile = fileStore.getPreviewFile(vaultId, m.id)
                        if (prevFile.exists() && prevFile.length() > 0) {
                            if (DerivativeCryptoVerifier.verifyPreviewCiphertext(prevFile, thumbSubkey, m.id)) {
                                hasPreview = true
                                descriptors.add(
                                    BackupFileDescriptor(
                                        typeCode = BackupArchiveFormat.ENTRY_TYPE_PREVIEW,
                                        itemId = m.id,
                                        cipherLength = prevFile.length(),
                                        cipherSha256Hex = computeFileSha256(prevFile)
                                    )
                                )
                            } else {
                                SafeLog.w("VaultBackupExporter", "Corrupt preview omitted for item ${m.id}")
                            }
                        }
                    }

                    mediaEntries.add(
                        BackupMediaItemEntry(
                            id = m.id,
                            folderId = m.folderId,
                            mediaTypeCode = m.mediaTypeCode,
                            encryptedMetadataHex = m.encryptedMetadata.toHex(),
                            plaintextSize = m.plaintextSize,
                            cipherSize = m.cipherSize,
                            sha256Hex = m.sha256Hex,
                            importedAt = m.importedAt,
                            updatedAt = m.updatedAt,
                            favorite = m.favorite,
                            deletedAt = m.deletedAt,
                            previousFolderId = m.previousFolderId,
                            dateTakenMs = m.dateTakenMs,
                            cleanupStateCode = 0, // P0-A Invariant: never export local runtime cleanup journal
                            concealed = m.concealed,
                            hasThumb = hasThumb,
                            hasPreview = hasPreview
                        )
                    )
                }
            } finally {
                mediaSubkey.fill(0)
                thumbSubkey.fill(0)
            }
        }

        if (descriptors.size > BackupLimits.MAX_DESCRIPTORS_V2) {
            throw BackupException(BackupError.INVALID_ARCHIVE, "Vault exceeds maximum descriptor count.")
        }

        // Build folder and lock entries
        val folderEntries = folders.map { f ->
            BackupFolderEntry(
                id = f.id,
                parentId = f.parentId,
                encryptedNameHex = f.encryptedName.toHex(),
                createdAt = f.createdAt,
                updatedAt = f.updatedAt,
                sortOrder = f.sortOrder,
                directHidden = f.directHidden,
                effectiveHidden = f.effectiveHidden,
                lockId = f.lockId,
                effectiveProtected = f.effectiveProtected
            )
        }

        val lockEntries = locks.map { l ->
            BackupFolderLockEntry(
                id = l.id,
                folderId = l.folderId,
                credentialTypeCode = l.credentialTypeCode,
                credentialEnvelopeHex = l.credentialEnvelope.toHex(),
                recoveryEnvelopeHex = l.recoveryEnvelope?.toHex()
            )
        }

        val manifest = BackupManifest(
            archiveId = archiveId,
            version = BackupArchiveFormat.CURRENT_VERSION,
            createdAt = now,
            schemaVersion = vaultEntity.schemaVersion,
            vaultId = vaultId,
            vaultKindCode = vaultEntity.kindCode,
            folders = folderEntries,
            folderLocks = lockEntries,
            mediaItems = mediaEntries,
            descriptors = descriptors
        )

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val normalized = keyManager.normalizeRecoverySecret(recoveryCodeInput)
        val secretBytes = normalized.toByteArray(Charsets.UTF_8)
        val recoveryKek = ByteArray(32)
        val portableRecoveryEnvelope: ByteArray
        val manifestNonce = Aead.generateNonce()
        val manifestCiphertext: ByteArray
        val manifestKey = ByteArray(32)

        try {
            val derivedKek = HkdfSha256.derive(
                ikm = secretBytes,
                salt = salt,
                info = BackupArchiveFormat.KEK_INFO.toByteArray(Charsets.UTF_8),
                length = 32
            )
            System.arraycopy(derivedKek, 0, recoveryKek, 0, 32)
            derivedKek.fill(0)

            val (envelope, mKey) = session.masterKeyHandle.useBytes { masterKey ->
                val envNonce = Aead.generateNonce()
                val wrappedMasterKey = Aead.encrypt(
                    keyBytes = recoveryKek,
                    nonce = envNonce,
                    aad = BackupArchiveFormat.RECOVERY_AAD.toByteArray(Charsets.UTF_8),
                    plaintext = masterKey
                )
                val env = ByteBuffer.allocate(envNonce.size + wrappedMasterKey.size)
                    .put(envNonce)
                    .put(wrappedMasterKey)
                    .array()

                val derivedManifestKey = HkdfSha256.derive(
                    ikm = masterKey,
                    salt = salt,
                    info = BackupArchiveFormat.MANIFEST_KEY_INFO.toByteArray(Charsets.UTF_8),
                    length = 32
                )
                val key = ByteArray(32)
                System.arraycopy(derivedManifestKey, 0, key, 0, 32)
                derivedManifestKey.fill(0)
                Pair(env, key)
            }
            portableRecoveryEnvelope = envelope
            System.arraycopy(mKey, 0, manifestKey, 0, 32)
            mKey.fill(0)

            val manifestPlain = manifest.toJsonString().toByteArray(Charsets.UTF_8)
            manifestCiphertext = try {
                Aead.encrypt(
                    keyBytes = manifestKey,
                    nonce = manifestNonce,
                    aad = BackupArchiveFormat.MANIFEST_AAD.toByteArray(Charsets.UTF_8),
                    plaintext = manifestPlain
                )
            } finally {
                manifestPlain.fill(0)
            }
        } finally {
            secretBytes.fill(0)
            recoveryKek.fill(0)
            manifestKey.fill(0)
        }

        // Calculate total estimated bytes
        var estimatedTotalBytes = 4L + 4 + 16 + 4 + portableRecoveryEnvelope.size + 4 + 12 + manifestCiphertext.size + 4
        for (desc in descriptors) {
            val idBytes = desc.itemId.toByteArray(Charsets.UTF_8)
            estimatedTotalBytes += 1 + 2 + idBytes.size + 8 + desc.cipherLength + 32
        }

        val bufferedOut = BufferedOutputStream(outputStream, BackupArchiveFormat.BUFFER_SIZE)
        val dos = DataOutputStream(bufferedOut)
        var bytesWritten = 0L
        var itemsWritten = 0

        // Write header
        dos.write(BackupArchiveFormat.MAGIC)
        dos.writeInt(BackupArchiveFormat.CURRENT_VERSION)
        dos.write(salt)
        dos.writeInt(portableRecoveryEnvelope.size)
        dos.write(portableRecoveryEnvelope)
        dos.writeInt(manifestCiphertext.size)
        dos.write(manifestNonce)
        dos.write(manifestCiphertext)
        bytesWritten += 4 + 4 + 16 + 4 + portableRecoveryEnvelope.size + 4 + 12 + manifestCiphertext.size

        onProgress(bytesWritten, estimatedTotalBytes, 0, mediaEntries.size)

        fun writeStreamEntry(type: Byte, id: String, file: File) {
            if (!file.exists()) {
                throw BackupException(
                    BackupError.MISSING_MEDIA,
                    "File disappeared during backup: ${file.name}"
                )
            }
            val idBytes = id.toByteArray(Charsets.UTF_8)
            require(idBytes.size <= Short.MAX_VALUE)

            dos.writeByte(type.toInt())
            dos.writeShort(idBytes.size)
            dos.write(idBytes)
            dos.writeLong(file.length())
            bytesWritten += 1 + 2 + idBytes.size + 8

            val buffer = ByteArray(BackupArchiveFormat.BUFFER_SIZE)
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    dos.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    bytesWritten += read
                    onProgress(bytesWritten, estimatedTotalBytes, itemsWritten, mediaEntries.size)
                }
            }
            val sha256 = digest.digest()
            dos.write(sha256)
            bytesWritten += 32
        }

        // Stream file entries according to descriptors
        for (desc in descriptors) {
            val file = when (desc.typeCode) {
                BackupArchiveFormat.ENTRY_TYPE_MEDIA -> fileStore.getMediaFile(vaultId, desc.itemId)
                BackupArchiveFormat.ENTRY_TYPE_THUMB -> fileStore.getThumbFile(vaultId, desc.itemId)
                BackupArchiveFormat.ENTRY_TYPE_PREVIEW -> fileStore.getPreviewFile(vaultId, desc.itemId)
                else -> throw IllegalStateException("Unknown entry type ${desc.typeCode}")
            }
            writeStreamEntry(desc.typeCode, desc.itemId, file)
            if (desc.typeCode == BackupArchiveFormat.ENTRY_TYPE_MEDIA) {
                itemsWritten++
                onProgress(bytesWritten, estimatedTotalBytes, itemsWritten, mediaEntries.size)
            }
        }

        // Write end marker
        dos.write(BackupArchiveFormat.END_MARKER)
        bytesWritten += 4
        dos.flush()

        SafeLog.i("VaultBackupExporter", "Backup complete. Wrote $bytesWritten bytes for $itemsWritten items")
        BackupExportResult(
            archiveId = archiveId,
            mediaCount = itemsWritten,
            folderCount = folderEntries.size,
            totalBytesWritten = bytesWritten
        )
    }
}
