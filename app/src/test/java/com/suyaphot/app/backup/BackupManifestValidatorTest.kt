package com.suyaphot.app.backup

import com.suyaphot.app.domain.backup.BackupError
import com.suyaphot.app.domain.backup.BackupException
import com.suyaphot.app.domain.backup.BackupFileDescriptor
import com.suyaphot.app.domain.backup.BackupFolderEntry
import com.suyaphot.app.domain.backup.BackupFolderLockEntry
import com.suyaphot.app.domain.backup.BackupManifest
import com.suyaphot.app.domain.backup.BackupManifestValidator
import com.suyaphot.app.domain.backup.BackupMediaItemEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class BackupManifestValidatorTest {

    private val validSha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private fun validBaseManifest(): BackupManifest {
        return BackupManifest(
            archiveId = "archive_1",
            version = 2,
            createdAt = 1700000000000L,
            schemaVersion = 5,
            vaultId = "vault_1",
            vaultKindCode = 0, // REAL
            folders = listOf(
                BackupFolderEntry(
                    id = "folder_1",
                    parentId = null,
                    encryptedNameHex = "deadbeef",
                    createdAt = 1000L,
                    updatedAt = 1000L,
                    sortOrder = 0L,
                    directHidden = false,
                    effectiveHidden = false,
                    lockId = "lock_1",
                    effectiveProtected = true
                )
            ),
            folderLocks = listOf(
                BackupFolderLockEntry(
                    id = "lock_1",
                    folderId = "folder_1",
                    credentialTypeCode = 0,
                    credentialEnvelopeHex = "01".repeat(32),
                    recoveryEnvelopeHex = "02".repeat(32)
                )
            ),
            mediaItems = listOf(
                BackupMediaItemEntry(
                    id = "media_1",
                    folderId = "folder_1",
                    mediaTypeCode = 0,
                    encryptedMetadataHex = "03".repeat(32),
                    plaintextSize = 1000L,
                    cipherSize = 1028L,
                    sha256Hex = validSha,
                    importedAt = 1000L,
                    updatedAt = 1000L,
                    favorite = false,
                    deletedAt = null,
                    previousFolderId = null,
                    dateTakenMs = null,
                    cleanupStateCode = 0,
                    concealed = false,
                    hasThumb = true,
                    hasPreview = false
                )
            ),
            descriptors = listOf(
                BackupFileDescriptor(
                    typeCode = 0, // MEDIA
                    itemId = "media_1",
                    cipherLength = 1028L,
                    cipherSha256Hex = validSha
                ),
                BackupFileDescriptor(
                    typeCode = 1, // THUMB
                    itemId = "media_1",
                    cipherLength = 512L,
                    cipherSha256Hex = validSha
                )
            )
        )
    }

    @Test
    fun testValidManifestPasses() {
        val validated = BackupManifestValidator.validateManifest(validBaseManifest(), headerVersion = 2)
        assertNotNull(validated)
        assertEquals(1, validated.mediaById.size)
        assertEquals(1, validated.folderById.size)
        assertEquals(1, validated.lockById.size)
        assertEquals(1028L + 512L, validated.declaredBodyBytes)
    }

    @Test
    fun testHeaderVersionMismatchRejected() {
        try {
            BackupManifestValidator.validateManifest(validBaseManifest(), headerVersion = 1)
            fail("Expected UNSUPPORTED_VERSION")
        } catch (e: BackupException) {
            assertEquals(BackupError.UNSUPPORTED_VERSION, e.error)
        }
    }

    @Test
    fun testUnsupportedVaultKindRejected() {
        val manifest = validBaseManifest().copy(vaultKindCode = 1) // Secondary
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testInvalidShaCharactersRejected() {
        val corruptSha = "z".repeat(64)
        val manifest = validBaseManifest().copy(
            mediaItems = listOf(
                validBaseManifest().mediaItems[0].copy(sha256Hex = corruptSha)
            )
        )
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testDuplicateDescriptorRejected() {
        val manifest = validBaseManifest().copy(
            descriptors = listOf(
                BackupFileDescriptor(typeCode = 0, itemId = "media_1", cipherLength = 100L, cipherSha256Hex = validSha),
                BackupFileDescriptor(typeCode = 0, itemId = "media_1", cipherLength = 100L, cipherSha256Hex = validSha)
            )
        )
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testUnknownDescriptorTypeRejected() {
        val manifest = validBaseManifest().copy(
            descriptors = listOf(
                BackupFileDescriptor(typeCode = 99, itemId = "media_1", cipherLength = 100L, cipherSha256Hex = validSha)
            )
        )
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testDescriptorReferencesUnknownMediaRejected() {
        val manifest = validBaseManifest().copy(
            descriptors = listOf(
                BackupFileDescriptor(typeCode = 0, itemId = "non_existent_media", cipherLength = 100L, cipherSha256Hex = validSha)
            )
        )
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testMissingMediaDescriptorRejected() {
        val manifest = validBaseManifest().copy(
            descriptors = listOf(
                BackupFileDescriptor(typeCode = 1, itemId = "media_1", cipherLength = 100L, cipherSha256Hex = validSha)
            )
        )
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testUnexpectedThumbDescriptorRejected() {
        // hasThumb is false, but THUMB descriptor is present
        val manifest = validBaseManifest().copy(
            mediaItems = listOf(
                validBaseManifest().mediaItems[0].copy(hasThumb = false)
            )
        )
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testFolderCycleRejected() {
        val manifest = validBaseManifest().copy(
            folders = listOf(
                BackupFolderEntry(id = "f1", parentId = "f2", encryptedNameHex = "aabb", createdAt = 0L, updatedAt = 0L, sortOrder = 0L, directHidden = false, effectiveHidden = false, lockId = null, effectiveProtected = false),
                BackupFolderEntry(id = "f2", parentId = "f1", encryptedNameHex = "ccdd", createdAt = 0L, updatedAt = 0L, sortOrder = 0L, directHidden = false, effectiveHidden = false, lockId = null, effectiveProtected = false)
            ),
            folderLocks = emptyList()
        )
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testFolderSelfParentRejected() {
        val manifest = validBaseManifest().copy(
            folders = listOf(
                BackupFolderEntry(id = "f1", parentId = "f1", encryptedNameHex = "aabb", createdAt = 0L, updatedAt = 0L, sortOrder = 0L, directHidden = false, effectiveHidden = false, lockId = null, effectiveProtected = false)
            ),
            folderLocks = emptyList()
        )
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testFolderLockConsistencyBidirectional() {
        // Folder references lock that doesn't exist
        val manifest = validBaseManifest().copy(
            folderLocks = emptyList()
        )
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testCheckedArithmeticOverflowRejected() {
        val manifest = validBaseManifest().copy(
            descriptors = listOf(
                BackupFileDescriptor(typeCode = 0, itemId = "media_1", cipherLength = Long.MAX_VALUE, cipherSha256Hex = validSha),
                BackupFileDescriptor(typeCode = 1, itemId = "media_1", cipherLength = 100L, cipherSha256Hex = validSha)
            )
        )
        try {
            BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
            fail("Expected INVALID_ARCHIVE")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }
}
