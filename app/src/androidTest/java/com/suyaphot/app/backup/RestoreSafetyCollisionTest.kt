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
import com.suyaphot.app.domain.backup.VaultBackupImporter
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreSafetyCollisionTest {

    private val pepper = object : PepperProvider {
        override fun hmacSha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
    }

    private suspend fun createSampleBackupBytes(
        context: android.content.Context,
        vaultId: String,
        recoveryCode: String
    ): ByteArray {
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val keyManager = KeyManager(context, pepper)
        val vaultCrypto = VaultCrypto()
        val securityPrefs = SecurityPreferences(context)
        val fileStore = VaultFileStore(context)
        val session = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))

        val masterKey = keyManager.generateMasterKey()
        val normalizedRecovery = keyManager.normalizeRecoverySecret(recoveryCode)
        val pinEnvelope = keyManager.createPinEnvelope(masterKey, "123456".toCharArray())
        val recoveryEnvelope = keyManager.createRecoveryEnvelope(masterKey, normalizedRecovery)

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

            val mediaId = UUID.randomUUID().toString()
            val mediaPlaintext = "Restore Safety Test Payload".toByteArray(Charsets.UTF_8)
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

            val rawMetadata = PrivateMediaMetadata(
                originalDisplayName = "safety_test.jpg",
                originalRelativePath = "DCIM/Camera",
                originalMimeType = "image/jpeg",
                originalContentUri = null,
                dateTakenMs = System.currentTimeMillis(),
                dateModifiedMs = System.currentTimeMillis(),
                width = 100,
                height = 100,
                durationMs = null,
                orientation = 0,
                sourceVolume = null,
                sourceMediaStoreId = null,
                gpsWasAvailable = false,
                originalFileExtension = "jpg"
            ).serialize()

            val encMetadata = Aead.encryptWithPrependedNonce(
                keyBytes = metaSubkey,
                plaintext = rawMetadata,
                aad = mediaId.toByteArray(Charsets.UTF_8)
            )

            val mediaEntity = MediaItemEntity(
                id = mediaId,
                vaultId = vaultId,
                folderId = null,
                mediaTypeCode = 0,
                encryptedMetadata = encMetadata,
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
            exporter.exportVault(baos, recoveryCode) { _, _, _, _ -> }
            return baos.toByteArray()
        } finally {
            db.close()
            fileStore.getVaultDir(vaultId).deleteRecursively()
            masterKey.fill(0)
        }
    }

    @Test
    fun testInvalidNewCredentialRejectedImmediately(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recoveryCode = KeyManager(context, pepper).generateRecoverySecret()
        val backupBytes = createSampleBackupBytes(context, "vault_safety_1", recoveryCode)

        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val keyManager = KeyManager(context, pepper)
        val fileStore = VaultFileStore(context)
        val securityPrefs = SecurityPreferences(context)
        val session = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))
        val access = FolderAccessManager(db, session, CoroutineScope(Dispatchers.Unconfined))
        val privacy = FolderPrivacyCoordinator(db)

        val importer = VaultBackupImporter(
            context = context,
            database = db,
            fileStore = fileStore,
            keyManager = keyManager,
            privacyCoordinator = privacy,
            vaultCrypto = VaultCrypto(),
            sessionManager = session,
            accessManager = access
        )

        // 1. Non-digit PIN
        try {
            importer.restoreVault(
                inputStream = ByteArrayInputStream(backupBytes),
                recoveryCodeInput = recoveryCode,
                newCredential = "ABCDEF".toCharArray(),
                newCredentialType = 0,
                onProgress = { _, _, _, _ -> }
            )
            fail("Expected INVALID_NEW_CREDENTIAL for alphabetic PIN")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_NEW_CREDENTIAL, e.error)
        }

        // 2. Short PIN
        try {
            importer.restoreVault(
                inputStream = ByteArrayInputStream(backupBytes),
                recoveryCodeInput = recoveryCode,
                newCredential = "123".toCharArray(),
                newCredentialType = 0,
                onProgress = { _, _, _, _ -> }
            )
            fail("Expected INVALID_NEW_CREDENTIAL for short PIN")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_NEW_CREDENTIAL, e.error)
        }

        // 3. Invalid Pattern
        try {
            importer.restoreVault(
                inputStream = ByteArrayInputStream(backupBytes),
                recoveryCodeInput = recoveryCode,
                newCredential = "P:01".toCharArray(),
                newCredentialType = 1,
                onProgress = { _, _, _, _ -> }
            )
            fail("Expected INVALID_NEW_CREDENTIAL for short pattern")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_NEW_CREDENTIAL, e.error)
        }

        // 4. Invalid type code
        try {
            importer.restoreVault(
                inputStream = ByteArrayInputStream(backupBytes),
                recoveryCodeInput = recoveryCode,
                newCredential = "123456".toCharArray(),
                newCredentialType = 99,
                onProgress = { _, _, _, _ -> }
            )
            fail("Expected INVALID_NEW_CREDENTIAL for type 99")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_NEW_CREDENTIAL, e.error)
        }

        // Assert DB and disk remain completely clean
        assertEquals(0, db.vaultDao().getAllVaults().size)
        db.close()
    }

    @Test
    fun testPreExistingTargetDirectoryNotDeletedOnCollision(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recoveryCode = KeyManager(context, pepper).generateRecoverySecret()
        val targetVaultId = "vault_collision_dir"
        val backupBytes = createSampleBackupBytes(context, targetVaultId, recoveryCode)

        val fileStore = VaultFileStore(context)
        val targetDir = fileStore.vaultDirPath(targetVaultId)
        targetDir.mkdirs()
        val sentinelFile = File(targetDir, "do_not_delete.sentinel")
        sentinelFile.writeText("Pre-existing user sentinel data")

        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val keyManager = KeyManager(context, pepper)
        val securityPrefs = SecurityPreferences(context)
        val session = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))
        val access = FolderAccessManager(db, session, CoroutineScope(Dispatchers.Unconfined))
        val privacy = FolderPrivacyCoordinator(db)

        val importer = VaultBackupImporter(
            context = context,
            database = db,
            fileStore = fileStore,
            keyManager = keyManager,
            privacyCoordinator = privacy,
            vaultCrypto = VaultCrypto(),
            sessionManager = session,
            accessManager = access
        )

        try {
            importer.restoreVault(
                inputStream = ByteArrayInputStream(backupBytes),
                recoveryCodeInput = recoveryCode,
                newCredential = "654321".toCharArray(),
                newCredentialType = 0,
                onProgress = { _, _, _, _ -> }
            )
            fail("Expected RESTORE_TARGET_COLLISION due to pre-existing directory")
        } catch (e: BackupException) {
            assertEquals(BackupError.RESTORE_TARGET_COLLISION, e.error)
        }

        // Invariant: Pre-existing sentinel file and directory MUST NOT be deleted
        assertTrue(targetDir.exists())
        assertTrue(sentinelFile.exists())
        assertEquals("Pre-existing user sentinel data", sentinelFile.readText())

        // Cleanup sentinel test
        targetDir.deleteRecursively()
        db.close()
    }

    @Test
    fun testPreExistingDbVaultIdCollisionRejected(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recoveryCode = KeyManager(context, pepper).generateRecoverySecret()
        val targetVaultId = "vault_collision_db"
        val backupBytes = createSampleBackupBytes(context, targetVaultId, recoveryCode)

        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val keyManager = KeyManager(context, pepper)
        val fileStore = VaultFileStore(context)
        val securityPrefs = SecurityPreferences(context)
        val session = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))
        val access = FolderAccessManager(db, session, CoroutineScope(Dispatchers.Unconfined))
        val privacy = FolderPrivacyCoordinator(db)

        // Deliberately insert a vault row with same ID but different kind to test ID collision check
        val dummyVault = VaultEntity(
            id = targetVaultId,
            kindCode = VaultKind.SECONDARY.code,
            createdAt = System.currentTimeMillis(),
            schemaVersion = 5,
            pinEnvelope = ByteArray(16),
            recoveryEnvelope = null,
            biometricEnvelope = null,
            biometricIv = null,
            credentialTypeCode = 0
        )
        db.vaultDao().insert(dummyVault)

        val importer = VaultBackupImporter(
            context = context,
            database = db,
            fileStore = fileStore,
            keyManager = keyManager,
            privacyCoordinator = privacy,
            vaultCrypto = VaultCrypto(),
            sessionManager = session,
            accessManager = access
        )

        try {
            importer.restoreVault(
                inputStream = ByteArrayInputStream(backupBytes),
                recoveryCodeInput = recoveryCode,
                newCredential = "654321".toCharArray(),
                newCredentialType = 0,
                onProgress = { _, _, _, _ -> }
            )
            fail("Expected RESTORE_TARGET_COLLISION due to duplicate vault ID")
        } catch (e: BackupException) {
            assertEquals(BackupError.RESTORE_TARGET_COLLISION, e.error)
        }

        // Dummy vault remains untouched
        assertEquals(1, db.vaultDao().getAllVaults().size)
        db.close()
    }

    @Test
    fun testWrongRecoveryCodeStillWipesNewCredentialBuffer(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyManager = KeyManager(context, pepper)
        val recoveryCode = keyManager.generateRecoverySecret()
        var wrongRecoveryCode = keyManager.generateRecoverySecret()
        while (wrongRecoveryCode == recoveryCode) {
            wrongRecoveryCode = keyManager.generateRecoverySecret()
        }
        val targetVaultId = "vault_restore_secret_wipe"
        val backupBytes = createSampleBackupBytes(context, targetVaultId, recoveryCode)

        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val fileStore = VaultFileStore(context)
        val securityPrefs = SecurityPreferences(context)
        val session = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))
        val access = FolderAccessManager(db, session, CoroutineScope(Dispatchers.Unconfined))
        val privacy = FolderPrivacyCoordinator(db)
        val importer = VaultBackupImporter(
            context = context,
            database = db,
            fileStore = fileStore,
            keyManager = keyManager,
            privacyCoordinator = privacy,
            vaultCrypto = VaultCrypto(),
            sessionManager = session,
            accessManager = access
        )

        val newCredential = "654321".toCharArray()
        try {
            importer.restoreVault(
                inputStream = ByteArrayInputStream(backupBytes),
                recoveryCodeInput = wrongRecoveryCode,
                newCredential = newCredential,
                newCredentialType = 0,
                onProgress = { _, _, _, _ -> }
            )
            fail("Expected incorrect Recovery Code to reject restore")
        } catch (e: BackupException) {
            assertEquals(BackupError.INCORRECT_RECOVERY_CODE, e.error)
        }

        org.junit.Assert.assertTrue(
            "Restore must wipe the new credential even when failure happens before staging",
            newCredential.all { it == '\u0000' }
        )
        assertEquals(0, db.vaultDao().getAllVaults().size)
        db.close()
        fileStore.vaultDirPath(targetVaultId).deleteRecursively()
    }

}
