package com.suyaphot.app.domain.folders

import android.os.SystemClock
import androidx.room.withTransaction
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderLockEntity
import com.suyaphot.app.domain.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher

class FolderLockManager(
    private val database: SuyaDatabase,
    private val keyManager: KeyManager,
    private val sessionManager: SessionManager,
    private val accessManager: FolderAccessManager,
    private val privacyCoordinator: FolderPrivacyCoordinator
) {
    private data class FailureState(val attempts: Int, val blockedUntil: Long)
    private val failures = HashMap<String, FailureState>()

    @Synchronized private fun blocked(lockId: String): Boolean =
        (failures[lockId]?.blockedUntil ?: 0L) > SystemClock.elapsedRealtime()

    @Synchronized private fun recordFailure(lockId: String) {
        val count = (failures[lockId]?.attempts ?: 0) + 1
        val delayMs = when {
            count < 5 -> 0L
            count == 5 -> 15_000L
            count == 6 -> 30_000L
            else -> 60_000L
        }
        failures[lockId] = FailureState(count, SystemClock.elapsedRealtime() + delayMs)
    }
    private fun validateCredential(chars: CharArray, typeCode: Int) {
        require(when (typeCode) {
            0 -> chars.size in 4..12 && chars.all(Char::isDigit)
            1 -> chars.size in 6..11 && chars[0] == 'P' && chars[1] == ':'
            else -> false
        }) { "Invalid folder credential" }
    }

    suspend fun create(folderId: String, credential: CharArray, typeCode: Int): Boolean = withContext(Dispatchers.IO) {
        val vaultId = sessionManager.currentVaultId ?: return@withContext false
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            validateCredential(credential, typeCode)
            val lockId = UUID.randomUUID().toString()
            val envelope = keyManager.createFolderLockEnvelope(token, credential, lockId).serialize()
            database.withTransaction {
                val folder = database.folderDao().getFolderForVault(folderId, vaultId) ?: return@withTransaction false
                if (folder.lockId != null || !accessManager.canOpen(vaultId, folderId)) return@withTransaction false
                val now = System.currentTimeMillis()
                database.folderLockDao().insert(
                    FolderLockEntity(lockId, vaultId, folderId, typeCode, envelope, null, null, 0, now, now)
                )
                check(database.folderDao().setLockId(vaultId, folderId, lockId, now) == 1)
                privacyCoordinator.recomputeInsideTransaction(vaultId)
                true
            }
        } finally {
            token.fill(0)
            credential.fill('\u0000')
        }
    }

    suspend fun unlock(lockId: String, credential: CharArray, typeCode: Int): Boolean = withContext(Dispatchers.IO) {
        val vaultId = sessionManager.currentVaultId ?: return@withContext false
        try {
            if (blocked(lockId)) return@withContext false
            val lock = database.folderLockDao().getForVault(vaultId, lockId) ?: return@withContext false
            if (lock.credentialTypeCode != typeCode) { recordFailure(lockId); return@withContext false }
            val envelope = runCatching { KeyManager.PinEnvelope.deserialize(lock.credentialEnvelope) }.getOrNull()
                ?: return@withContext false
            val token = keyManager.unwrapFolderLockEnvelope(envelope, credential, lock.id)
                ?: run { recordFailure(lockId); return@withContext false }
            token.fill(0)
            synchronized(this@FolderLockManager) { failures.remove(lockId) }
            accessManager.grantLock(vaultId, lockId)
            true
        } finally {
            credential.fill('\u0000')
        }
    }

    suspend fun remove(folderId: String): Boolean = withContext(Dispatchers.IO) {
        val vaultId = sessionManager.currentVaultId ?: return@withContext false
        val removedLockId = database.withTransaction {
            val lock = database.folderLockDao().getForFolder(vaultId, folderId) ?: return@withTransaction null
            if (!accessManager.hasLockGrant(vaultId, lock.id)) return@withTransaction null
            check(database.folderDao().setLockId(vaultId, folderId, null, System.currentTimeMillis()) == 1)
            check(database.folderLockDao().delete(vaultId, lock.id) == 1)
            privacyCoordinator.recomputeInsideTransaction(vaultId)
            accessManager.revokeLock(lock.id)
            lock.id
        }
        if (removedLockId != null) {
            // Keystore key is no longer needed after the DB lock is removed.
            keyManager.deleteBiometricKey("folder_$removedLockId")
        }
        removedLockId != null
    }

    suspend fun tokenForBiometricEnrollment(lockId: String, credential: CharArray, typeCode: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val vaultId = sessionManager.currentVaultId ?: return@withContext null
            try {
                val lock = database.folderLockDao().getForVault(vaultId, lockId) ?: return@withContext null
                if (lock.credentialTypeCode != typeCode || !accessManager.hasLockGrant(vaultId, lockId)) return@withContext null
                val envelope = runCatching { KeyManager.PinEnvelope.deserialize(lock.credentialEnvelope) }.getOrNull()
                    ?: return@withContext null
                keyManager.unwrapFolderLockEnvelope(envelope, credential, lockId)
            } finally { credential.fill('\u0000') }
        }

    suspend fun saveBiometricEnvelope(lockId: String, envelope: ByteArray, iv: ByteArray): Boolean {
        val vaultId = sessionManager.currentVaultId ?: return false
        if (!accessManager.hasLockGrant(vaultId, lockId)) return false
        return database.folderLockDao().updateBiometric(vaultId, lockId, envelope, iv, System.currentTimeMillis()) == 1
    }

    suspend fun unlockWithBiometric(lockId: String, cipher: Cipher): Boolean = withContext(Dispatchers.IO) {
        val vaultId = sessionManager.currentVaultId ?: return@withContext false
        val lock = database.folderLockDao().getForVault(vaultId, lockId) ?: return@withContext false
        val envelope = lock.biometricEnvelope ?: return@withContext false
        try {
            val token = cipher.doFinal(envelope)
            if (token.size != 32) { token.fill(0); return@withContext false }
            token.fill(0)
            accessManager.grantLock(vaultId, lockId)
            true
        } catch (_: Exception) { false }
    }
}
