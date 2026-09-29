package com.suyaphot.app.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyQueriesTest {
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
            assertEquals(listOf("public"), db.mediaItemDao().getByFolder("vault", null).first().map { it.id })
            assertEquals(listOf("public-trash"), db.mediaItemDao().getTrashItems("vault").first().map { it.id })
            assertEquals(listOf("private-trash"), db.mediaItemDao().getPrivateTrashItems("vault").first().map { it.id })
            assertTrue(db.folderDao().getSubFoldersWithCount("vault", null).first().isEmpty())
            assertEquals(listOf("hidden"), db.folderDao().getHiddenRoots("vault").first().map { it.folder.id })
        } finally { db.close() }
    }
}
