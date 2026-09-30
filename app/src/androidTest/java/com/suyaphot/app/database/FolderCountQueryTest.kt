package com.suyaphot.app.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderCountQueryTest {

    @Test
    fun parentFolderCountsImmediateSubfolderAsItem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.folderDao()
            dao.insert(
                FolderEntity(
                    id = "parent",
                    vaultId = "vault",
                    parentId = null,
                    encryptedName = byteArrayOf(1),
                    createdAt = 1L,
                    updatedAt = 1L,
                    coverMediaId = null,
                    sortOrder = 0L
                )
            )
            dao.insert(
                FolderEntity(
                    id = "child",
                    vaultId = "vault",
                    parentId = "parent",
                    encryptedName = byteArrayOf(2),
                    createdAt = 2L,
                    updatedAt = 2L,
                    coverMediaId = null,
                    sortOrder = 0L
                )
            )

            val root = dao.getSubFoldersWithCount("vault", null).first()
            assertEquals(1, root.size)
            assertEquals("parent", root.single().folder.id)
            assertEquals(1, root.single().itemCount)
        } finally {
            db.close()
        }
    }
}
