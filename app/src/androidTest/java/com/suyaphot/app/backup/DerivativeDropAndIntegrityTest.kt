package com.suyaphot.app.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.crypto.PepperProvider
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.FolderLockEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.backup.BackupError
import com.suyaphot.app.domain.backup.BackupException
import com.suyaphot.app.domain.backup.BackupVerifier
import com.suyaphot.app.domain.backup.FolderLockCryptoFormat
import com.suyaphot.app.domain.backup.VaultBackupExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DerivativeDropAndIntegrityTest {

    private val pepper = object : PepperProvider {
        override fun hmacSha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
    }

    private lateinit var db: SuyaDatabase
    private lateinit var fileStore: VaultFileStore
    private lateinit var keyManager: KeyManager
    private lateinit var sessionManager: SessionManager
    private lateinit var vaultCrypto: VaultCrypto
    private lateinit var exporter: VaultBackupExporter
    private lateinit var verifier: BackupVerifier

    private val vaultId = "test_vault_integrity"
    private var recoveryCode = ""
    private lateinit var masterKey: ByteArray
    private lateinit var mediaSubkey: ByteArray
    private lateinit var metaSubkey: ByteArray
    private lateinit var thumbSubkey: ByteArray

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        fileStore = VaultFileStore(context)
        keyManager = KeyManager(context, pepper)
        val securityPrefs = SecurityPreferences(context)
        sessionManager = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))
        vaultCrypto = VaultCrypto()
        exporter = VaultBackupExporter(db, fileStore, keyManager, sessionManager, vaultCrypto)
        verifier = BackupVerifier(keyManager)

        masterKey = keyManager.generateMasterKey()
        recoveryCode = keyManager.generateRecoverySecret()
        val normalizedRecovery = keyManager.normalizeRecoverySecret(recoveryCode)
        val recoveryEnvelope = keyManager.createRecoveryEnvelope(masterKey, normalizedRecovery)

        db.vaultDao().insert(
            VaultEntity(
                id = vaultId,
                kindCode = VaultKind.REAL.code,
                createdAt = System.currentTimeMillis(),
                schemaVersion = 6,
                pinEnvelope = ByteArray(16),
                recoveryEnvelope = recoveryEnvelope.serialize(),
                biometricEnvelope = null,
                biometricIv = null,
                credentialTypeCode = 0
            )
        )

        mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
        metaSubkey = vaultCrypto.deriveMetaSubkey(masterKey)
        thumbSubkey = vaultCrypto.deriveThumbSubkey(masterKey)

        sessionManager.unlock(
            vaultId = vaultId,
            kind = VaultKind.REAL,
            masterKeyHandle = SensitiveKeyHandle(masterKey.copyOf()),
            mediaSubkey = mediaSubkey,
            metaSubkey = metaSubkey,
            thumbSubkey = thumbSubkey
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertValidMedia(mediaId: String = UUID.randomUUID().toString()): Triple<String, Long, String> {
        val mediaPlaintext = "Sample Plaintext Image Data 12345".toByteArray()
        val mediaFile = fileStore.getMediaFile(vaultId, mediaId)
        val result = vaultCrypto.encryptStream(
            input = ByteArrayInputStream(mediaPlaintext),
            outputFile = mediaFile,
            mediaSubkey = mediaSubkey,
            itemId = mediaId,
            isVideo = false,
            plaintextSize = mediaPlaintext.size.toLong()
        )
        val sha256Hex = result.sha256.joinToString("") { "%02x".format(it) }

        val encMetadata = Aead.encryptWithPrependedNonce(
            keyBytes = metaSubkey,
            plaintext = "fake metadata".toByteArray(),
            aad = mediaId.toByteArray(Charsets.UTF_8)
        )

        val prefix = if (mediaId.length >= 2) mediaId.substring(0, 2) else "xx"
        val mediaEntity = MediaItemEntity(
            id = mediaId,
            vaultId = vaultId,
            folderId = null,
            mediaTypeCode = 0,
            encryptedMetadata = encMetadata,
            encryptedFileRelativePath = "media/$prefix/$mediaId.sph",
            encryptedThumbRelativePath = null,
            plaintextSize = mediaPlaintext.size.toLong(),
            cipherSize = mediaFile.length(),
            sha256Hex = sha256Hex,
            importedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            favorite = false,
            encryptedPreviewRelativePath = null,
            deletedAt = null,
            previousFolderId = null,
            dateTakenMs = null,
            cleanupStateCode = 0,
            concealed = false
        )
        runBlocking { db.mediaItemDao().insert(mediaEntity) }
        return Triple(mediaId, mediaFile.length(), sha256Hex)
    }

    @Test
    fun testExportFailsWhenDbSha256HexWrong(): Unit = runBlocking {
        val (mediaId, _, _) = insertValidMedia()
        // Corrupt sha256Hex in DB
        db.openHelper.writableDatabase.execSQL(
            "UPDATE media_items SET sha256Hex = ? WHERE id = ?",
            arrayOf("00".repeat(32), mediaId)
        )

        try {
            exporter.exportVault(ByteArrayOutputStream(), recoveryCode, { _, _, _, _ -> })
            fail("Expected export to fail on wrong SHA-256")
        } catch (e: BackupException) {
            assertEquals(BackupError.CORRUPT_MEDIA, e.error)
        }
    }

    @Test
    fun testExportFailsWhenDbPlaintextSizeWrong(): Unit = runBlocking {
        val (mediaId, _, _) = insertValidMedia()
        // Corrupt plaintextSize in DB
        db.openHelper.writableDatabase.execSQL(
            "UPDATE media_items SET plaintextSize = ? WHERE id = ?",
            arrayOf(999999L, mediaId)
        )

        try {
            exporter.exportVault(ByteArrayOutputStream(), recoveryCode, { _, _, _, _ -> })
            fail("Expected export to fail on wrong plaintextSize")
        } catch (e: BackupException) {
            assertEquals(BackupError.CORRUPT_MEDIA, e.error)
        }
    }

    @Test
    fun testExportFailsWhenFolderLockLacksRecoveryEnvelope(): Unit = runBlocking {
        insertValidMedia()
        // Insert a folder lock with recoveryEnvelope == null (migrated legacy lock)
        val folderId = "f_migrated"
        db.folderDao().insert(
            FolderEntity(
                id = folderId, vaultId = vaultId, parentId = null,
                encryptedName = ByteArray(16), createdAt = 1000L, updatedAt = 1000L,
                coverMediaId = null, sortOrder = 0L, directHidden = false,
                effectiveHidden = false, lockId = "l_migrated", effectiveProtected = true
            )
        )
        db.folderLockDao().insert(
            FolderLockEntity(
                id = "l_migrated", vaultId = vaultId, folderId = folderId,
                credentialTypeCode = 0, credentialEnvelope = ByteArray(32),
                biometricEnvelope = null, biometricIv = null,
                recoveryEnvelope = null, // NULL!
                createdAt = 1000L, updatedAt = 1000L
            )
        )

        try {
            exporter.exportVault(ByteArrayOutputStream(), recoveryCode, { _, _, _, _ -> })
            fail("Expected export to fail with FOLDER_LOCK_RECOVERY_NOT_READY")
        } catch (e: BackupException) {
            assertEquals(BackupError.FOLDER_LOCK_RECOVERY_NOT_READY, e.error)
        }
    }

    @Test
    fun testCorruptThumbnailIsDroppedWhileMediaIsExportedSuccessfully(): Unit = runBlocking {
        val (mediaId, _, _) = insertValidMedia()

        // Create corrupt thumbnail file on disk
        val thumbFile = fileStore.getThumbFile(vaultId, mediaId)
        thumbFile.writeBytes("corrupted thumbnail non-aead bytes".toByteArray())

        val prefix = if (mediaId.length >= 2) mediaId.substring(0, 2) else "xx"
        db.openHelper.writableDatabase.execSQL(
            "UPDATE media_items SET encryptedThumbRelativePath = ? WHERE id = ?",
            arrayOf("thumbs/$prefix/$mediaId.sth", mediaId)
        )

        val baos = ByteArrayOutputStream()
        val summary = exporter.exportVault(baos, recoveryCode, { _, _, _, _ -> })
        assertEquals(1, summary.mediaCount)

        // Verify the exported archive: thumbnail should have been omitted because it was corrupt
        val verified = verifier.verifyFullArchive(ByteArrayInputStream(baos.toByteArray()), recoveryCode)
        assertEquals(1, verified.mediaCount)
    }
}
