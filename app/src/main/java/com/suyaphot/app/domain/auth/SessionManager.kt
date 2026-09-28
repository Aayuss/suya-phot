package com.suyaphot.app.domain.auth

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.suyaphot.app.core.crypto.SensitiveKeyHandle
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.core.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface VaultSession {
    data object Locked : VaultSession

    data class Unlocked(
        val vaultId: String,
        val kind: VaultKind,
        val masterKeyHandle: SensitiveKeyHandle,
        val mediaSubkey: ByteArray,
        val metaSubkey: ByteArray,
        val thumbSubkey: ByteArray,
        val unlockedAt: Long
    ) : VaultSession {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unlocked) return false
            return vaultId == other.vaultId && kind == other.kind
        }

        override fun hashCode(): Int = vaultId.hashCode() * 31 + kind.hashCode()
    }
}

enum class LockReason {
    Explicit,
    Background,
    Timeout,
    ScreenOff
}

/**
 * Coordinates in-memory vault session lifecycle, auto-lock timeouts, and key cleanup.
 */
class SessionManager(
    private val preferences: SecurityPreferences,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : DefaultLifecycleObserver {

    private val _sessionState = MutableStateFlow<VaultSession>(VaultSession.Locked)
    val sessionState: StateFlow<VaultSession> = _sessionState.asStateFlow()

    private val _hideSensitiveUi = MutableStateFlow(false)
    val hideSensitiveUi: StateFlow<Boolean> = _hideSensitiveUi.asStateFlow()

    private var backgroundedAt: Long? = null

    val isUnlocked: Boolean
        get() = _sessionState.value is VaultSession.Unlocked

    val currentVaultId: String?
        get() = (_sessionState.value as? VaultSession.Unlocked)?.vaultId

    val currentVaultKind: VaultKind?
        get() = (_sessionState.value as? VaultSession.Unlocked)?.kind

    /**
     * Authenticates and establishes an active unlocked vault session.
     */
    @Synchronized
    fun unlock(
        vaultId: String,
        kind: VaultKind,
        masterKeyHandle: SensitiveKeyHandle,
        mediaSubkey: ByteArray,
        metaSubkey: ByteArray,
        thumbSubkey: ByteArray
    ) {
        // Lock any prior session cleanly
        lock(LockReason.Explicit)

        _sessionState.value = VaultSession.Unlocked(
            vaultId = vaultId,
            kind = kind,
            masterKeyHandle = masterKeyHandle,
            mediaSubkey = mediaSubkey,
            metaSubkey = metaSubkey,
            thumbSubkey = thumbSubkey,
            unlockedAt = SystemClock.elapsedRealtime()
        )
        _hideSensitiveUi.value = false
        SafeLog.d("SessionManager", "Vault unlocked: $vaultId (kind=$kind)")
    }

    /**
     * Locks the vault immediately, wiping sensitive key handles from memory.
     */
    @Synchronized
    fun lock(reason: LockReason) {
        val current = _sessionState.value
        if (current is VaultSession.Unlocked) {
            current.masterKeyHandle.close()
            current.mediaSubkey.fill(0)
            current.metaSubkey.fill(0)
            current.thumbSubkey.fill(0)
            SafeLog.d("SessionManager", "Vault locked. Reason: $reason")
        }
        _sessionState.value = VaultSession.Locked
        _hideSensitiveUi.value = false
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
        _hideSensitiveUi.value = true

        scope.launch {
            val timeoutMs = preferences.autoLockTimeoutMs.first()
            if (timeoutMs == 0L) {
                lock(LockReason.Background)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val bg = backgroundedAt
        backgroundedAt = null

        if (bg != null && isUnlocked) {
            scope.launch {
                val timeoutMs = preferences.autoLockTimeoutMs.first()
                val awayTime = SystemClock.elapsedRealtime() - bg
                if (awayTime >= timeoutMs && timeoutMs > 0L) {
                    lock(LockReason.Timeout)
                } else {
                    _hideSensitiveUi.value = false
                }
            }
        } else {
            _hideSensitiveUi.value = false
        }
    }

    fun onScreenTurnedOff() {
        scope.launch {
            if (preferences.lockOnScreenOff.first()) {
                lock(LockReason.ScreenOff)
            }
        }
    }
}
