package com.suyaphot.app.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.trash.TrashCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArtifactCleanupRecoveryTest {

    @Test
    fun deleteForeverKeepsJournalUntilMediaThumbAndPreviewAreAllGone(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val fileStore = VaultFileStore(context)
        val preferences = SecurityPreferences(context)

        val vaultId = "cleanup_${UUID.randomUUID()}"
        val itemId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        try {
            database.vaultDao().insert(
                VaultEntity(
                    id = vaultId,
                    kindCode = 0,
                    createdAt = now,
                    schemaVersion = 6,
                    pinEnvelope = ByteArray(64),
                    recoveryEnvelope = null,
                    biometricEnvelope = null,
                    biometricIv = null,
                    credentialTypeCode = 0
                )
            )

            database.mediaItemDao().insert(
                MediaItemEntity(
                    id = itemId,
                    vaultId = vaultId,
                    folderId = null,
                    mediaTypeCode = 0,
                    encryptedMetadata = ByteArray(32),
                    encryptedFileRelativePath = "$itemId.sph",
                    encryptedThumbRelativePath = "$itemId.sth",
                    plaintextSize = 4L,
                    cipherSize = 32L,
                    sha256Hex = "00".repeat(32),
                    importedAt = now,
                    updatedAt = now,
                    favorite = false,
                    deletedAt = now,
                    previousFolderId = null,
                    dateTakenMs = null,
                    encryptedPreviewRelativePath = "$itemId.spr",
                    cleanupStateCode = 0,
                    concealed = false
                )
            )

            val media = fileStore.getMediaFile(vaultId, itemId).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val thumb = fileStore.getThumbFile(vaultId, itemId).apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val preview = fileStore.getPreviewFile(vaultId, itemId)
            assertTrue(preview.mkdir())
            val blocker = java.io.File(preview, "blocker").apply { writeText("keep directory non-empty") }

            val coordinator = TrashCoordinator(database, fileStore, preferences)
            val first = coordinator.permanentDelete(vaultId, listOf(itemId))

            assertEquals(0, first.deleted)
            assertEquals(1, first.cleanupPending)
            assertNotNull(database.mediaItemDao().getItemForVault(itemId, vaultId))
            assertFalse(media.exists())
            assertFalse(thumb.exists())
            assertTrue(preview.exists())

            assertTrue(blocker.delete())
            assertTrue(preview.delete())

            val recovered = coordinator.reconcilePending(vaultId)
            assertEquals(1, recovered.deleted)
            assertEquals(0, recovered.cleanupPending)
            assertNull(database.mediaItemDao().getItemForVault(itemId, vaultId))
            assertFalse(fileStore.getMediaFile(vaultId, itemId).exists())
            assertFalse(fileStore.getThumbFile(vaultId, itemId).exists())
            assertFalse(fileStore.getPreviewFile(vaultId, itemId).exists())
        } finally {
            database.close()
            fileStore.vaultDirPath(vaultId).deleteRecursively()
        }
    }
}
