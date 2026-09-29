package com.suyaphot.app.backup

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.domain.backup.BackupError
import com.suyaphot.app.domain.backup.BackupException
import com.suyaphot.app.domain.backup.BackupFolderEntry
import com.suyaphot.app.domain.backup.BackupFolderLockEntry
import com.suyaphot.app.domain.backup.BackupManifest
import com.suyaphot.app.domain.backup.BackupManifestValidator
import com.suyaphot.app.domain.backup.BackupMediaItemEntry
import com.suyaphot.app.domain.backup.FolderLockCryptoFormat
import com.suyaphot.app.domain.folders.FolderManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

class PortableBackupSemanticIntegrityTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray {
        val b = ByteArray(size)
        random.nextBytes(b)
        return b
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun testExportAlwaysNeutralizesCleanupStateCodeToZero() {
        val entry = BackupMediaItemEntry(
            id = "media-1",
            folderId = null,
            mediaTypeCode = 0,
            encryptedMetadataHex = bytesToHex(randomBytes(32)),
            plaintextSize = 1000L,
            cipherSize = 1028L,
            sha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            importedAt = 1000L,
            updatedAt = 1000L,
            favorite = false,
            deletedAt = null,
            previousFolderId = null,
            dateTakenMs = null,
            cleanupStateCode = 0,
            concealed = false,
            hasThumb = false,
            hasPreview = false
        )

        val json = entry.toJson()
        val restored = BackupMediaItemEntry.fromJson(json)
        assertEquals(0, restored.cleanupStateCode)
        assertEquals(false, restored.concealed)
    }

    @Test
    fun testFolderRecoveryEnvelopeAeadValidation() {
        val metaSubkey = randomBytes(32)
        val lockId = "lock_abc123"
        val folderToken = randomBytes(32)

        // Valid envelope
        val envelope = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = folderToken,
            aad = FolderLockCryptoFormat.recoveryAad(lockId)
        )

        val decrypted = Aead.decryptWithPrependedNonce(
            keyBytes = metaSubkey,
            payload = envelope,
            aad = FolderLockCryptoFormat.recoveryAad(lockId)
        )
        assertEquals(32, decrypted.size)
        assertArrayEquals(folderToken, decrypted)

        // Wrong lockId AAD must fail AEAD authentication
        try {
            Aead.decryptWithPrependedNonce(
                keyBytes = metaSubkey,
                payload = envelope,
                aad = FolderLockCryptoFormat.recoveryAad("lock_wrong")
            )
            fail("Decryption with wrong lockId AAD should have failed")
        } catch (_: Exception) {
            // Expected
        }

        // Corrupted envelope bytes must fail AEAD
        val corrupted = envelope.clone()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0x01).toByte()
        try {
            Aead.decryptWithPrependedNonce(
                keyBytes = metaSubkey,
                payload = corrupted,
                aad = FolderLockCryptoFormat.recoveryAad(lockId)
            )
            fail("Decryption of corrupted envelope should have failed")
        } catch (_: Exception) {
            // Expected
        }
    }

    @Test
    fun testFolderNameAuthenticationAndNormalization() {
        val metaSubkey = randomBytes(32)
        val folderId = "folder_xyz"
        val rawName = "  Vacation Photos  "
        val normalizedName = FolderManager.normalizeFolderName(rawName)
        assertEquals("Vacation Photos", normalizedName)

        val nameCiphertext = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = normalizedName.toByteArray(Charsets.UTF_8),
            aad = folderId.toByteArray(Charsets.UTF_8)
        )

        // Decrypt under metaSubkey and folder.id AAD
        val decryptedBytes = Aead.decryptWithPrependedNonce(
            keyBytes = metaSubkey,
            payload = nameCiphertext,
            aad = folderId.toByteArray(Charsets.UTF_8)
        )
        val decryptedName = String(decryptedBytes, Charsets.UTF_8)
        assertEquals(normalizedName, FolderManager.normalizeFolderName(decryptedName))

        // Wrong folder ID AAD must fail
        try {
            Aead.decryptWithPrependedNonce(
                keyBytes = metaSubkey,
                payload = nameCiphertext,
                aad = "other_folder".toByteArray(Charsets.UTF_8)
            )
            fail("Decryption with wrong folder ID AAD must fail")
        } catch (_: Exception) {
            // Expected
        }
    }

    @Test
    fun testLegacyV1ProtectedFolderWithoutRecoveryEnvelopeIsRejected() {
        val baseManifest = BackupManifest(
            archiveId = UUID.randomUUID().toString(),
            version = 1, // Legacy V1
            createdAt = 1700000000000L,
            schemaVersion = 3,
            vaultId = "vault_legacy",
            vaultKindCode = 0,
            folders = listOf(
                BackupFolderEntry(
                    id = "folder_prot",
                    parentId = null,
                    encryptedNameHex = "deadbeef",
                    createdAt = 1000L,
                    updatedAt = 1000L,
                    sortOrder = 0L,
                    directHidden = false,
                    effectiveHidden = false,
                    lockId = "lock_prot",
                    effectiveProtected = true
                )
            ),
            folderLocks = listOf(
                BackupFolderLockEntry(
                    id = "lock_prot",
                    folderId = "folder_prot",
                    credentialTypeCode = 0,
                    credentialEnvelopeHex = "01".repeat(32),
                    recoveryEnvelopeHex = null // Missing portable recovery envelope in V1!
                )
            ),
            mediaItems = emptyList(),
            descriptors = emptyList()
        )

        try {
            BackupManifestValidator.validate(1, baseManifest)
            fail("Should have rejected legacy V1 protected folder without recovery envelope")
        } catch (e: BackupException) {
            assertEquals(BackupError.LEGACY_PROTECTED_FOLDER_NOT_PORTABLE, e.error)
        }
    }
}
