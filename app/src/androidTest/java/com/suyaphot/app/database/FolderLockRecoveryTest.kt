package com.suyaphot.app.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.crypto.PepperProvider
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.folders.FolderLockManager
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class FolderLockRecoveryTest {
    @Test fun changeAndRecoveryPreserveFolderToken() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val pepper = object : PepperProvider {
            override fun hmacSha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
        }
        val keys = KeyManager(context, pepper)
        val crypto = VaultCrypto()
        val master = ByteArray(32) { 9 }
        val recoveryCode = "ABCDEFGHJKLMNPQRSTUVWXYZ23"
        val recoveryEnvelope = keys.createRecoveryEnvelope(master, recoveryCode).serialize()
        val session = SessionManager(SecurityPreferences(context), CoroutineScope(Dispatchers.Unconfined))
        val access = FolderAccessManager(db, session, CoroutineScope(Dispatchers.Unconfined))
        val manager = FolderLockManager(db, keys, session, access, FolderPrivacyCoordinator(db), context)
        try {
            db.vaultDao().insert(VaultEntity("vault", 0, 1, 4, byteArrayOf(1), recoveryEnvelope))
            db.folderDao().insert(FolderEntity("folder", "vault", null, byteArrayOf(1), 1, 1, null, 0))
            session.unlock("vault", VaultKind.REAL, SensitiveKeyHandle(master.copyOf()),
                crypto.deriveMediaSubkey(master), crypto.deriveMetaSubkey(master), crypto.deriveThumbSubkey(master))
            assertTrue(manager.create("folder", "2468".toCharArray(), 0))
            val original = db.folderLockDao().getForFolder("vault", "folder")!!
            assertTrue(original.recoveryEnvelope != null)
            val token = keys.unwrapFolderLockEnvelope(
                KeyManager.PinEnvelope.deserialize(original.credentialEnvelope), "2468".toCharArray(), original.id
            )!!
            val pattern = PatternCredential.canonicalChars(intArrayOf(0, 1, 4, 5))
            assertTrue(manager.change("folder", "2468".toCharArray(), 0, pattern.copyOf(), 1))
            val changed = db.folderLockDao().getForFolder("vault", "folder")!!
            val changedToken = keys.unwrapFolderLockEnvelope(
                KeyManager.PinEnvelope.deserialize(changed.credentialEnvelope), pattern, changed.id
            )!!
            assertTrue(changedToken.contentEquals(token))
            assertTrue(manager.resetWithRecovery("folder", recoveryCode, "1357".toCharArray(), 0))
            val reset = db.folderLockDao().getForFolder("vault", "folder")!!
            val resetToken = keys.unwrapFolderLockEnvelope(
                KeyManager.PinEnvelope.deserialize(reset.credentialEnvelope), "1357".toCharArray(), reset.id
            )!!
            assertTrue(resetToken.contentEquals(token))
            token.fill(0); changedToken.fill(0); resetToken.fill(0)
        } finally { db.close() }
    }

    @Test fun wrongAttemptsRemainBlockedAcrossManagerRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        val pepper = object : PepperProvider {
            override fun hmacSha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
        }
        val keys = KeyManager(context, pepper)
        val session = SessionManager(SecurityPreferences(context), CoroutineScope(Dispatchers.Unconfined))
        val access = FolderAccessManager(db, session, CoroutineScope(Dispatchers.Unconfined))
        val privacy = FolderPrivacyCoordinator(db)
        try {
            db.vaultDao().insert(VaultEntity("vault", 0, 1, 4, byteArrayOf(1)))
            db.folderDao().insert(FolderEntity("folder", "vault", null, byteArrayOf(1), 1, 1, null, 0))
            session.unlock("vault", VaultKind.REAL, SensitiveKeyHandle(ByteArray(32)),
                ByteArray(32), ByteArray(32), ByteArray(32))
            val first = FolderLockManager(db, keys, session, access, privacy, context)
            assertTrue(first.create("folder", "2468".toCharArray(), 0))
            val lockId = db.folderLockDao().getForFolder("vault", "folder")!!.id
            repeat(5) { assertFalse(first.unlock(lockId, "0000".toCharArray(), 0)) }
            val restarted = FolderLockManager(db, keys, session, access, privacy, context)
            assertFalse(restarted.unlock(lockId, "2468".toCharArray(), 0))
        } finally { db.close() }
    }
}
