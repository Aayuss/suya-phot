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
     * Checks if a candidate PIN matches the real vault PIN (Section 29).
     */
    suspend fun isSameAsRealPin(candidate: CharArray): Boolean {
        val real = vaultDao.getVaultByKind(VaultKind.REAL.code) ?: return false
        val envelope = KeyManager.PinEnvelope.deserialize(real.pinEnvelope)
        val key = keyManager.unwrapPinEnvelope(envelope, candidate.copyOf())
        return if (key != null) {
            key.fill(0)
            true
        } else {
            false
        }
    }

    /**
     * Authenticates with a PIN.
     * Evaluates both real and secondary vaults to prevent timing attacks (Section 30).
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

        val realVault = allVaults.firstOrNull { it.kindCode == VaultKind.REAL.code }
        val secondaryVault = allVaults.firstOrNull { it.kindCode == VaultKind.SECONDARY.code }

        val realEnvelope = realVault?.let { KeyManager.PinEnvelope.deserialize(it.pinEnvelope) }
        val secondaryEnvelope = secondaryVault?.let { KeyManager.PinEnvelope.deserialize(it.pinEnvelope) }

        // Both unwrap attempts run to mitigate timing discrepancy
        val realResult = realEnvelope?.let { keyManager.unwrapPinEnvelope(it, pinChars.copyOf()) }
        val secondaryResult = secondaryEnvelope?.let { keyManager.unwrapPinEnvelope(it, pinChars.copyOf()) }

        return when {
            realResult != null -> {
                secondaryResult?.fill(0)
                preferences.resetFailedAttempts()
                establishSession(realVault!!.id, VaultKind.REAL, realResult)
                AuthResult.Success(realVault.id, VaultKind.REAL)
            }
            secondaryResult != null -> {
                preferences.resetFailedAttempts()
                establishSession(secondaryVault!!.id, VaultKind.SECONDARY, secondaryResult)
                AuthResult.Success(secondaryVault.id, VaultKind.SECONDARY)
            }
            else -> {
                val currentAttempts = preferences.failedAttempts.first() + 1
                val lockoutDuration = calculateLockoutMs(currentAttempts)
                val newLockoutUntil = if (lockoutDuration > 0) now + lockoutDuration else 0L
                preferences.recordFailedAttempt(newLockoutUntil)
                SafeLog.d("PinAuthenticator", "Authentication failed. Attempt count: $currentAttempts")
                AuthResult.IncorrectPin(currentAttempts, lockoutDuration)
            }
        }
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
        val recoveryEnvelopeBytes = realVault.recoveryEnvelope ?: return false
        val envelope = KeyManager.RecoveryEnvelope.deserialize(recoveryEnvelopeBytes)
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
