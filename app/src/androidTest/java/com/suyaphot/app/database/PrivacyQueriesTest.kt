package com.suyaphot.app.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyQueriesTest {
    @Test fun trashPrivacyTracksFolderChangesAndNeverDowngradesAfterFolderDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        try {
            db.vaultDao().insert(VaultEntity("vault", 0, 1, 1, byteArrayOf(1)))
            db.folderDao().insert(FolderEntity("folder", "vault", null, byteArrayOf(1), 1, 1, null, 0))
            db.mediaItemDao().insert(MediaItemEntity(
                "trashed", "vault", "folder", 0, byteArrayOf(1), "item.sph", null,
                1, 1, "hash", 1, 1, false, null, null
            ))
            val privacy = FolderPrivacyCoordinator(db)
            db.mediaItemDao().softDeleteForVault("vault", listOf("trashed"), 2)
            suspend fun concealed() = db.mediaItemDao().getItemForVault("trashed", "vault")!!.concealed
            assertEquals(false, concealed())

            privacy.setHidden("vault", "folder", true)
            assertTrue(concealed())
            assertEquals(listOf("trashed"), db.mediaItemDao().getPrivateTrashItems("vault").first().map { it.id })
            privacy.setHidden("vault", "folder", false)
            assertEquals(false, concealed())

            db.folderDao().setLockId("vault", "folder", "lock", 3)
            privacy.recompute("vault")
            assertTrue(concealed())
            privacy.setHidden("vault", "folder", true)
            db.folderDao().setLockId("vault", "folder", null, 4)
            privacy.recompute("vault")
            assertTrue(concealed())
            privacy.setHidden("vault", "folder", false)
            assertEquals(false, concealed())

            privacy.setHidden("vault", "folder", true)
            db.folderDao().deleteForVault("folder", "vault")
            privacy.recompute("vault")
            assertTrue(concealed())
        } finally { db.close() }
    }

    @Test fun concealedMediaIsAbsentFromNormalAggregatesAndTrash() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        try {
            db.vaultDao().insert(VaultEntity("vault", 0, 1, 1, byteArrayOf(1)))
            db.folderDao().insert(FolderEntity("hidden", "vault", null, byteArrayOf(1), 1, 1, null, 0,
                directHidden = true, effectiveHidden = true))
            fun media(id: String, folderId: String?, concealed: Boolean, deletedAt: Long? = null) = MediaItemEntity(
                id, "vault", folderId, 0, byteArrayOf(1), "$id.sph", null, 1, 1, id,
                1, 1, true, deletedAt, if (deletedAt != null) folderId else null, concealed = concealed
            )
            db.mediaItemDao().insert(media("public", null, false))
            db.mediaItemDao().insert(media("private", "hidden", true))
            db.mediaItemDao().insert(media("private-root-fallback", null, true))
            db.mediaItemDao().insert(media("public-trash", null, false, 2))
            db.mediaItemDao().insert(media("private-trash", null, true, 2))

            assertEquals(listOf("public"), db.mediaItemDao().getAllVisibleIdsForFilter("vault", 0))
            assertEquals(listOf("public"), db.mediaItemDao().getFavorites("vault").first().map { it.id })
            assertEquals(listOf("public"), db.mediaItemDao().getByFolderPrivileged("vault", null).first().map { it.id })
            assertEquals(listOf("public-trash"), db.mediaItemDao().getTrashItems("vault").first().map { it.id })
            assertEquals(listOf("private-trash"), db.mediaItemDao().getPrivateTrashItems("vault").first().map { it.id })
            assertTrue(db.folderDao().getSubFoldersWithCount("vault", null).first().isEmpty())
            assertEquals(listOf("hidden"), db.folderDao().getHiddenRoots("vault").first().map { it.folder.id })
        } finally { db.close() }
    }

    @Test fun folderTileCountIncludesDirectChildFoldersAndDirectMedia() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        try {
            db.vaultDao().insert(VaultEntity("vault", 0, 1, 1, byteArrayOf(1)))
            db.folderDao().insert(FolderEntity("parent", "vault", null, byteArrayOf(1), 1, 1, null, 0))
            db.folderDao().insert(FolderEntity("child", "vault", "parent", byteArrayOf(1), 2, 2, null, 0))
            db.mediaItemDao().insert(MediaItemEntity(
                "media", "vault", "parent", 0, byteArrayOf(1), "media.sph", null,
                1, 1, "hash", 1, 1, false, null, null
            ))

            val parent = db.folderDao().getSubFoldersWithCount("vault", null).first().single()
            assertEquals(2, parent.itemCount)

            db.mediaItemDao().softDeleteForVault("vault", listOf("media"), 3)
            val afterTrash = db.folderDao().getSubFoldersWithCount("vault", null).first().single()
            assertEquals(1, afterTrash.itemCount)
        } finally { db.close() }
    }

}
