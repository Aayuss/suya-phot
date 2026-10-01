package com.suyaphot.app.folders

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderCountTest {

    private lateinit var db: SuyaDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SuyaDatabase::class.java
        ).allowMainThreadQueries().build()

        runBlocking {
            db.vaultDao().insert(
                VaultEntity(
                    id = "v",
                    kindCode = 0,
                    createdAt = 1L,
                    schemaVersion = 6,
                    pinEnvelope = byteArrayOf(1)
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun folder(
        id: String,
        parentId: String?,
        hidden: Boolean = false
    ) = FolderEntity(
        id = id,
        vaultId = "v",
        parentId = parentId,
        encryptedName = byteArrayOf(1, 2, 3),
        createdAt = 1L,
        updatedAt = 1L,
        coverMediaId = null,
        sortOrder = 0L,
        directHidden = hidden && parentId == null,
        effectiveHidden = hidden
    )

    @Test
    fun visibleFolderCountsImmediateSubfolderAsAnItem() = runBlocking {
        db.folderDao().insert(folder("parent", null))
        db.folderDao().insert(folder("child", "parent"))

        val roots = db.folderDao().getSubFoldersWithCount("v", null).first()
        val parent = roots.single { it.folder.id == "parent" }

        assertEquals(1, parent.itemCount)
    }

    @Test
    fun hiddenHierarchyCountsSubfoldersAndMedia() = runBlocking {
        db.folderDao().insert(folder("hidden-parent", null, hidden = true))
        db.folderDao().insert(folder("hidden-child", "hidden-parent", hidden = true))

        db.mediaItemDao().insert(
            MediaItemEntity(
                id = "m1",
                vaultId = "v",
                folderId = "hidden-parent",
                mediaTypeCode = 0,
                encryptedMetadata = byteArrayOf(1),
                encryptedFileRelativePath = "m1.sph",
                encryptedThumbRelativePath = null,
                plaintextSize = 1L,
                cipherSize = 1L,
                sha256Hex = "00".repeat(32),
                importedAt = 1L,
                updatedAt = 1L,
                favorite = false,
                deletedAt = null,
                previousFolderId = null,
                concealed = true
            )
        )

        val roots = db.folderDao().getHiddenRoots("v").first()
        val parent = roots.single { it.folder.id == "hidden-parent" }
        assertEquals(2, parent.itemCount)

        val children = db.folderDao().getAllSubFoldersWithCount("v", "hidden-parent").first()
        assertEquals(0, children.single().itemCount)
    }
}
