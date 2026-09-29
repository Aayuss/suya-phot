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
import java.security.MessageDigest
import javax.crypto.Cipher

sealed interface AuthResult {
    data class Success(val vaultId: String, val kind: VaultKind) : AuthResult
    data class IncorrectPin(val attempts: Int, val lockoutRemainingMs: Long) : AuthResult
    data class LockedOut(val lockoutRemainingMs: Long) : AuthResult
    data class Error(val message: String) : AuthResult
}

sealed interface ChangeSecondaryPinResult {
    data object Success : ChangeSecondaryPinResult
    data object IncorrectCurrentPin : ChangeSecondaryPinResult
    data object SameAsRealPin : ChangeSecondaryPinResult
    data object InvalidNewPin : ChangeSecondaryPinResult
    data class Error(val safeCode: String) : ChangeSecondaryPinResult
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

    @Volatile
    private var monotonicLockoutDeadlineMs: Long = 0L
    private var reauthFailures = 0
    private var reauthBlockedUntil = 0L

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
        val candidateCopy = candidate.copyOf()
        return try {
            val real = vaultDao.getVaultByKind(VaultKind.REAL.code) ?: return false
            if (real.credentialTypeCode != 0) return false
            val envelope = runCatching { KeyManager.PinEnvelope.deserialize(real.pinEnvelope) }.getOrNull()
                ?: return false
            val key = keyManager.unwrapPinEnvelope(envelope, candidateCopy)
            key?.fill(0)
            key != null
        } finally {
            candidateCopy.fill('\u0000')
        }
    }

    suspend fun changeSecondaryPin(
        currentPin: CharArray,
        newPin: CharArray
    ): ChangeSecondaryPinResult {
        val currentCopy = currentPin.copyOf()
        val newCopy = newPin.copyOf()
        try {
            if (newCopy.size != 6 || newCopy.any { !it.isDigit() }) {
                return ChangeSecondaryPinResult.InvalidNewPin
            }
            if (isSameAsRealPin(newCopy)) {
                return ChangeSecondaryPinResult.SameAsRealPin
            }
            val secondary = vaultDao.getVaultByKind(VaultKind.SECONDARY.code)
                ?: return ChangeSecondaryPinResult.Error("SECONDARY_NOT_FOUND")
            val envelope = runCatching { KeyManager.PinEnvelope.deserialize(secondary.pinEnvelope) }.getOrNull()
                ?: return ChangeSecondaryPinResult.Error("SECONDARY_ENVELOPE_INVALID")
            val existingMasterKey = keyManager.unwrapPinEnvelope(envelope, currentCopy)
                ?: return ChangeSecondaryPinResult.IncorrectCurrentPin
            try {
                val replacement = keyManager.createPinEnvelope(existingMasterKey, newCopy)
                vaultDao.updatePinEnvelope(secondary.id, replacement.serialize())
                return ChangeSecondaryPinResult.Success
            } catch (e: Exception) {
                SafeLog.e("PinAuthenticator", "Secondary PIN rewrap failed", e)
                return ChangeSecondaryPinResult.Error("SECONDARY_PIN_UPDATE_FAILED")
            } finally {
                existingMasterKey.fill(0)
            }
        } finally {
            currentCopy.fill('\u0000')
            newCopy.fill('\u0000')
            currentPin.fill('\u0000')
            newPin.fill('\u0000')
        }
    }

    /**
     * Authenticates with a PIN.
     * Evaluates both real and secondary vaults to prevent timing attacks (Section 30).
     */
    suspend fun authenticateWithPin(pinChars: CharArray): AuthResult {
        return authenticateWithCredential(pinChars, 0)
    }

    suspend fun authenticateWithPattern(nodes: IntArray): AuthResult {
        val credential = try {
            PatternCredential.canonicalChars(nodes)
        } catch (_: IllegalArgumentException) {
            return AuthResult.Error("Pattern needs at least four nodes")
        }
        nodes.fill(-1)
        return authenticateWithCredential(credential, 1)
    }

    suspend fun verifyCurrentCredential(chars: CharArray, typeCode: Int): Boolean {
        return try {
            val session = sessionManager.sessionState.value as? VaultSession.Unlocked ?: return false
            if (SystemClock.elapsedRealtime() < reauthBlockedUntil) return false
            val vault = vaultDao.getVault(session.vaultId) ?: return false
            if (vault.credentialTypeCode != typeCode) return false
            val envelope = runCatching { KeyManager.PinEnvelope.deserialize(vault.pinEnvelope) }.getOrNull() ?: return false
            val key = keyManager.unwrapPinEnvelope(envelope, chars)
            key?.fill(0)
            if (key == null) {
                reauthFailures++
                if (reauthFailures >= 5) reauthBlockedUntil = SystemClock.elapsedRealtime() + 30_000L
            } else {
                reauthFailures = 0
                reauthBlockedUntil = 0L
            }
            key != null
        } finally {
            chars.fill('\u0000')
        }
    }

    suspend fun changeCurrentCredential(current: CharArray, currentType: Int, replacement: CharArray, replacementType: Int): Boolean {
        return try {
            val session = sessionManager.sessionState.value as? VaultSession.Unlocked ?: return false
            val vault = vaultDao.getVault(session.vaultId) ?: return false
            if (vault.credentialTypeCode != currentType || replacementType !in 0..1) return false
            val oldEnvelope = runCatching { KeyManager.PinEnvelope.deserialize(vault.pinEnvelope) }.getOrNull() ?: return false
            val master = keyManager.unwrapPinEnvelope(oldEnvelope, current) ?: return false
            try {
                val newEnvelope = keyManager.createPinEnvelope(master, replacement)
                vaultDao.updateCredential(vault.id, newEnvelope.serialize(), replacementType) == 1
            } finally { master.fill(0) }
        } finally {
            current.fill('\u0000')
            replacement.fill('\u0000')
        }
    }

    private suspend fun authenticateWithCredential(pinChars: CharArray, typeCode: Int): AuthResult {
        val now = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        val monotonicRemaining = (monotonicLockoutDeadlineMs - elapsedNow).coerceAtLeast(0L)
        if (monotonicRemaining > 0L) {
            pinChars.fill('\u0000')
            return AuthResult.LockedOut(monotonicRemaining)
        }
        val lockoutUntil = preferences.lockoutUntilTimestamp.first()
        if (now < lockoutUntil) {
            pinChars.fill('\u0000')
            return AuthResult.LockedOut(lockoutUntil - now)
        }

        val allVaults = vaultDao.getAllVaults()
        if (allVaults.isEmpty()) {
            return AuthResult.Error("No vault configured")
        }

        val realVault = allVaults.firstOrNull { it.kindCode == VaultKind.REAL.code }
        val secondaryVault = allVaults.firstOrNull { it.kindCode == VaultKind.SECONDARY.code }

        val realEnvelope = realVault?.let { runCatching { KeyManager.PinEnvelope.deserialize(it.pinEnvelope) }.getOrNull() }
        val secondaryEnvelope = secondaryVault?.let { runCatching { KeyManager.PinEnvelope.deserialize(it.pinEnvelope) }.getOrNull() }

        // Both unwrap attempts run to mitigate timing discrepancy
        val realPinCopy = pinChars.copyOf()
        val secondaryPinCopy = pinChars.copyOf()
        val realResult = try {
            realEnvelope?.let { keyManager.unwrapPinEnvelope(it, realPinCopy) }
        } finally {
            realPinCopy.fill('\u0000')
        }
        val secondaryResult = try {
            secondaryEnvelope?.let { keyManager.unwrapPinEnvelope(it, secondaryPinCopy) }
        } finally {
            secondaryPinCopy.fill('\u0000')
            pinChars.fill('\u0000')
        }

        return when {
            realResult != null && realVault?.credentialTypeCode == typeCode -> {
                secondaryResult?.fill(0)
                preferences.resetFailedAttempts()
                establishSession(realVault!!.id, VaultKind.REAL, realResult)
                AuthResult.Success(realVault.id, VaultKind.REAL)
            }
            secondaryResult != null && secondaryVault?.credentialTypeCode == typeCode -> {
                preferences.resetFailedAttempts()
                establishSession(secondaryVault!!.id, VaultKind.SECONDARY, secondaryResult)
                AuthResult.Success(secondaryVault.id, VaultKind.SECONDARY)
            }
            else -> {
                realResult?.fill(0)
                secondaryResult?.fill(0)
                val currentAttempts = preferences.failedAttempts.first() + 1
                val lockoutDuration = calculateLockoutMs(currentAttempts)
                val newLockoutUntil = if (lockoutDuration > 0) now + lockoutDuration else 0L
                monotonicLockoutDeadlineMs = if (lockoutDuration > 0) elapsedNow + lockoutDuration else 0L
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

    /** Re-authenticates the already-open vault without switching vaults or resetting its session. */
    suspend fun verifyCurrentBiometric(cipher: Cipher): Boolean {
        val active = sessionManager.sessionState.value as? VaultSession.Unlocked ?: return false
        val vault = vaultDao.getVault(active.vaultId) ?: return false
        val envelope = vault.biometricEnvelope ?: return false
        val master = try { cipher.doFinal(envelope) } catch (_: Exception) { return false }
        return try {
            val derived = vaultCrypto.deriveMediaSubkey(master)
            try {
                sessionManager.currentVaultId == active.vaultId && MessageDigest.isEqual(derived, active.mediaSubkey)
            } finally { derived.fill(0) }
        } finally { master.fill(0) }
    }

    /**
     * Unwraps master key via recovery code and sets a new PIN.
     */
    suspend fun recoverWithCode(recoveryCodeInput: String, newPinChars: CharArray): Boolean {
        val realVault = vaultDao.getVaultByKind(VaultKind.REAL.code) ?: return false
        val recoveryEnvelopeBytes = realVault.recoveryEnvelope ?: return false
        val envelope = runCatching { KeyManager.RecoveryEnvelope.deserialize(recoveryEnvelopeBytes) }.getOrNull()
            ?: return false
        val masterKey = keyManager.unwrapRecoveryEnvelope(envelope, recoveryCodeInput) ?: return false

        try {
            // Re-wrap master key with new PIN
            val newPinEnvelope = keyManager.createPinEnvelope(masterKey, newPinChars)
            vaultDao.updateCredential(realVault.id, newPinEnvelope.serialize(), 0)
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
