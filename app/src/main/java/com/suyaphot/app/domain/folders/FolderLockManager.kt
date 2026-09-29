package com.suyaphot.app.domain.folders

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderLockEntity
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.core.model.VaultKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher

class FolderLockManager(
    private val database: SuyaDatabase,
    private val keyManager: KeyManager,
    private val sessionManager: SessionManager,
    private val accessManager: FolderAccessManager,
    private val privacyCoordinator: FolderPrivacyCoordinator,
    context: Context? = null
) {
    private data class FailureState(val attempts: Int, val wallDeadlineMs: Long, val monotonicDeadlineMs: Long)
    private val failures = HashMap<String, FailureState>()
    private val failurePrefs = context?.getSharedPreferences("folder_lock_rate_limit_v1", Context.MODE_PRIVATE)

    @Synchronized private fun failureState(lockId: String): FailureState {
        failures[lockId]?.let { return it }
        val savedAttempts = failurePrefs?.getInt("$lockId.attempts", 0) ?: 0
        val savedWall = failurePrefs?.getLong("$lockId.wallDeadline", 0L) ?: 0L
        val nowWall = System.currentTimeMillis()
        val remainingWall = (savedWall - nowWall).coerceAtLeast(0L)
        val nowMonotonic = SystemClock.elapsedRealtime()
        val monotonicUntil = if (remainingWall > 0L) nowMonotonic + remainingWall else 0L
        val saved = FailureState(savedAttempts, savedWall, monotonicUntil)
        failures[lockId] = saved
        return saved
    }

    @Synchronized private fun blocked(lockId: String): Boolean {
        val state = failureState(lockId)
        val nowWall = System.currentTimeMillis()
        val nowMonotonic = SystemClock.elapsedRealtime()
        return state.monotonicDeadlineMs > nowMonotonic || state.wallDeadlineMs > nowWall
    }

    @Synchronized private fun recordFailure(lockId: String) {
        val count = (failureState(lockId).attempts + 1).coerceAtMost(7)
        val delayMs = when {
            count < 5 -> 0L
            count == 5 -> 15_000L
            count == 6 -> 30_000L
            else -> 60_000L
        }
        val nowWall = System.currentTimeMillis()
        val nowMonotonic = SystemClock.elapsedRealtime()
        val wallUntil = nowWall + delayMs
        val monotonicUntil = nowMonotonic + delayMs
        failures[lockId] = FailureState(count, wallUntil, monotonicUntil)
        failurePrefs?.edit()
            ?.putInt("$lockId.attempts", count)
            ?.putLong("$lockId.wallDeadline", wallUntil)
            ?.commit()
    }

    @Synchronized private fun resetFailures(lockId: String) {
        failures.remove(lockId)
        failurePrefs?.edit()?.remove("$lockId.attempts")?.remove("$lockId.wallDeadline")?.commit()
    }
    private fun validateCredential(chars: CharArray, typeCode: Int) {
        require(when (typeCode) {
            0 -> chars.size in 4..12 && chars.all(Char::isDigit)
            1 -> com.suyaphot.app.domain.auth.PatternCredential.isCanonical(chars)
            else -> false
        }) { "Invalid folder credential" }
    }

    private fun recoveryAad(lockId: String) = "folder-recovery:$lockId:v1".toByteArray(Charsets.UTF_8)

    private fun recoveryEnvelope(lockId: String, token: ByteArray): ByteArray? {
        val session = sessionManager.sessionState.value as? VaultSession.Unlocked ?: return null
        if (session.kind != VaultKind.REAL) return null
        return Aead.encryptWithPrependedNonce(session.metaSubkey, token, recoveryAad(lockId))
    }

    suspend fun create(folderId: String, credential: CharArray, typeCode: Int): Boolean = withContext(Dispatchers.IO) {
        val vaultId = sessionManager.currentVaultId ?: return@withContext false
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            validateCredential(credential, typeCode)
            val lockId = UUID.randomUUID().toString()
            val envelope = keyManager.createFolderLockEnvelope(token, credential, lockId).serialize()
            val recovery = recoveryEnvelope(lockId, token)
            database.withTransaction {
                val folder = database.folderDao().getFolderForVault(folderId, vaultId) ?: return@withTransaction false
                if (folder.lockId != null || !accessManager.canOpen(vaultId, folderId)) return@withTransaction false
                val now = System.currentTimeMillis()
                database.folderLockDao().insert(
                    FolderLockEntity(lockId, vaultId, folderId, typeCode, envelope, null, null, recovery, now, now)
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

    suspend fun change(folderId: String, current: CharArray, currentType: Int, replacement: CharArray, replacementType: Int): Boolean =
        withContext(Dispatchers.IO) {
            val vaultId = sessionManager.currentVaultId ?: return@withContext false
            try {
                validateCredential(replacement, replacementType)
                val lock = database.folderLockDao().getForFolder(vaultId, folderId) ?: return@withContext false
                if (blocked(lock.id)) return@withContext false
                if (lock.credentialTypeCode != currentType) { recordFailure(lock.id); return@withContext false }
                val envelope = runCatching { KeyManager.PinEnvelope.deserialize(lock.credentialEnvelope) }.getOrNull()
                    ?: return@withContext false
                val token = keyManager.unwrapFolderLockEnvelope(envelope, current, lock.id)
                    ?: run { recordFailure(lock.id); return@withContext false }
                try {
                    val replacementEnvelope = keyManager.createFolderLockEnvelope(token, replacement, lock.id).serialize()
                    val recovery = lock.recoveryEnvelope ?: recoveryEnvelope(lock.id, token)
                    val updated = database.folderLockDao().updateCredential(
                        vaultId, lock.id, replacementEnvelope, replacementType, recovery, System.currentTimeMillis()
                    ) == 1
                    if (updated) { resetFailures(lock.id); accessManager.grantLock(vaultId, lock.id) }
                    updated
                } finally { token.fill(0) }
            } finally {
                current.fill('\u0000')
                replacement.fill('\u0000')
            }
        }

    suspend fun resetWithRecovery(folderId: String, recoveryCode: String, replacement: CharArray, replacementType: Int): Boolean =
        withContext(Dispatchers.IO) {
            val session = sessionManager.sessionState.value as? VaultSession.Unlocked ?: return@withContext false
            try {
                if (session.kind != VaultKind.REAL) return@withContext false
                validateCredential(replacement, replacementType)
                val lock = database.folderLockDao().getForFolder(session.vaultId, folderId) ?: return@withContext false
                val encryptedToken = lock.recoveryEnvelope ?: return@withContext false
                val vault = database.vaultDao().getVault(session.vaultId) ?: return@withContext false
                val recoveryBytes = vault.recoveryEnvelope ?: return@withContext false
                val envelope = runCatching { KeyManager.RecoveryEnvelope.deserialize(recoveryBytes) }.getOrNull()
                    ?: return@withContext false
                val master = keyManager.unwrapRecoveryEnvelope(envelope, recoveryCode) ?: return@withContext false
                val derivedMeta = try { VaultCrypto().deriveMetaSubkey(master) } finally { master.fill(0) }
                try {
                    if (!MessageDigest.isEqual(derivedMeta, session.metaSubkey)) return@withContext false
                    val token = runCatching { Aead.decryptWithPrependedNonce(derivedMeta, encryptedToken, recoveryAad(lock.id)) }
                        .getOrNull() ?: return@withContext false
                    try {
                        if (token.size != 32) return@withContext false
                        val newEnvelope = keyManager.createFolderLockEnvelope(token, replacement, lock.id).serialize()
                        val updated = database.folderLockDao().updateCredential(
                            session.vaultId, lock.id, newEnvelope, replacementType, encryptedToken, System.currentTimeMillis()
                        ) == 1
                        if (updated) { resetFailures(lock.id); accessManager.grantLock(session.vaultId, lock.id) }
                        updated
                    } finally { token.fill(0) }
                } finally { derivedMeta.fill(0) }
            } finally { replacement.fill('\u0000') }
        }

    suspend fun disableBiometric(lockId: String): Boolean = withContext(Dispatchers.IO) {
        val vaultId = sessionManager.currentVaultId ?: return@withContext false
        if (!accessManager.hasLockGrant(vaultId, lockId)) return@withContext false
        val disabled = database.folderLockDao().updateBiometric(vaultId, lockId, null, null, System.currentTimeMillis()) == 1
        if (disabled) keyManager.deleteBiometricKey("folder_$lockId")
        disabled
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
            try {
                if (lock.recoveryEnvelope == null) {
                    val backfilled = recoveryEnvelope(lockId, token)
                    if (backfilled != null) {
                        database.folderLockDao().updateRecovery(vaultId, lockId, backfilled, System.currentTimeMillis())
                    }
                }
            } finally {
                token.fill(0)
            }
            resetFailures(lockId)
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
            resetFailures(removedLockId)
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
            try {
                if (lock.recoveryEnvelope == null) {
                    val backfilled = recoveryEnvelope(lockId, token)
                    if (backfilled != null) {
                        database.folderLockDao().updateRecovery(vaultId, lockId, backfilled, System.currentTimeMillis())
                    }
                }
            } finally {
                token.fill(0)
            }
            accessManager.grantLock(vaultId, lockId)
            true
        } catch (_: Exception) { false }
    }
}
