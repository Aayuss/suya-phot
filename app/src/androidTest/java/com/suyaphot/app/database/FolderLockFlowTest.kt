package com.suyaphot.app.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.crypto.PepperProvider
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.folders.FolderLockManager
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class FolderLockFlowTest {
    @Test fun folderPinConcealsMediaAndRelocksWhenLeaving() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val session = SessionManager(SecurityPreferences(context), testScope)
        val access = FolderAccessManager(db, session, testScope)
        try {
            db.vaultDao().insert(VaultEntity("test-vault", 0, 1, 1, byteArrayOf(1)))
            db.folderDao().insert(FolderEntity("protected", "test-vault", null, byteArrayOf(1), 1, 1, null, 0))
            db.mediaItemDao().insert(MediaItemEntity(
                "photo", "test-vault", "protected", 0, byteArrayOf(1), "photo.sph", null,
                1, 1, "hash", 1, 1, false, null, null
            ))
            session.unlock(
                "test-vault", VaultKind.REAL, SensitiveKeyHandle(ByteArray(32) { 1 }),
                ByteArray(32) { 2 }, ByteArray(32) { 3 }, ByteArray(32) { 4 }
            )
            val pepper = object : PepperProvider {
                override fun hmacSha256(input: ByteArray): ByteArray =
                    MessageDigest.getInstance("SHA-256").digest(input)
            }
            val manager = FolderLockManager(db, KeyManager(context, pepper), session, access, FolderPrivacyCoordinator(db))
            assertTrue(manager.create("protected", "2468".toCharArray(), 0))
            val lockId = db.folderDao().getFolderForVault("protected", "test-vault")?.lockId
            assertNotNull(lockId)
            assertTrue(db.mediaItemDao().getAllVisibleIdsForFilter("test-vault", 0).isEmpty())
            assertFalse(access.canOpen("test-vault", "protected"))
            assertFalse(manager.unlock(lockId!!, "1111".toCharArray(), 0))
            assertFalse(access.canOpen("test-vault", "protected"))
            assertTrue(manager.unlock(lockId, "2468".toCharArray(), 0))
            assertTrue(access.canOpen("test-vault", "protected"))
            access.retainLocksForFolder("test-vault", null)
            assertFalse(access.canOpen("test-vault", "protected"))
        } finally {
            session.lock(com.suyaphot.app.domain.auth.LockReason.Explicit)
            db.close()
        }
    }
}
