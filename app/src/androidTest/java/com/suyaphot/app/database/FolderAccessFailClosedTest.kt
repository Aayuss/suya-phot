package com.suyaphot.app.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.gallery.GalleryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderAccessFailClosedTest {
    @Test fun wrongVaultMissingParentAndCycleNeverAuthorize() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val session = SessionManager(SecurityPreferences(context), CoroutineScope(Dispatchers.Unconfined))
        val access = FolderAccessManager(db, session, CoroutineScope(Dispatchers.Unconfined))
        try {
            db.vaultDao().insert(VaultEntity("real", 0, 1, 1, byteArrayOf(1)))
            db.vaultDao().insert(VaultEntity("other", 1, 1, 1, byteArrayOf(1)))
            session.unlock("real", VaultKind.REAL, SensitiveKeyHandle(ByteArray(32)),
                ByteArray(32), ByteArray(32), ByteArray(32))
            fun folder(id: String, vault: String, parent: String? = null, lock: String? = null) =
                FolderEntity(id, vault, parent, byteArrayOf(1), 1, 1, null, 0, lockId = lock)
            db.folderDao().insert(folder("valid", "real"))
            db.folderDao().insert(folder("other-folder", "other"))
            db.folderDao().insert(folder("orphan", "real", "missing"))
            db.folderDao().insert(folder("locked-orphan", "real", "missing", "lock"))
            db.folderDao().insert(folder("cycle-a", "real", "cycle-b"))
            db.folderDao().insert(folder("cycle-b", "real", "cycle-a"))
            db.folderDao().insert(folder("protected", "real", null, "protected-lock"))
            db.mediaItemDao().insert(MediaItemEntity("secret", "real", "protected", 0,
                byteArrayOf(1), "secret.sph", null, 1, 1, "secret-hash", 1, 1,
                false, null, null, concealed = true))
            assertTrue(access.canOpen("real", "valid"))
            assertFalse(access.canOpen("other", "other-folder"))
            assertFalse(access.canOpen("real", "orphan"))
            access.grantLock("real", "lock")
            assertFalse(access.canOpen("real", "locked-orphan"))
            assertFalse(access.canOpen("real", "cycle-a"))
            val guarded = GalleryRepository(db.mediaItemDao(), access)
            assertTrue(guarded.authorizedFolderIds("real", "protected").isEmpty())
            assertTrue(guarded.authorizedFolderIds("other", "other-folder").isEmpty())
            access.grantLock("real", "protected-lock")
            assertTrue(guarded.authorizedFolderIds("real", "protected") == listOf("secret"))
        } finally { db.close() }
    }
}
