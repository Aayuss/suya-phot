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
import com.suyaphot.app.domain.auth.AuthResult
import com.suyaphot.app.domain.auth.PinAuthenticator
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.backup.BackupError
import com.suyaphot.app.domain.backup.BackupException
import com.suyaphot.app.domain.backup.BackupVerifier
import com.suyaphot.app.domain.backup.VaultBackupExporter
import com.suyaphot.app.domain.backup.VaultBackupImporter
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.folders.FolderLockManager
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupRestoreIntegrationTest {

    private val pepper = object : PepperProvider {
        override fun hmacSha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
    }

    @Test
    fun testExportVerifyAndFailSafeRestoreRoundTrip(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db1 = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val keyManager = KeyManager(context, pepper)
        val vaultCrypto = VaultCrypto()
        val securityPrefs = SecurityPreferences(context)
        val fileStore = VaultFileStore(context)
        val session1 = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))
        val access1 = FolderAccessManager(db1, session1, CoroutineScope(Dispatchers.Unconfined))
        val privacy1 = FolderPrivacyCoordinator(db1)
        val lockManager1 = FolderLockManager(db1, keyManager, session1, access1, privacy1, context)

        val masterKey = keyManager.generateMasterKey()
        val recoveryCode = keyManager.generateRecoverySecret()
        val normalizedRecovery = keyManager.normalizeRecoverySecret(recoveryCode)
        val pinChars = "123456".toCharArray()

        val pinEnvelope = keyManager.createPinEnvelope(masterKey, pinChars)
        val recoveryEnvelope = keyManager.createRecoveryEnvelope(masterKey, normalizedRecovery)

        val vaultId = "testvault123"
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
            db1.vaultDao().insert(vaultEntity)
            val mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
            val metaSubkey = vaultCrypto.deriveMetaSubkey(masterKey)
            val thumbSubkey = vaultCrypto.deriveThumbSubkey(masterKey)

            session1.unlock(
                vaultId = vaultId,
                kind = VaultKind.REAL,
                masterKeyHandle = SensitiveKeyHandle(masterKey.copyOf()),
                mediaSubkey = mediaSubkey,
                metaSubkey = metaSubkey,
                thumbSubkey = thumbSubkey
            )

            // Create folder and folder lock
            val folderId = UUID.randomUUID().toString()
            val encFolderName = Aead.encryptWithPrependedNonce(
                keyBytes = metaSubkey,
                plaintext = "Confidential".toByteArray(Charsets.UTF_8),
                aad = folderId.toByteArray(Charsets.UTF_8)
            )
            db1.folderDao().insert(
                FolderEntity(
                    id = folderId,
                    vaultId = vaultId,
                    parentId = null,
                    encryptedName = encFolderName,
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
            assertTrue(lockManager1.create(folderId, "9876".toCharArray(), 0))

            // Create media item and write encrypted file to disk using VaultFileStore
            val mediaId = UUID.randomUUID().toString()
            val mediaPlaintext = "Hello Suya Phot Secure Media Content!".toByteArray(Charsets.UTF_8)
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
                originalDisplayName = "test_image.jpg",
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

            val prefix = if (mediaId.length >= 2) mediaId.substring(0, 2) else "xx"
            val mediaEntity = MediaItemEntity(
                id = mediaId,
                vaultId = vaultId,
                folderId = folderId,
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
                concealed = true
            )
            db1.mediaItemDao().insert(mediaEntity)

            val verifier = BackupVerifier(keyManager)
            val exporter = VaultBackupExporter(
                database = db1,
                fileStore = fileStore,
                keyManager = keyManager,
                sessionManager = session1,
                vaultCrypto = vaultCrypto
            )

            // 1. Export vault
            val backupBaos = ByteArrayOutputStream()
            val summary = exporter.exportVault(
                outputStream = backupBaos,
                recoveryCodeInput = recoveryCode,
                onProgress = { _, _, _, _ -> }
            )
            assertEquals(1, summary.mediaCount)
            assertEquals(1, summary.folderCount)

            val backupBytes = backupBaos.toByteArray()

            // 2. Full archive verification
            val verifiedSummary = verifier.verifyFullArchive(
                inputStream = ByteArrayInputStream(backupBytes),
                recoveryCodeInput = recoveryCode
            )
            assertEquals(summary.mediaCount, verifiedSummary.mediaCount)

            // 3. Test Fail-Safe Invariant: Cannot restore into existing vault of same kind
            val importerExisting = VaultBackupImporter(
                context = context,
                database = db1,
                fileStore = fileStore,
                keyManager = keyManager,
                privacyCoordinator = privacy1,
                vaultCrypto = vaultCrypto,
                sessionManager = session1,
                accessManager = access1
            )

            try {
                importerExisting.restoreVault(
                    inputStream = ByteArrayInputStream(backupBytes),
                    recoveryCodeInput = recoveryCode,
                    newCredential = "654321".toCharArray(),
                    newCredentialType = 0,
                    onProgress = { _, _, _, _ -> }
                )
                fail("Expected restore into existing vault to fail with RESTORE_REQUIRES_EMPTY_VAULT")
            } catch (e: BackupException) {
                assertEquals(BackupError.RESTORE_REQUIRES_EMPTY_VAULT, e.error)
            }

            // Existing vault must remain completely intact
            assertEquals(1, db1.vaultDao().getAllVaults().size)
            assertEquals(1, db1.mediaItemDao().getAllForIntegrityCheck(vaultId).size)

            // Clean up existing vault on disk to simulate fresh install / target device
            fileStore.getVaultDir(vaultId).deleteRecursively()

            // 4. Test Restore into Fresh Database
            val db2 = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
            val session2 = SessionManager(securityPrefs, CoroutineScope(Dispatchers.Unconfined))
            val access2 = FolderAccessManager(db2, session2, CoroutineScope(Dispatchers.Unconfined))
            val privacy2 = FolderPrivacyCoordinator(db2)
            val lockManager2 = FolderLockManager(db2, keyManager, session2, access2, privacy2, context)
            val pinAuth2 = PinAuthenticator(db2.vaultDao(), keyManager, vaultCrypto, session2, securityPrefs)

            val importerClean = VaultBackupImporter(
                context = context,
                database = db2,
                fileStore = fileStore,
                keyManager = keyManager,
                privacyCoordinator = privacy2,
                vaultCrypto = vaultCrypto,
                sessionManager = session2,
                accessManager = access2
            )

            val restoreResult = importerClean.restoreVault(
                inputStream = ByteArrayInputStream(backupBytes),
                recoveryCodeInput = recoveryCode,
                newCredential = "654321".toCharArray(),
                newCredentialType = 0,
                onProgress = { _, _, _, _ -> }
            )
            assertEquals(1, restoreResult.mediaCount)
            assertEquals(1, restoreResult.folderCount)

            // Verify session was locked post-restore
            assertTrue(session2.sessionState.value is VaultSession.Locked)

            // Authenticate with the NEW PIN "654321"
            val authResult = pinAuth2.authenticateWithPin("654321".toCharArray())
            assertTrue(authResult is AuthResult.Success)
            val restoredVaultId = (session2.sessionState.value as VaultSession.Unlocked).vaultId

            // Verify restored folder lock has requiresCredentialReset = true
            val restoredLock = db2.folderLockDao().getForFolder(restoredVaultId, folderId)
            assertNotNull(restoredLock)
            assertTrue(restoredLock!!.requiresCredentialReset)

            // Folder cannot be unlocked directly before reset
            assertFalse(lockManager2.unlock(restoredLock.id, "9876".toCharArray(), 0))

            // Reset folder credential using recovery code
            assertTrue(lockManager2.resetWithRecovery(folderId, recoveryCode, "1122".toCharArray(), 0))
            val postResetLock = db2.folderLockDao().getForFolder(restoredVaultId, folderId)
            assertFalse(postResetLock!!.requiresCredentialReset)

            // Now unlock folder with new PIN "1122"
            assertTrue(lockManager2.unlock(postResetLock.id, "1122".toCharArray(), 0))

            // Clean up
            db2.close()
            fileStore.getVaultDir(restoredVaultId).deleteRecursively()
        } finally {
            db1.close()
            fileStore.getVaultDir(vaultId).deleteRecursively()
            masterKey.fill(0)
            pinChars.fill('\u0000')
        }
    }

    @Test
    fun testCorruptArchiveRejection(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyManager = KeyManager(context, pepper)
        val verifier = BackupVerifier(keyManager)

        val invalidBytes = ByteArray(100) { 0x55.toByte() }
        try {
            verifier.verifyFullArchive(ByteArrayInputStream(invalidBytes), "ABCDEFGHJKLMNPQRSTUVWXYZ23")
            fail("Expected verification to fail on invalid header")
        } catch (e: BackupException) {
            assertEquals(BackupError.INVALID_ARCHIVE, e.error)
        }
    }
}
