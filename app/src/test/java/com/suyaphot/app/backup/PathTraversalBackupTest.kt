package com.suyaphot.app.backup

import com.suyaphot.app.core.util.InternalId
import com.suyaphot.app.domain.backup.BackupError
import com.suyaphot.app.domain.backup.BackupException
import com.suyaphot.app.domain.backup.BackupFileDescriptor
import com.suyaphot.app.domain.backup.BackupFolderEntry
import com.suyaphot.app.domain.backup.BackupManifest
import com.suyaphot.app.domain.backup.BackupManifestValidator
import com.suyaphot.app.domain.backup.BackupMediaItemEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID

class PathTraversalBackupTest {

    private val validMediaSha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private fun createValidManifest(
        archiveId: String = "valid-archive-id",
        mediaId: String = "valid-media-id"
    ): BackupManifest {
        return BackupManifest(
            archiveId = archiveId,
            version = 2,
            createdAt = 1700000000000L,
            schemaVersion = 5,
            vaultId = "valid-vault-id",
            vaultKindCode = 0,
            folders = emptyList(),
            folderLocks = emptyList(),
            mediaItems = listOf(
                BackupMediaItemEntry(
                    id = mediaId,
                    folderId = null,
                    mediaTypeCode = 0,
                    encryptedMetadataHex = "01".repeat(32),
                    plaintextSize = 100L,
                    cipherSize = 128L,
                    sha256Hex = validMediaSha,
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
            ),
            descriptors = listOf(
                BackupFileDescriptor(
                    typeCode = 0, // MEDIA
                    itemId = mediaId,
                    cipherLength = 128L,
                    cipherSha256Hex = validMediaSha
                )
            )
        )
    }

    private val maliciousIdPayloads = listOf(
        "../x",
        "../../x",
        "/../../x",
        "a/b",
        "a\\b",
        ".",
        "..",
        "a".repeat(129), // >128 chars
        "evil\nid", // newline
        "evil\u0000id", // null byte
        "evil\r\nid",
        "evil\u2024id", // one-dot leader
        "evil\u2025id", // two-dot leader
        "evil\uFF0Fid"  // fullwidth solidus
    )

    @Test
    fun testInternalIdRejectsMaliciousStrings() {
        for (payload in maliciousIdPayloads) {
            try {
                InternalId.requireValid(payload, "test id")
                fail("Expected InternalId.requireValid to reject: $payload")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun testManifestValidatorRejectsMaliciousArchiveId() {
        for (payload in maliciousIdPayloads) {
            val manifest = createValidManifest(archiveId = payload)
            try {
                BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
                fail("Expected validator to reject archiveId: $payload")
            } catch (e: BackupException) {
                assertEquals(BackupError.INVALID_ARCHIVE, e.error)
            }
        }
    }

    @Test
    fun testManifestValidatorRejectsMaliciousMediaId() {
        for (payload in maliciousIdPayloads) {
            val manifest = createValidManifest(mediaId = payload)
            try {
                BackupManifestValidator.validateManifest(manifest, headerVersion = 2)
                fail("Expected validator to reject mediaId: $payload")
            } catch (e: BackupException) {
                assertEquals(BackupError.INVALID_ARCHIVE, e.error)
            }
        }
    }

    @Test
    fun testSentinelFileSafetyOnMaliciousPath() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "sentinel_test_${UUID.randomUUID()}")
        tempDir.mkdirs()
        try {
            val sentinelFile = File(tempDir, "sentinel.txt")
            sentinelFile.writeText("CRITICAL_UNTOUCHED_CONTENT")

            val maliciousManifest = createValidManifest(
                archiveId = "../../sentinel",
                mediaId = "../x"
            )

            try {
                BackupManifestValidator.validateManifest(maliciousManifest, headerVersion = 2)
                fail("Should have failed")
            } catch (e: BackupException) {
                assertEquals(BackupError.INVALID_ARCHIVE, e.error)
            }

            assertTrue("Sentinel must exist", sentinelFile.exists())
            assertEquals("CRITICAL_UNTOUCHED_CONTENT", sentinelFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
