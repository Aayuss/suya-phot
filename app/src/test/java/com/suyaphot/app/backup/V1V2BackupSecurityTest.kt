package com.suyaphot.app.backup

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.HkdfSha256
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.crypto.FakePepperProvider
import com.suyaphot.app.domain.backup.BackupArchiveFormat
import com.suyaphot.app.domain.backup.BackupError
import com.suyaphot.app.domain.backup.BackupException
import com.suyaphot.app.domain.backup.BackupLimits
import com.suyaphot.app.domain.backup.BackupManifest
import com.suyaphot.app.domain.backup.BackupMediaItemEntry
import com.suyaphot.app.domain.backup.BackupVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

class V1V2BackupSecurityTest {

    private val fakePepperProvider = FakePepperProvider()
    private val keyManager = KeyManager(
        context = object : android.content.ContextWrapper(null) {},
        pepperProvider = fakePepperProvider
    )
    private val verifier = BackupVerifier(keyManager)
    private val random = SecureRandom()

    private fun buildArchive(
        version: Int,
        mediaItems: List<BackupMediaItemEntry>,
        bodyMutator: ((DataOutputStream) -> Unit)? = null,
        appendTrailing: Boolean = false
    ): Pair<ByteArray, String> {
        val masterKey = keyManager.generateMasterKey()
        val recoveryCode = keyManager.generateRecoverySecret()
        val normalizedCode = keyManager.normalizeRecoverySecret(recoveryCode)
        val salt = ByteArray(16).also { random.nextBytes(it) }

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

        val manifestKey = HkdfSha256.derive(
            ikm = masterKey,
            salt = salt,
            info = BackupArchiveFormat.MANIFEST_KEY_INFO.toByteArray(Charsets.UTF_8),
            length = 32
        )

        val manifest = BackupManifest(
            archiveId = "arch-sec-test",
            version = version,
            createdAt = 1700000000000L,
            schemaVersion = 4,
            vaultId = "vault_sec",
            vaultKindCode = 0,
            folders = emptyList(),
            folderLocks = emptyList(),
            mediaItems = mediaItems,
            descriptors = emptyList()
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
        dos.writeInt(version)
        dos.write(salt)
        dos.writeInt(recoveryEnvelope.size)
        dos.write(recoveryEnvelope)
        dos.writeInt(manifestCiphertext.size)
        dos.write(manifestNonce)
        dos.write(manifestCiphertext)

        if (bodyMutator != null) {
            bodyMutator(dos)
        } else {
            // Default: write valid V1 bodies for each media item
            for (item in mediaItems) {
                dos.writeByte(BackupArchiveFormat.ENTRY_TYPE_MEDIA.toInt())
                val idBytes = item.id.toByteArray(Charsets.UTF_8)
                dos.writeShort(idBytes.size)
                dos.write(idBytes)
                dos.writeLong(item.cipherSize)
                val body = ByteArray(item.cipherSize.toInt())
                dos.write(body)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
                dos.write(digest)
            }
            dos.write(BackupArchiveFormat.END_MARKER)
        }

        if (appendTrailing) {
            dos.write(byteArrayOf(0x00, 0x11, 0x22, 0x33))
        }

        dos.flush()
        return Pair(baos.toByteArray(), recoveryCode)
    }

    private fun sampleMediaItem(id: String, cipherSize: Long): BackupMediaItemEntry {
        return BackupMediaItemEntry(
            id = id,
            folderId = null,
            mediaTypeCode = 0,
            encryptedMetadataHex = "00".repeat(32),
            plaintextSize = cipherSize - 28,
            cipherSize = cipherSize,
            sha256Hex = "00".repeat(32),
            importedAt = 100L,
            updatedAt = 200L,
            favorite = false,
            deletedAt = null,
            previousFolderId = null,
            dateTakenMs = null,
            cleanupStateCode = 0,
            concealed = false,
            hasThumb = true,
            hasPreview = true
        )
    }

    @Test
    fun testRejectsUnknownEntryTypeMarkerInV1Archive() {
        val (archive, code) = buildArchive(
            version = 1,
            mediaItems = listOf(sampleMediaItem("item1", 100L)),
            bodyMutator = { dos ->
                dos.writeByte(0x7F) // Unknown entry type
                val idBytes = "item1".toByteArray(Charsets.UTF_8)
                dos.writeShort(idBytes.size)
                dos.write(idBytes)
                dos.writeLong(100L)
                dos.write(ByteArray(100))
                dos.write(BackupArchiveFormat.END_MARKER)
            }
        )

        try {
            ByteArrayInputStream(archive).use { verifier.verifyFullArchive(it, code) }
            fail("Should have rejected unknown entry type")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testRejectsUnknownItemIdInV1Body() {
        val (archive, code) = buildArchive(
            version = 1,
            mediaItems = listOf(sampleMediaItem("item1", 100L)),
            bodyMutator = { dos ->
                dos.writeByte(BackupArchiveFormat.ENTRY_TYPE_MEDIA.toInt())
                val idBytes = "unknown_item_99".toByteArray(Charsets.UTF_8)
                dos.writeShort(idBytes.size)
                dos.write(idBytes)
                dos.writeLong(100L)
                dos.write(ByteArray(100))
                dos.write(BackupArchiveFormat.END_MARKER)
            }
        )

        try {
            ByteArrayInputStream(archive).use { verifier.verifyFullArchive(it, code) }
            fail("Should have rejected undeclared item ID")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testRejectsOversizedThumbnailEntry() {
        val (archive, code) = buildArchive(
            version = 1,
            mediaItems = listOf(sampleMediaItem("item1", 100L)),
            bodyMutator = { dos ->
                dos.writeByte(BackupArchiveFormat.ENTRY_TYPE_THUMB.toInt())
                val idBytes = "item1".toByteArray(Charsets.UTF_8)
                dos.writeShort(idBytes.size)
                dos.write(idBytes)
                // Exceed BackupLimits.MAX_V1_THUMB_BYTES
                val oversized = BackupLimits.MAX_V1_THUMB_BYTES + 1024L
                dos.writeLong(oversized)
                dos.write(ByteArray(100))
            }
        )

        try {
            ByteArrayInputStream(archive).use { verifier.verifyFullArchive(it, code) }
            fail("Should have rejected oversized thumbnail entry before body read")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testRejectsOversizedPreviewEntry() {
        val (archive, code) = buildArchive(
            version = 1,
            mediaItems = listOf(sampleMediaItem("item1", 100L)),
            bodyMutator = { dos ->
                dos.writeByte(BackupArchiveFormat.ENTRY_TYPE_PREVIEW.toInt())
                val idBytes = "item1".toByteArray(Charsets.UTF_8)
                dos.writeShort(idBytes.size)
                dos.write(idBytes)
                // Exceed BackupLimits.MAX_V1_PREVIEW_BYTES
                val oversized = BackupLimits.MAX_V1_PREVIEW_BYTES + 1024L
                dos.writeLong(oversized)
                dos.write(ByteArray(100))
            }
        )

        try {
            ByteArrayInputStream(archive).use { verifier.verifyFullArchive(it, code) }
            fail("Should have rejected oversized preview entry before body read")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }

    @Test
    fun testRejectsCipherSizeMismatchInV1Body() {
        val (archive, code) = buildArchive(
            version = 1,
            mediaItems = listOf(sampleMediaItem("item1", 200L)),
            bodyMutator = { dos ->
                dos.writeByte(BackupArchiveFormat.ENTRY_TYPE_MEDIA.toInt())
                val idBytes = "item1".toByteArray(Charsets.UTF_8)
                dos.writeShort(idBytes.size)
                dos.write(idBytes)
                dos.writeLong(150L) // declared 200 in manifest, but 150 here!
                dos.write(ByteArray(150))
                dos.write(BackupArchiveFormat.END_MARKER)
            }
        )

        try {
            ByteArrayInputStream(archive).use { verifier.verifyFullArchive(it, code) }
            fail("Should have rejected cipherSize mismatch")
        } catch (e: BackupException) {
            assertEquals(BackupError.CORRUPT_MEDIA, e.error)
        }
    }

    @Test
    fun testRejectsIncompleteV1Archive() {
        val (archive, code) = buildArchive(
            version = 1,
            mediaItems = listOf(sampleMediaItem("item1", 100L), sampleMediaItem("item2", 100L)),
            bodyMutator = { dos ->
                // Only provide item1 body, then END_MARKER
                dos.writeByte(BackupArchiveFormat.ENTRY_TYPE_MEDIA.toInt())
                val idBytes = "item1".toByteArray(Charsets.UTF_8)
                dos.writeShort(idBytes.size)
                dos.write(idBytes)
                dos.writeLong(100L)
                val body = ByteArray(100)
                dos.write(body)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body)
                dos.write(digest)
                dos.write(BackupArchiveFormat.END_MARKER)
            }
        )

        try {
            ByteArrayInputStream(archive).use { verifier.verifyFullArchive(it, code) }
            fail("Should have rejected incomplete archive missing item2")
        } catch (e: BackupException) {
            assertEquals(BackupError.MISSING_MEDIA, e.error)
        }
    }

    @Test
    fun testRejectsTrailingGarbageBytesAfterEndMarker() {
        val (archive, code) = buildArchive(
            version = 1,
            mediaItems = listOf(sampleMediaItem("item1", 100L)),
            appendTrailing = true
        )

        try {
            ByteArrayInputStream(archive).use { verifier.verifyFullArchive(it, code) }
            fail("Should have rejected archive with trailing bytes")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }
}
