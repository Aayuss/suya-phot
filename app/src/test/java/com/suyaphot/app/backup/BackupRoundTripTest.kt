package com.suyaphot.app.backup

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.crypto.FakePepperProvider
import com.suyaphot.app.domain.backup.BackupArchiveFormat
import com.suyaphot.app.domain.backup.BackupFolderEntry
import com.suyaphot.app.domain.backup.BackupFolderLockEntry
import com.suyaphot.app.domain.backup.BackupManifest
import com.suyaphot.app.domain.backup.BackupMediaItemEntry
import com.suyaphot.app.domain.backup.BackupVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

class BackupRoundTripTest {

    private val fakePepperProvider = FakePepperProvider()
    private val keyManager = KeyManager(
        context = object : android.content.ContextWrapper(null) {},
        pepperProvider = fakePepperProvider
    )
    private val verifier = BackupVerifier(keyManager)

    @Test
    fun testManifestJsonRoundTrip() {
        val original = BackupManifest(
            archiveId = UUID.randomUUID().toString(),
            version = 1,
            createdAt = 1700000000000L,
            schemaVersion = 4,
            vaultId = "vault_primary",
            vaultKindCode = 0,
            folders = listOf(
                BackupFolderEntry(
                    id = "f1",
                    parentId = null,
                    encryptedNameHex = "deadbeef",
                    createdAt = 1000L,
                    updatedAt = 2000L,
                    sortOrder = 1L,
                    directHidden = false,
                    effectiveHidden = false,
                    lockId = "l1",
                    effectiveProtected = true
                )
            ),
            folderLocks = listOf(
                BackupFolderLockEntry(
                    id = "l1",
                    folderId = "f1",
                    credentialTypeCode = 0,
                    credentialEnvelopeHex = "01020304",
                    recoveryEnvelopeHex = "aabbccdd"
                )
            ),
            mediaItems = listOf(
                BackupMediaItemEntry(
                    id = "m1",
                    folderId = "f1",
                    mediaTypeCode = 0,
                    encryptedMetadataHex = "cafebabe",
                    plaintextSize = 1024L,
                    cipherSize = 1052L,
                    sha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    importedAt = 3000L,
                    updatedAt = 4000L,
                    favorite = true,
                    deletedAt = null,
                    previousFolderId = null,
                    dateTakenMs = 5000L,
                    cleanupStateCode = 0,
                    concealed = true,
                    hasThumb = true,
                    hasPreview = false
                )
            )
        )

        val jsonStr = original.toJsonString()
        val parsed = BackupManifest.fromJsonString(jsonStr)

        assertEquals(original.archiveId, parsed.archiveId)
        assertEquals(original.vaultId, parsed.vaultId)
        assertEquals(original.folders.size, parsed.folders.size)
        assertEquals(original.folders[0].id, parsed.folders[0].id)
        assertEquals(original.folderLocks.size, parsed.folderLocks.size)
        assertEquals(original.folderLocks[0].recoveryEnvelopeHex, parsed.folderLocks[0].recoveryEnvelopeHex)
        assertEquals(original.mediaItems.size, parsed.mediaItems.size)
        assertEquals(original.mediaItems[0].plaintextSize, parsed.mediaItems[0].plaintextSize)
        assertEquals(original.mediaItems[0].hasThumb, parsed.mediaItems[0].hasThumb)
        assertEquals(original.mediaItems[0].hasPreview, parsed.mediaItems[0].hasPreview)
    }

    @Test
    fun testBackupVerifierWithValidArchive() {
        val masterKey = keyManager.generateMasterKey()
        val recoveryCode = keyManager.generateRecoverySecret()
        val normalizedCode = keyManager.normalizeRecoverySecret(recoveryCode)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }

        // Wrap recovery envelope
        val recoveryKek = HkdfSha256.derive(
            ikm = normalizedCode.toByteArray(Charsets.UTF_8),
            salt = salt,
            info = BackupArchiveFormat.KEK_INFO.toByteArray(Charsets.UTF_8),
            length = 32
        )
        val envNonce = Aead.generateNonce()
        val wrappedMasterKey = Aead.encrypt(
            keyBytes = recoveryKek,
            nonce = envNonce,
            aad = BackupArchiveFormat.RECOVERY_AAD.toByteArray(Charsets.UTF_8),
            plaintext = masterKey
        )
        val recoveryEnvelope = ByteBuffer.allocate(envNonce.size + wrappedMasterKey.size)
            .put(envNonce)
            .put(wrappedMasterKey)
            .array()

        // Encrypt manifest
        val manifestKey = HkdfSha256.derive(
            ikm = masterKey,
            salt = salt,
            info = BackupArchiveFormat.MANIFEST_KEY_INFO.toByteArray(Charsets.UTF_8),
            length = 32
        )
        val manifest = BackupManifest(
            archiveId = "arch-123",
            version = 1,
            createdAt = 1700000000000L,
            schemaVersion = 4,
            vaultId = "vault_abc",
            vaultKindCode = 0,
            folders = emptyList(),
            folderLocks = emptyList(),
            mediaItems = listOf(
                BackupMediaItemEntry(
                    id = "item_1",
                    folderId = null,
                    mediaTypeCode = 0,
                    encryptedMetadataHex = "aa",
                    plaintextSize = 2048L,
                    cipherSize = 2076L,
                    sha256Hex = "00".repeat(32),
                    importedAt = 100L,
                    updatedAt = 200L,
                    favorite = false,
                    deletedAt = null,
                    previousFolderId = null,
                    dateTakenMs = null,
                    cleanupStateCode = 0,
                    concealed = false,
                    hasThumb = false,
                    hasPreview = false
                )
            )
        )
        val manifestNonce = Aead.generateNonce()
        val manifestCiphertext = Aead.encrypt(
            keyBytes = manifestKey,
            nonce = manifestNonce,
            aad = BackupArchiveFormat.MANIFEST_AAD.toByteArray(Charsets.UTF_8),
            plaintext = manifest.toJsonString().toByteArray(Charsets.UTF_8)
        )

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.write(BackupArchiveFormat.MAGIC)
        dos.writeInt(BackupArchiveFormat.CURRENT_VERSION)
        dos.write(salt)
        dos.writeInt(recoveryEnvelope.size)
        dos.write(recoveryEnvelope)
        dos.writeInt(manifestCiphertext.size)
        dos.write(manifestNonce)
        dos.write(manifestCiphertext)
        dos.write(BackupArchiveFormat.END_MARKER)
        dos.flush()

        val archiveBytes = baos.toByteArray()

        // 1. Inspect with correct recovery code
        val summary = verifier.verifyAndInspect(ByteArrayInputStream(archiveBytes), recoveryCode)
        assertEquals("arch-123", summary.archiveId)
        assertEquals("vault_abc", summary.vaultId)
        assertEquals(1, summary.mediaCount)
        assertEquals(2048L, summary.totalPlaintextSize)

        // 2. Inspect with wrong recovery code should throw IllegalArgumentException
        val wrongCode = keyManager.generateRecoverySecret()
        try {
            verifier.verifyAndInspect(ByteArrayInputStream(archiveBytes), wrongCode)
            fail("Expected verification to fail with wrong recovery code")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Incorrect Recovery Code"))
        }

        // 3. Inspect corrupted magic should fail
        val corruptedBytes = archiveBytes.copyOf()
        corruptedBytes[0] = 0x00
        try {
            verifier.readHeader(ByteArrayInputStream(corruptedBytes))
            fail("Expected verification to fail with corrupt magic")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Not a valid Suya Phot backup"))
        }
    }
}
