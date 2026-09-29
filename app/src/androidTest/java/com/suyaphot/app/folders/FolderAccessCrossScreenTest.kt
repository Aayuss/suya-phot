package com.suyaphot.app.folders

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.FolderLockEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.folders.FolderAccessRequirement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderAccessCrossScreenTest {

    private lateinit var db: SuyaDatabase
    private lateinit var session: SessionManager
    private lateinit var access: FolderAccessManager
    private val vaultId = "test_vault_access"

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SuyaDatabase::class.java).build()
        session = SessionManager(SecurityPreferences(context), CoroutineScope(Dispatchers.Unconfined))
        access = FolderAccessManager(db, session, CoroutineScope(Dispatchers.Unconfined))

        db.vaultDao().insert(
            VaultEntity(
                id = vaultId,
                kindCode = 0,
                createdAt = 1000L,
                schemaVersion = 6,
                pinEnvelope = ByteArray(16),
                recoveryEnvelope = null,
                biometricEnvelope = null,
                biometricIv = null,
                credentialTypeCode = 0
            )
        )

        session.unlock(
            vaultId = vaultId,
            kind = VaultKind.REAL,
            masterKeyHandle = SensitiveKeyHandle(ByteArray(32)),
            mediaSubkey = ByteArray(32),
            metaSubkey = ByteArray(32),
            thumbSubkey = ByteArray(32)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertFolder(
        id: String,
        parentId: String? = null,
        lockId: String? = null,
        directHidden: Boolean = false,
        effectiveHidden: Boolean = false,
        effectiveProtected: Boolean = false
    ) = runBlocking {
        db.folderDao().insert(
            FolderEntity(
                id = id,
                vaultId = vaultId,
                parentId = parentId,
                encryptedName = byteArrayOf(1, 2, 3),
                createdAt = 1000L,
                updatedAt = 1000L,
                coverMediaId = null,
                sortOrder = 0L,
                directHidden = directHidden,
                effectiveHidden = effectiveHidden,
                lockId = lockId,
                effectiveProtected = effectiveProtected
            )
        )
    }

    private fun insertLock(
        lockId: String,
        folderId: String,
        requiresReset: Boolean = false
    ) = runBlocking {
        db.folderLockDao().insert(
            FolderLockEntity(
                id = lockId,
                vaultId = vaultId,
                folderId = folderId,
                credentialTypeCode = 0,
                credentialEnvelope = ByteArray(32),
                biometricEnvelope = null,
                biometricIv = null,
                recoveryEnvelope = ByteArray(32),
                createdAt = 1000L,
                updatedAt = 1000L,
                requiresCredentialReset = requiresReset
            )
        )
    }

    @Test
    fun testNormalFolderRequirementIsGranted() = runBlocking {
        insertFolder("f_normal")
        val req = access.nextRequirement(vaultId, "f_normal")
        assertEquals(FolderAccessRequirement.Granted, req)
    }

    @Test
    fun testHiddenFolderRequiresHiddenVaultAuthThenGranted() = runBlocking {
        insertFolder("f_hidden", directHidden = true, effectiveHidden = true)

        val initialReq = access.nextRequirement(vaultId, "f_hidden")
        assertEquals(FolderAccessRequirement.HiddenVaultAuth, initialReq)

        access.grantHidden(vaultId)

        val grantedReq = access.nextRequirement(vaultId, "f_hidden")
        assertEquals(FolderAccessRequirement.Granted, grantedReq)
    }

    @Test
    fun testRestoredProtectedFolderRequiresRecoveryReset() = runBlocking {
        insertFolder("f_restored", lockId = "lock_restored", effectiveProtected = true)
        insertLock("lock_restored", "f_restored", requiresReset = true)

        val req = access.nextRequirement(vaultId, "f_restored")
        assertTrue(req is FolderAccessRequirement.RecoveryReset)
        val resetReq = req as FolderAccessRequirement.RecoveryReset
        assertEquals("lock_restored", resetReq.lockId)
        assertEquals("f_restored", resetReq.folderId)
    }

    @Test
    fun testLockedFolderRequiresCredentialThenGranted() = runBlocking {
        insertFolder("f_locked", lockId = "lock_normal", effectiveProtected = true)
        insertLock("lock_normal", "f_locked", requiresReset = false)

        val req = access.nextRequirement(vaultId, "f_locked")
        assertTrue(req is FolderAccessRequirement.Credential)
        val credReq = req as FolderAccessRequirement.Credential
        assertEquals("lock_normal", credReq.lockId)

        // Grant lock
        access.grantLock(vaultId, "lock_normal")
        val grantedReq = access.nextRequirement(vaultId, "f_locked")
        assertEquals(FolderAccessRequirement.Granted, grantedReq)
    }

    @Test
    fun testNestedFolderHierarchyEvaluatesParentBeforeChild() = runBlocking {
        insertFolder("parent_locked", lockId = "lock_parent", effectiveProtected = true)
        insertLock("lock_parent", "parent_locked", requiresReset = false)

        insertFolder("child_locked", parentId = "parent_locked", lockId = "lock_child", effectiveProtected = true)
        insertLock("lock_child", "child_locked", requiresReset = false)

        // Target is child, but parent lock must be demanded first!
        val req1 = access.nextRequirement(vaultId, "child_locked")
        assertTrue(req1 is FolderAccessRequirement.Credential)
        assertEquals("lock_parent", (req1 as FolderAccessRequirement.Credential).lockId)

        // Unlock parent
        access.grantLock(vaultId, "lock_parent")

        // Next call targets child lock
        val req2 = access.nextRequirement(vaultId, "child_locked")
        assertTrue(req2 is FolderAccessRequirement.Credential)
        assertEquals("lock_child", (req2 as FolderAccessRequirement.Credential).lockId)

        // Unlock child
        access.grantLock(vaultId, "lock_child")

        // Finally granted
        val req3 = access.nextRequirement(vaultId, "child_locked")
        assertEquals(FolderAccessRequirement.Granted, req3)
    }
}
