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
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.backup.BackupError
import com.suyaphot.app.domain.backup.BackupException
import com.suyaphot.app.domain.backup.VaultBackupExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportIntegrityPreflightTest {

    private val pepper = object : PepperProvider {
        override fun hmacSha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
    }

    @Test
    fun testCorruptFolderEncryptedNameRefusesExport(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val keyManager = KeyManager(context, pepper)
        val vaultCrypto = VaultCrypto()
        val securityPrefs = SecurityPreferences(context)
        val fileStore = VaultFileStore(context)
        val session = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))

        val masterKey = keyManager.generateMasterKey()
        val recoveryCode = keyManager.generateRecoverySecret()
        val normalizedRecovery = keyManager.normalizeRecoverySecret(recoveryCode)
        val pinEnvelope = keyManager.createPinEnvelope(masterKey, "123456".toCharArray())
        val recoveryEnvelope = keyManager.createRecoveryEnvelope(masterKey, normalizedRecovery)

        val vaultId = "testvault_corrupt_folder"
        val vaultEntity = VaultEntity(
            id = vaultId,
            kindCode = VaultKind.REAL.code,
            createdAt = System.currentTimeMillis(),
            schemaVersion = 5,
            pinEnvelope = pinEnvelope.serialize(),
            recoveryEnvelope = recoveryEnvelope.serialize(),
            biometricEnvelope = null,
            biometricIv = null,
            credentialTypeCode = 0
        )

        try {
            db.vaultDao().insert(vaultEntity)
            val mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
            val metaSubkey = vaultCrypto.deriveMetaSubkey(masterKey)
            val thumbSubkey = vaultCrypto.deriveThumbSubkey(masterKey)

            session.unlock(
                vaultId = vaultId,
                kind = VaultKind.REAL,
                masterKeyHandle = SensitiveKeyHandle(masterKey.copyOf()),
                mediaSubkey = mediaSubkey,
                metaSubkey = metaSubkey,
                thumbSubkey = thumbSubkey
            )

            // Insert folder with corrupt encryptedName
            val folderId = UUID.randomUUID().toString()
            val corruptNameBytes = ByteArray(32) { 0x77.toByte() }
            db.folderDao().insert(
                FolderEntity(
                    id = folderId,
                    vaultId = vaultId,
                    parentId = null,
                    encryptedName = corruptNameBytes,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    coverMediaId = null,
                    sortOrder = 0L,
                    directHidden = false,
                    effectiveHidden = false,
                    lockId = null,
                    effectiveProtected = false
                )
            )

            val exporter = VaultBackupExporter(db, fileStore, keyManager, session, vaultCrypto)
            val baos = ByteArrayOutputStream()

            try {
                exporter.exportVault(baos, recoveryCode) { _, _, _, _ -> }
                fail("Expected export to fail due to corrupt folder encryptedName")
            } catch (e: BackupException) {
                assertEquals(BackupError.VAULT_INTEGRITY_CHECK_FAILED, e.error)
            }

            assertEquals(0, baos.size())
        } finally {
            db.close()
            fileStore.getVaultDir(vaultId).deleteRecursively()
            masterKey.fill(0)
        }
    }

    @Test
    fun testCorruptMediaMetadataRefusesExport(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val keyManager = KeyManager(context, pepper)
        val vaultCrypto = VaultCrypto()
        val securityPrefs = SecurityPreferences(context)
        val fileStore = VaultFileStore(context)
        val session = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))

        val masterKey = keyManager.generateMasterKey()
        val recoveryCode = keyManager.generateRecoverySecret()
        val normalizedRecovery = keyManager.normalizeRecoverySecret(recoveryCode)
        val pinEnvelope = keyManager.createPinEnvelope(masterKey, "123456".toCharArray())
        val recoveryEnvelope = keyManager.createRecoveryEnvelope(masterKey, normalizedRecovery)

        val vaultId = "testvault_corrupt_meta"
        val vaultEntity = VaultEntity(
            id = vaultId,
            kindCode = VaultKind.REAL.code,
            createdAt = System.currentTimeMillis(),
            schemaVersion = 5,
            pinEnvelope = pinEnvelope.serialize(),
            recoveryEnvelope = recoveryEnvelope.serialize(),
            biometricEnvelope = null,
            biometricIv = null,
            credentialTypeCode = 0
        )

        try {
            db.vaultDao().insert(vaultEntity)
            val mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
            val metaSubkey = vaultCrypto.deriveMetaSubkey(masterKey)
            val thumbSubkey = vaultCrypto.deriveThumbSubkey(masterKey)

            session.unlock(
                vaultId = vaultId,
                kind = VaultKind.REAL,
                masterKeyHandle = SensitiveKeyHandle(masterKey.copyOf()),
                mediaSubkey = mediaSubkey,
                metaSubkey = metaSubkey,
                thumbSubkey = thumbSubkey
            )

            // Create media file on disk with valid ciphertext
            val mediaId = UUID.randomUUID().toString()
            val mediaPlaintext = "Sample valid media".toByteArray(Charsets.UTF_8)
            val mediaFile = fileStore.getMediaFile(vaultId, mediaId)
            val verificationResult = vaultCrypto.encryptStream(
                input = ByteArrayInputStream(mediaPlaintext),
                outputFile = mediaFile,
                mediaSubkey = mediaSubkey,
                itemId = mediaId,
                isVideo = false,
                plaintextSize = mediaPlaintext.size.toLong()
            )
            val sha256Hex = verificationResult.sha256.joinToString("") { "%02x".format(it) }

            // Insert media entity with corrupt encryptedMetadata
            val corruptMetaBytes = ByteArray(48) { 0x88.toByte() }
            val mediaEntity = MediaItemEntity(
                id = mediaId,
                vaultId = vaultId,
                folderId = null,
                mediaTypeCode = 0,
                encryptedMetadata = corruptMetaBytes,
                encryptedFileRelativePath = mediaFile.name,
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
            db.mediaItemDao().insert(mediaEntity)

            val exporter = VaultBackupExporter(db, fileStore, keyManager, session, vaultCrypto)
            val baos = ByteArrayOutputStream()

            try {
                exporter.exportVault(baos, recoveryCode) { _, _, _, _ -> }
                fail("Expected export to fail due to corrupt media metadata")
            } catch (e: BackupException) {
                assertEquals(BackupError.VAULT_INTEGRITY_CHECK_FAILED, e.error)
            }

            assertEquals(0, baos.size())
        } finally {
            db.close()
            fileStore.getVaultDir(vaultId).deleteRecursively()
            masterKey.fill(0)
        }
    }

    @Test
    fun testSourceCiphertextGrowthDuringExportIsRejected(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val keyManager = KeyManager(context, pepper)
        val vaultCrypto = VaultCrypto()
        val securityPrefs = SecurityPreferences(context)
        val fileStore = VaultFileStore(context)
        val session = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))

        val masterKey = keyManager.generateMasterKey()
        val recoveryCode = keyManager.generateRecoverySecret()
        val normalizedRecovery = keyManager.normalizeRecoverySecret(recoveryCode)
        val pinEnvelope = keyManager.createPinEnvelope(masterKey, "123456".toCharArray())
        val recoveryEnvelope = keyManager.createRecoveryEnvelope(masterKey, normalizedRecovery)

        val vaultId = "testvault_export_drift"
        try {
            db.vaultDao().insert(
                VaultEntity(
                    id = vaultId,
                    kindCode = VaultKind.REAL.code,
                    createdAt = System.currentTimeMillis(),
                    schemaVersion = 6,
                    pinEnvelope = pinEnvelope.serialize(),
                    recoveryEnvelope = recoveryEnvelope.serialize(),
                    biometricEnvelope = null,
                    biometricIv = null,
                    credentialTypeCode = 0
                )
            )

            val mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
            val metaSubkey = vaultCrypto.deriveMetaSubkey(masterKey)
            val thumbSubkey = vaultCrypto.deriveThumbSubkey(masterKey)
            session.unlock(
                vaultId = vaultId,
                kind = VaultKind.REAL,
                masterKeyHandle = SensitiveKeyHandle(masterKey.copyOf()),
                mediaSubkey = mediaSubkey,
                metaSubkey = metaSubkey,
                thumbSubkey = thumbSubkey
            )

            val mediaId = UUID.randomUUID().toString()
            // Larger than the backup BufferedOutputStream so its first underlying write
            // occurs while this source body is being streamed.
            val plaintext = ByteArray(512 * 1024) { index -> (index and 0xFF).toByte() }
            val mediaFile = fileStore.getMediaFile(vaultId, mediaId)
            val verification = vaultCrypto.encryptStream(
                input = ByteArrayInputStream(plaintext),
                outputFile = mediaFile,
                mediaSubkey = mediaSubkey,
                itemId = mediaId,
                isVideo = false,
                plaintextSize = plaintext.size.toLong()
            )

            val rawMetadata = PrivateMediaMetadata(
                originalDisplayName = "drift.jpg",
                originalRelativePath = "DCIM/Camera",
                originalMimeType = "image/jpeg",
                originalContentUri = null,
                dateTakenMs = null,
                dateModifiedMs = null,
                width = 100,
                height = 100,
                durationMs = null,
                orientation = 1,
                sourceVolume = null,
                sourceMediaStoreId = null,
                gpsWasAvailable = false,
                originalFileExtension = "jpg"
            ).serialize()
            val encryptedMetadata = try {
                Aead.encryptWithPrependedNonce(
                    keyBytes = metaSubkey,
                    plaintext = rawMetadata,
                    aad = mediaId.toByteArray(Charsets.UTF_8)
                )
            } finally {
                rawMetadata.fill(0)
            }

            db.mediaItemDao().insert(
                MediaItemEntity(
                    id = mediaId,
                    vaultId = vaultId,
                    folderId = null,
                    mediaTypeCode = 0,
                    encryptedMetadata = encryptedMetadata,
                    encryptedFileRelativePath = mediaFile.name,
                    encryptedThumbRelativePath = null,
                    plaintextSize = plaintext.size.toLong(),
                    cipherSize = mediaFile.length(),
                    sha256Hex = verification.sha256.joinToString("") { "%02x".format(it) },
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
            )

            var mutated = false
            val output = object : ByteArrayOutputStream() {
                private fun mutateOnce() {
                    if (mutated) return
                    mutated = true
                    FileOutputStream(mediaFile, true).use { it.write(0x7A) }
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    mutateOnce()
                    super.write(b, off, len)
                }

                override fun write(b: Int) {
                    mutateOnce()
                    super.write(b)
                }
            }

            try {
                VaultBackupExporter(db, fileStore, keyManager, session, vaultCrypto)
                    .exportVault(output, recoveryCode) { _, _, _, _ -> }
                fail("Expected export to fail because source ciphertext changed after preflight")
            } catch (e: BackupException) {
                assertEquals(BackupError.CORRUPT_MEDIA, e.error)
            }

            org.junit.Assert.assertTrue("Test must actually mutate the source during export", mutated)
        } finally {
            db.close()
            fileStore.vaultDirPath(vaultId).deleteRecursively()
            masterKey.fill(0)
        }
    }

}
