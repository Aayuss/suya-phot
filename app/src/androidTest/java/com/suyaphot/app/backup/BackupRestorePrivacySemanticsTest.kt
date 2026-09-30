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
import com.suyaphot.app.domain.auth.PinAuthenticator
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.backup.VaultBackupExporter
import com.suyaphot.app.domain.backup.VaultBackupImporter
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.folders.FolderDeletePolicy
import com.suyaphot.app.domain.folders.FolderLockManager
import com.suyaphot.app.domain.folders.FolderManager
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import com.suyaphot.app.domain.trash.TrashCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupRestorePrivacySemanticsTest {

    private val pepper = object : PepperProvider {
        override fun hmacSha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
    }

    private suspend fun insertEncryptedMedia(
        db: SuyaDatabase,
        fileStore: VaultFileStore,
        vaultCrypto: VaultCrypto,
        vaultId: String,
        folderId: String?,
        plaintextContent: String,
        mediaSubkey: ByteArray,
        metaSubkey: ByteArray,
        staleConcealed: Boolean = false
    ): Pair<String, String> {
        val mediaId = UUID.randomUUID().toString()
        val plainBytes = plaintextContent.toByteArray(Charsets.UTF_8)
        val mediaFile = fileStore.getMediaFile(vaultId, mediaId)
        val verification = vaultCrypto.encryptStream(
            input = ByteArrayInputStream(plainBytes),
            outputFile = mediaFile,
            mediaSubkey = mediaSubkey,
            itemId = mediaId,
            isVideo = false,
            plaintextSize = plainBytes.size.toLong()
        )
        val sha256Hex = verification.sha256.joinToString("") { "%02x".format(it) }

        val rawMetadata = PrivateMediaMetadata(
            originalDisplayName = "$mediaId.jpg",
            originalRelativePath = "DCIM/Camera",
            originalMimeType = "image/jpeg",
            originalContentUri = null,
            dateTakenMs = System.currentTimeMillis(),
            dateModifiedMs = System.currentTimeMillis(),
            width = 200,
            height = 200,
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

        val entity = MediaItemEntity(
            id = mediaId,
            vaultId = vaultId,
            folderId = folderId,
            mediaTypeCode = 0,
            encryptedMetadata = encMetadata,
            encryptedFileRelativePath = mediaFile.name,
            encryptedThumbRelativePath = null,
            plaintextSize = plainBytes.size.toLong(),
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
            concealed = staleConcealed
        )
        db.mediaItemDao().insert(entity)
        return Pair(mediaId, sha256Hex)
    }

    @Test
    fun testOrphanedPrivateTrashPreservesConcealmentAndRestoresToRecoveredPrivate(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db1 = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val keyManager = KeyManager(context, pepper)
        val vaultCrypto = VaultCrypto()
        val securityPrefs1 = SecurityPreferences(context)
        val fileStore = VaultFileStore(context)
        val session1 = SessionManager(securityPrefs1, CoroutineScope(Dispatchers.Unconfined))
        val access1 = FolderAccessManager(db1, session1, CoroutineScope(Dispatchers.Unconfined))
        val privacy1 = FolderPrivacyCoordinator(db1)
        val lockManager1 = FolderLockManager(db1, keyManager, session1, access1, privacy1, context)
        val folderManager1 = FolderManager(session1, db1.folderDao(), db1.mediaItemDao(), db1, privacy1, access1)

        val masterKey = keyManager.generateMasterKey()
        val recoveryCode = keyManager.generateRecoverySecret()
        val normalizedRecovery = keyManager.normalizeRecoverySecret(recoveryCode)
        val pinEnvelope = keyManager.createPinEnvelope(masterKey, "123456".toCharArray())
        val recoveryEnvelope = keyManager.createRecoveryEnvelope(masterKey, normalizedRecovery)

        val vaultId = "vault_privacy_test"
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

            // 1. Create hierarchy: Public folder, Hidden A, Locked B, Protected C, Ordinary D
            val publicFolderId = folderManager1.createFolder("Public Folder", null)
            val hiddenFolderAId = folderManager1.createFolder("Hidden A", null)
            privacy1.setHidden(vaultId, hiddenFolderAId, true)

            val lockedFolderBId = folderManager1.createFolder("Locked B", null)
            assertTrue(lockManager1.create(lockedFolderBId, "1111".toCharArray(), 0))

            val protectedFolderCId = folderManager1.createFolder("Protected C", null)
            assertTrue(lockManager1.create(protectedFolderCId, "2222".toCharArray(), 0))

            val ordinaryFolderDId = folderManager1.createFolder("Ordinary D", null)

            // 2. Insert items into each
            val (publicItemId, _) = insertEncryptedMedia(db1, fileStore, vaultCrypto, vaultId, publicFolderId, "Public Item", mediaSubkey, metaSubkey)
            val (hiddenItemId, _) = insertEncryptedMedia(db1, fileStore, vaultCrypto, vaultId, hiddenFolderAId, "Hidden Item", mediaSubkey, metaSubkey)
            val (lockedItemId, _) = insertEncryptedMedia(db1, fileStore, vaultCrypto, vaultId, lockedFolderBId, "Locked Item", mediaSubkey, metaSubkey)
            val (orphanedPrivateId, orphanedSha) = insertEncryptedMedia(db1, fileStore, vaultCrypto, vaultId, protectedFolderCId, "Irreplaceable Orphaned Private Photo", mediaSubkey, metaSubkey)
            val (ordinaryOrphanId, _) = insertEncryptedMedia(db1, fileStore, vaultCrypto, vaultId, ordinaryFolderDId, "Ordinary Orphaned Photo", mediaSubkey, metaSubkey)

            // Active normal media with stale archived concealed = true
            val (staleConcealedId, _) = insertEncryptedMedia(db1, fileStore, vaultCrypto, vaultId, null, "Active Root Media With Stale Concealed", mediaSubkey, metaSubkey, staleConcealed = true)

            // Recompute initial privacy
            privacy1.recomputeInsideTransaction(vaultId)

            // Verify active concealment
            val allInitialMedia = db1.mediaItemDao().getAllForIntegrityCheck(vaultId).associateBy { it.id }
            assertTrue(allInitialMedia[publicItemId] != null && !allInitialMedia[publicItemId]!!.concealed)
            assertTrue(allInitialMedia[hiddenItemId] != null && allInitialMedia[hiddenItemId]!!.concealed)
            assertTrue(allInitialMedia[lockedItemId] != null && allInitialMedia[lockedItemId]!!.concealed)
            assertTrue(allInitialMedia[orphanedPrivateId] != null && allInitialMedia[orphanedPrivateId]!!.concealed)
            assertTrue(allInitialMedia[ordinaryOrphanId] != null && !allInitialMedia[ordinaryOrphanId]!!.concealed)
            // Stale concealed item at root should have been recomputed to false by privacy coordinator
            assertTrue(allInitialMedia[staleConcealedId] != null && !allInitialMedia[staleConcealedId]!!.concealed)

            // Verify normal gallery view excludes concealed items
            val normalGalleryVisible = db1.mediaItemDao().getAllActiveOnce(vaultId).associateBy { it.id }
            assertTrue(normalGalleryVisible.containsKey(publicItemId))
            assertFalse(normalGalleryVisible.containsKey(hiddenItemId))
            assertFalse(normalGalleryVisible.containsKey(lockedItemId))
            assertFalse(normalGalleryVisible.containsKey(orphanedPrivateId))
            assertTrue(normalGalleryVisible.containsKey(ordinaryOrphanId))
            assertTrue(normalGalleryVisible.containsKey(staleConcealedId))

            // 3. Trash items:
            // - Trash hidden item (folder remains)
            db1.mediaItemDao().softDeleteForVault(vaultId, listOf(hiddenItemId), System.currentTimeMillis())
            // - Trash locked item (folder remains)
            db1.mediaItemDao().softDeleteForVault(vaultId, listOf(lockedItemId), System.currentTimeMillis())
            // - Delete protected folder C to Trash (unlock folder C first, then delete: folder C row deleted, media item orphaned in Trash)
            val lockC = db1.folderLockDao().getForFolder(vaultId, protectedFolderCId)!!
            assertTrue(lockManager1.unlock(lockC.id, "2222".toCharArray(), 0))
            folderManager1.deleteFolder(protectedFolderCId, FolderDeletePolicy.DELETE_CONTENTS_TO_TRASH)
            // - Delete ordinary folder D to Trash (folder D row deleted, media item orphaned in Trash)
            folderManager1.deleteFolder(ordinaryFolderDId, FolderDeletePolicy.DELETE_CONTENTS_TO_TRASH)

            // 4. Assert source state
            assertNull(db1.folderDao().getFolderForVault(protectedFolderCId, vaultId))
            assertNull(db1.folderDao().getFolderForVault(ordinaryFolderDId, vaultId))

            val sourceOrphaned = db1.mediaItemDao().getItemForVault(orphanedPrivateId, vaultId)
            assertNotNull(sourceOrphaned)
            assertNotNull(sourceOrphaned!!.deletedAt)
            assertNotNull(sourceOrphaned.previousFolderId)
            assertTrue(sourceOrphaned.concealed)

            val sourceNormalTrash = db1.mediaItemDao().getTrashItems(vaultId).first().map { it.id }
            assertFalse(sourceNormalTrash.contains(orphanedPrivateId))
            assertFalse(sourceNormalTrash.contains(hiddenItemId))
            assertFalse(sourceNormalTrash.contains(lockedItemId))
            assertTrue(sourceNormalTrash.contains(ordinaryOrphanId))

            val sourcePrivateTrash = db1.mediaItemDao().getPrivateTrashItems(vaultId).first().map { it.id }
            assertTrue(sourcePrivateTrash.contains(orphanedPrivateId))
            assertTrue(sourcePrivateTrash.contains(hiddenItemId))
            assertTrue(sourcePrivateTrash.contains(lockedItemId))

            // 5. Export real .suyavault
            val exporter = VaultBackupExporter(db1, fileStore, keyManager, session1, vaultCrypto)
            val backupBaos = ByteArrayOutputStream()
            val exportResult = exporter.exportVault(backupBaos, recoveryCode) { _, _, _, _ -> }
            assertTrue(exportResult.mediaCount >= 5)

            val backupBytes = backupBaos.toByteArray()

            // 6. Restore into fresh clean DB & state
            val db2 = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
            val securityPrefs2 = SecurityPreferences(context)
            val session2 = SessionManager(securityPrefs2, CoroutineScope(Dispatchers.Unconfined))
            val access2 = FolderAccessManager(db2, session2, CoroutineScope(Dispatchers.Unconfined))
            val privacy2 = FolderPrivacyCoordinator(db2)
            val pinAuth2 = PinAuthenticator(db2.vaultDao(), keyManager, vaultCrypto, session2, securityPrefs2)
            val trashCoordinator2 = TrashCoordinator(db2, fileStore, securityPrefs2, access2, session2)

            val importer = VaultBackupImporter(
                context = context,
                database = db2,
                fileStore = fileStore,
                keyManager = keyManager,
                privacyCoordinator = privacy2,
                vaultCrypto = vaultCrypto,
                sessionManager = session2,
                accessManager = access2
            )

            // Before restore, delete directory from db1 to allow clean restore
            fileStore.getVaultDir(vaultId).deleteRecursively()

            importer.restoreVault(
                inputStream = ByteArrayInputStream(backupBytes),
                recoveryCodeInput = recoveryCode,
                newCredential = "654321".toCharArray(),
                newCredentialType = 0,
                onProgress = { _, _, _, _ -> }
            )

            // Unlock restored vault
            pinAuth2.authenticateWithPin("654321".toCharArray())
            assertTrue(session2.sessionState.value is VaultSession.Unlocked)

            // 7. Verify destination database state:
            val restoredOrphan = db2.mediaItemDao().getItemForVault(orphanedPrivateId, vaultId)
            assertNotNull(restoredOrphan)
            assertNotNull(restoredOrphan!!.deletedAt)
            assertNull(restoredOrphan.previousFolderId) // Provenance folder was deleted
            assertTrue(restoredOrphan.concealed) // P0 Rule: Preserved conservative archived privacy!
            assertEquals(0, restoredOrphan.cleanupStateCode)

            // Ordinary deleted folder item remains ordinary trash
            val restoredOrdinaryOrphan = db2.mediaItemDao().getItemForVault(ordinaryOrphanId, vaultId)
            assertNotNull(restoredOrdinaryOrphan)
            assertNotNull(restoredOrdinaryOrphan!!.deletedAt)
            assertNull(restoredOrdinaryOrphan.previousFolderId)
            assertFalse(restoredOrdinaryOrphan.concealed)

            // Surviving locked folder item recomputes from surviving folder
            val restoredLockedItem = db2.mediaItemDao().getItemForVault(lockedItemId, vaultId)
            assertNotNull(restoredLockedItem)
            assertNotNull(restoredLockedItem!!.deletedAt)
            assertEquals(lockedFolderBId, restoredLockedItem.previousFolderId)
            assertTrue(restoredLockedItem.concealed)

            // Query assertions:
            val restoredNormalTrash = db2.mediaItemDao().getTrashItems(vaultId).first().map { it.id }
            assertFalse(restoredNormalTrash.contains(orphanedPrivateId))
            assertFalse(restoredNormalTrash.contains(hiddenItemId))
            assertFalse(restoredNormalTrash.contains(lockedItemId))
            assertTrue(restoredNormalTrash.contains(ordinaryOrphanId))

            val restoredPrivateTrash = db2.mediaItemDao().getPrivateTrashItems(vaultId).first().map { it.id }
            assertTrue(restoredPrivateTrash.contains(orphanedPrivateId))
            assertTrue(restoredPrivateTrash.contains(hiddenItemId))
            assertTrue(restoredPrivateTrash.contains(lockedItemId))

            // Active normal media visible normally
            val restoredActive = db2.mediaItemDao().getAllActiveOnce(vaultId).map { it.id }
            assertTrue(restoredActive.contains(publicItemId))
            assertTrue(restoredActive.contains(staleConcealedId))

            // 8. Restore the orphaned private item from Trash
            // Grant hidden access to simulate Private Trash gate re-auth
            access2.grantHidden(vaultId)

            val restoredCount = trashCoordinator2.restore(vaultId, listOf(orphanedPrivateId))
            assertEquals(1, restoredCount)

            val postRestoreItem = db2.mediaItemDao().getItemForVault(orphanedPrivateId, vaultId)
            assertNotNull(postRestoreItem)
            assertNull(postRestoreItem!!.deletedAt)
            assertTrue(postRestoreItem.concealed)

            // Invariant: Restores into the hidden "Recovered Private" folder, never at root
            assertNotNull(postRestoreItem.folderId)
            val recoveredFolder = db2.folderDao().getFolderForVault(postRestoreItem.folderId!!, vaultId)
            assertNotNull(recoveredFolder)
            assertTrue(recoveredFolder!!.effectiveHidden)

            // Ciphertext on disk remains intact and decryptable with matching SHA
            val restoredMediaFile = fileStore.getMediaFile(vaultId, orphanedPrivateId)
            assertTrue(restoredMediaFile.exists())
            val restoredVerified = vaultCrypto.verifyAndHash(restoredMediaFile, mediaSubkey, orphanedPrivateId)
            assertEquals(orphanedSha, restoredVerified.sha256.joinToString("") { "%02x".format(it) })

            db2.close()
        } finally {
            db1.close()
            fileStore.getVaultDir(vaultId).deleteRecursively()
            masterKey.fill(0)
        }
    }
}
