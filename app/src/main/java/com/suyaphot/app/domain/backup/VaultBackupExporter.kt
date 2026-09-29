package com.suyaphot.app.domain.backup

import android.content.Context
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.database.SuyaDatabase
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
    private val sessionManager: SessionManager
) {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    suspend fun exportVault(
        outputStream: OutputStream,
        recoveryCodeInput: String,
        onProgress: (bytesWritten: Long, totalEstimatedBytes: Long, itemsWritten: Int, totalItems: Int) -> Unit
    ): BackupExportResult = withContext(Dispatchers.IO) {
        val session = sessionManager.sessionState.value as? VaultSession.Unlocked
            ?: throw IllegalStateException("Vault is locked or uninitialized")

        val vaultId = session.vaultId
        val vaultEntity = database.vaultDao().getVault(vaultId)
            ?: throw IllegalStateException("Vault entity not found")

        val folders = database.folderDao().getFoldersForVaultOnce(vaultId)
        val locks = database.folderLockDao().getAllForVault(vaultId)
        val mediaItems = database.mediaItemDao().getAllForIntegrityCheck(vaultId)

        val archiveId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Build manifest entries
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

        val mediaEntries = mediaItems.map { m ->
            val hasThumb = m.encryptedThumbRelativePath != null && fileStore.getThumbFile(vaultId, m.id).exists()
            val hasPreview = m.encryptedPreviewRelativePath != null && fileStore.getPreviewFile(vaultId, m.id).exists()
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
                cleanupStateCode = m.cleanupStateCode,
                concealed = m.concealed,
                hasThumb = hasThumb,
                hasPreview = hasPreview
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
            mediaItems = mediaEntries
        )

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val normalized = keyManager.normalizeRecoverySecret(recoveryCodeInput)
        val secretBytes = normalized.toByteArray(Charsets.UTF_8)
        val recoveryKek = ByteArray(32)
        val recoveryEnvelope: ByteArray
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
            recoveryEnvelope = envelope
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
        var estimatedTotalBytes = 4L + 4 + 16 + 4 + recoveryEnvelope.size + 4 + 12 + manifestCiphertext.size + 4
        for (m in mediaEntries) {
            val mediaFile = fileStore.getMediaFile(vaultId, m.id)
            if (mediaFile.exists()) estimatedTotalBytes += 1 + 2 + m.id.toByteArray(Charsets.UTF_8).size + 8 + mediaFile.length() + 32
            if (m.hasThumb) {
                val thumbFile = fileStore.getThumbFile(vaultId, m.id)
                if (thumbFile.exists()) estimatedTotalBytes += 1 + 2 + m.id.toByteArray(Charsets.UTF_8).size + 8 + thumbFile.length() + 32
            }
            if (m.hasPreview) {
                val prevFile = fileStore.getPreviewFile(vaultId, m.id)
                if (prevFile.exists()) estimatedTotalBytes += 1 + 2 + m.id.toByteArray(Charsets.UTF_8).size + 8 + prevFile.length() + 32
            }
        }

        val bufferedOut = BufferedOutputStream(outputStream, BackupArchiveFormat.BUFFER_SIZE)
        val dos = DataOutputStream(bufferedOut)
        var bytesWritten = 0L
        var itemsWritten = 0

        // Write header
        dos.write(BackupArchiveFormat.MAGIC)
        dos.writeInt(BackupArchiveFormat.CURRENT_VERSION)
        dos.write(salt)
        dos.writeInt(recoveryEnvelope.size)
        dos.write(recoveryEnvelope)
        dos.writeInt(manifestCiphertext.size)
        dos.write(manifestNonce)
        dos.write(manifestCiphertext)
        bytesWritten += 4 + 4 + 16 + 4 + recoveryEnvelope.size + 4 + 12 + manifestCiphertext.size

        onProgress(bytesWritten, estimatedTotalBytes, 0, mediaEntries.size)

        fun writeStreamEntry(type: Byte, id: String, file: File) {
            if (!file.exists()) return
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

        // Stream file entries
        for (m in mediaEntries) {
            val mediaFile = fileStore.getMediaFile(vaultId, m.id)
            writeStreamEntry(BackupArchiveFormat.ENTRY_TYPE_MEDIA, m.id, mediaFile)

            if (m.hasThumb) {
                val thumbFile = fileStore.getThumbFile(vaultId, m.id)
                writeStreamEntry(BackupArchiveFormat.ENTRY_TYPE_THUMB, m.id, thumbFile)
            }

            if (m.hasPreview) {
                val prevFile = fileStore.getPreviewFile(vaultId, m.id)
                writeStreamEntry(BackupArchiveFormat.ENTRY_TYPE_PREVIEW, m.id, prevFile)
            }

            itemsWritten++
            onProgress(bytesWritten, estimatedTotalBytes, itemsWritten, mediaEntries.size)
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
