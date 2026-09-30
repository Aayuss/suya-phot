package com.suyaphot.app.backup

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.domain.backup.FolderLockCryptoFormat
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.domain.backup.BackupError
import com.suyaphot.app.domain.backup.BackupException
import com.suyaphot.app.domain.backup.VaultBackupSemanticVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class VaultBackupSemanticVerifierTest {

    private val random = SecureRandom()
    private val metaSubkey = ByteArray(32).also { random.nextBytes(it) }

    @Test
    fun testFolderNameVerificationSuccess() {
        val folderId = "folder_abc"
        val folderName = "  Family Vacation  "
        val enc = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = folderName.toByteArray(Charsets.UTF_8),
            aad = folderId.toByteArray(Charsets.UTF_8)
        )

        val normalized = VaultBackupSemanticVerifier.verifyFolderName(folderId, enc, metaSubkey)
        assertEquals("Family Vacation", normalized)
    }

    @Test
    fun testFolderNameCorruptCiphertextThrowsIntegrityError() {
        val folderId = "folder_abc"
        val enc = ByteArray(32) { 0x42.toByte() }

        try {
            VaultBackupSemanticVerifier.verifyFolderName(folderId, enc, metaSubkey)
            fail("Expected VAULT_INTEGRITY_CHECK_FAILED")
        } catch (e: BackupException) {
            assertEquals(BackupError.VAULT_INTEGRITY_CHECK_FAILED, e.error)
        }
    }

    @Test
    fun testFolderNameWrongAadThrowsIntegrityError() {
        val folderId = "folder_abc"
        val enc = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = "Vacation".toByteArray(Charsets.UTF_8),
            aad = "wrong_folder_id".toByteArray(Charsets.UTF_8)
        )

        try {
            VaultBackupSemanticVerifier.verifyFolderName(folderId, enc, metaSubkey)
            fail("Expected VAULT_INTEGRITY_CHECK_FAILED")
        } catch (e: BackupException) {
            assertEquals(BackupError.VAULT_INTEGRITY_CHECK_FAILED, e.error)
        }
    }

    @Test
    fun testFolderNameBlankOrInvalidUtf8ThrowsIntegrityError() {
        val folderId = "folder_abc"
        val blankEnc = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = "   ".toByteArray(Charsets.UTF_8),
            aad = folderId.toByteArray(Charsets.UTF_8)
        )

        try {
            VaultBackupSemanticVerifier.verifyFolderName(folderId, blankEnc, metaSubkey)
            fail("Expected VAULT_INTEGRITY_CHECK_FAILED for blank name")
        } catch (e: BackupException) {
            assertEquals(BackupError.VAULT_INTEGRITY_CHECK_FAILED, e.error)
        }

        // Invalid UTF-8 sequence (0xFF, 0xFE)
        val invalidUtf8 = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val invalidUtf8Enc = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = invalidUtf8,
            aad = folderId.toByteArray(Charsets.UTF_8)
        )

        try {
            VaultBackupSemanticVerifier.verifyFolderName(folderId, invalidUtf8Enc, metaSubkey)
            fail("Expected VAULT_INTEGRITY_CHECK_FAILED for invalid UTF-8")
        } catch (e: BackupException) {
            assertEquals(BackupError.VAULT_INTEGRITY_CHECK_FAILED, e.error)
        }
    }

    @Test
    fun testMediaMetadataVerificationSuccess() {
        val itemId = "item_123"
        val metadata = PrivateMediaMetadata(
            originalDisplayName = "photo.jpg",
            originalRelativePath = "DCIM/Camera",
            originalMimeType = "image/jpeg",
            originalContentUri = null,
            dateTakenMs = 1700000000000L,
            dateModifiedMs = 1700000000000L,
            width = 1920,
            height = 1080,
            durationMs = null,
            orientation = 0,
            sourceVolume = null,
            sourceMediaStoreId = null,
            gpsWasAvailable = false,
            originalFileExtension = "jpg"
        )
        val enc = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = metadata.serialize(),
            aad = itemId.toByteArray(Charsets.UTF_8)
        )

        val deserialized = VaultBackupSemanticVerifier.verifyMediaMetadata(itemId, enc, metaSubkey)
        assertEquals("photo.jpg", deserialized.originalDisplayName)
        assertEquals(1920, deserialized.width)
    }

    @Test
    fun testMediaMetadataCorruptOrWrongAadThrowsIntegrityError() {
        val itemId = "item_123"
        val enc = ByteArray(40) { 0x55.toByte() }

        try {
            VaultBackupSemanticVerifier.verifyMediaMetadata(itemId, enc, metaSubkey)
            fail("Expected VAULT_INTEGRITY_CHECK_FAILED for corrupt metadata")
        } catch (e: BackupException) {
            assertEquals(BackupError.VAULT_INTEGRITY_CHECK_FAILED, e.error)
        }

        // Valid ciphertext with wrong AAD
        val encWrongAad = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = "{}".toByteArray(Charsets.UTF_8),
            aad = "different_item_id".toByteArray(Charsets.UTF_8)
        )

        try {
            VaultBackupSemanticVerifier.verifyMediaMetadata(itemId, encWrongAad, metaSubkey)
            fail("Expected VAULT_INTEGRITY_CHECK_FAILED for wrong AAD")
        } catch (e: BackupException) {
            assertEquals(BackupError.VAULT_INTEGRITY_CHECK_FAILED, e.error)
        }
    }

    @Test
    fun testFolderRecoveryEnvelopeVerification() {
        val lockId = "lock_abc"
        val validToken = ByteArray(32).also { random.nextBytes(it) }
        val enc = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = validToken,
            aad = FolderLockCryptoFormat.recoveryAad(lockId)
        )

        // Valid envelope succeeds
        VaultBackupSemanticVerifier.verifyFolderRecoveryEnvelope(lockId, enc, metaSubkey)

        // Wrong lockId AAD
        try {
            VaultBackupSemanticVerifier.verifyFolderRecoveryEnvelope("wrong_lock", enc, metaSubkey)
            fail("Expected VAULT_INTEGRITY_CHECK_FAILED for wrong lockId AAD")
        } catch (e: BackupException) {
            assertEquals(BackupError.VAULT_INTEGRITY_CHECK_FAILED, e.error)
        }

        // Invalid token size (e.g. 16 bytes instead of 32)
        val shortToken = ByteArray(16).also { random.nextBytes(it) }
        val encShort = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = shortToken,
            aad = FolderLockCryptoFormat.recoveryAad(lockId)
        )

        try {
            VaultBackupSemanticVerifier.verifyFolderRecoveryEnvelope(lockId, encShort, metaSubkey)
            fail("Expected VAULT_INTEGRITY_CHECK_FAILED for short token")
        } catch (e: BackupException) {
            assertEquals(BackupError.VAULT_INTEGRITY_CHECK_FAILED, e.error)
        }
    }
}
