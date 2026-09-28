package com.suyaphot.app.domain.auth

import android.os.SystemClock
import com.suyaphot.app.core.crypto.KeyManager
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.dao.VaultDao
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.core.util.SafeLog
import kotlinx.coroutines.flow.first
import javax.crypto.Cipher

sealed interface AuthResult {
    data class Success(val vaultId: String, val kind: VaultKind) : AuthResult
    data class IncorrectPin(val attempts: Int, val lockoutRemainingMs: Long) : AuthResult
    data class LockedOut(val lockoutRemainingMs: Long) : AuthResult
    data class Error(val message: String) : AuthResult
}

/**
 * Handles PIN, Biometric, and Recovery authentication logic and rate limiting.
 */
class PinAuthenticator(
    private val vaultDao: VaultDao,
    private val keyManager: KeyManager,
    private val vaultCrypto: VaultCrypto,
    private val sessionManager: SessionManager,
    private val preferences: SecurityPreferences
) {

    companion object {
        fun calculateLockoutMs(attempts: Int): Long {
            return when {
                attempts < 5 -> 0L
                attempts == 5 -> 15_000L
                attempts == 6 -> 30_000L
                attempts == 7 -> 60_000L
                else -> 300_000L // 5 minutes max
            }
        }
    }

    /**
     * Authenticates with a PIN.
     * Attempts to unlock the real vault first. If it fails, attempts the secondary vault.
     */
    suspend fun authenticateWithPin(pinChars: CharArray): AuthResult {
        val now = System.currentTimeMillis()
        val lockoutUntil = preferences.lockoutUntilTimestamp.first()
        if (now < lockoutUntil) {
            return AuthResult.LockedOut(lockoutUntil - now)
        }

        val allVaults = vaultDao.getAllVaults()
        if (allVaults.isEmpty()) {
            return AuthResult.Error("No vault configured")
        }

        // 1. Try real vault
        val realVault = allVaults.firstOrNull { it.kindCode == VaultKind.REAL.code }
        if (realVault != null) {
            val pinEnvelope = KeyManager.PinEnvelope.deserialize(realVault.pinEnvelope)
            val masterKey = keyManager.unwrapPinEnvelope(pinEnvelope, pinChars)
            if (masterKey != null) {
                preferences.resetFailedAttempts()
                establishSession(realVault.id, VaultKind.REAL, masterKey)
                return AuthResult.Success(realVault.id, VaultKind.REAL)
            }
        }

        // 2. Try secondary/decoy vault
        val secondaryVault = allVaults.firstOrNull { it.kindCode == VaultKind.SECONDARY.code }
        if (secondaryVault != null) {
            val pinEnvelope = KeyManager.PinEnvelope.deserialize(secondaryVault.pinEnvelope)
            val masterKey = keyManager.unwrapPinEnvelope(pinEnvelope, pinChars)
            if (masterKey != null) {
                // Successful decoy auth is NOT an intruder attempt
                preferences.resetFailedAttempts()
                establishSession(secondaryVault.id, VaultKind.SECONDARY, masterKey)
                return AuthResult.Success(secondaryVault.id, VaultKind.SECONDARY)
            }
        }

        // 3. Failed authentication
        val currentAttempts = preferences.failedAttempts.first() + 1
        val lockoutDuration = calculateLockoutMs(currentAttempts)
        val newLockoutUntil = if (lockoutDuration > 0) now + lockoutDuration else 0L
        preferences.recordFailedAttempt(newLockoutUntil)

        SafeLog.d("PinAuthenticator", "Authentication failed. Attempt count: $currentAttempts")
        return AuthResult.IncorrectPin(currentAttempts, lockoutDuration)
    }

    /**
     * Authenticates using a biometric cipher unwrap.
     */
    suspend fun authenticateWithBiometric(cipher: Cipher): AuthResult {
        val realVault = vaultDao.getVaultByKind(VaultKind.REAL.code)
            ?: return AuthResult.Error("Real vault not found")

        val biometricEnvelope = realVault.biometricEnvelope
            ?: return AuthResult.Error("Biometric not enrolled for this vault")

        return try {
            val masterKey = cipher.doFinal(biometricEnvelope)
            preferences.resetFailedAttempts()
            establishSession(realVault.id, VaultKind.REAL, masterKey)
            AuthResult.Success(realVault.id, VaultKind.REAL)
        } catch (e: Exception) {
            SafeLog.e("PinAuthenticator", "Biometric unwrap failed", e)
            AuthResult.Error("Biometric authentication failed")
        }
    }

    /**
     * Unwraps master key via recovery code and sets a new PIN.
     */
    suspend fun recoverWithCode(recoveryCodeInput: String, newPinChars: CharArray): Boolean {
        val realVault = vaultDao.getVaultByKind(VaultKind.REAL.code) ?: return false
        val envelope = KeyManager.RecoveryEnvelope.deserialize(realVault.recoveryEnvelope)
        val masterKey = keyManager.unwrapRecoveryEnvelope(envelope, recoveryCodeInput) ?: return false

        try {
            // Re-wrap master key with new PIN
            val newPinEnvelope = keyManager.createPinEnvelope(masterKey, newPinChars)
            vaultDao.updatePinEnvelope(realVault.id, newPinEnvelope.serialize())
            preferences.resetFailedAttempts()
            establishSession(realVault.id, VaultKind.REAL, masterKey)
            return true
        } catch (e: Exception) {
            SafeLog.e("PinAuthenticator", "Recovery reset failed", e)
            return false
        }
    }

    private fun establishSession(vaultId: String, kind: VaultKind, masterKey: ByteArray) {
        val mediaSubkey = vaultCrypto.deriveMediaSubkey(masterKey)
        val metaSubkey = vaultCrypto.deriveMetaSubkey(masterKey)
        val thumbSubkey = vaultCrypto.deriveThumbSubkey(masterKey)
        val handle = SensitiveKeyHandle(masterKey)

        sessionManager.unlock(
            vaultId = vaultId,
            kind = kind,
            masterKeyHandle = handle,
            mediaSubkey = mediaSubkey,
            metaSubkey = metaSubkey,
            thumbSubkey = thumbSubkey
        )
    }
}
