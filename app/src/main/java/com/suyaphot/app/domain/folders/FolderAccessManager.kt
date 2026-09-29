package com.suyaphot.app.domain.folders

import android.os.SystemClock
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ViewerAccessScope(
    val vaultId: String,
    val hidden: Boolean,
    val lockIds: List<String>,
    val privacyEpoch: Long = 0L
)

/** Ephemeral app-access grants; vault-level media crypto remains the at-rest boundary. */
class FolderAccessManager(
    private val database: SuyaDatabase,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private data class Grant(val vaultId: String, val expiresAt: Long)
    private val grants = HashMap<String, Grant>()
    private var hiddenGrant: Grant? = null
    private var privacyEpoch = 0L
    private var expiryJob: Job? = null
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    init {
        scope.launch {
            sessionManager.sessionState.collect { if (it is VaultSession.Locked) clear() }
        }
        scope.launch {
            sessionManager.hideSensitiveUi.collect { if (it) clear() }
        }
    }

    @Synchronized fun clear() {
        expiryJob?.cancel()
        grants.clear()
        hiddenGrant = null
        _revision.value++
    }

    @Synchronized fun onPrivacyMutation() {
        privacyEpoch++
        _revision.value++
    }

    @Synchronized fun grantHidden(vaultId: String) {
        require(sessionManager.currentVaultId == vaultId)
        hiddenGrant = Grant(vaultId, SystemClock.elapsedRealtime() + 5 * 60_000L)
        scheduleExpiryLocked()
        _revision.value++
    }

    @Synchronized fun hasHiddenGrant(vaultId: String): Boolean =
        sessionManager.currentVaultId == vaultId && hiddenGrant?.let {
            it.vaultId == vaultId && it.expiresAt > SystemClock.elapsedRealtime()
        } == true

    @Synchronized fun grantLock(vaultId: String, lockId: String) {
        require(sessionManager.currentVaultId == vaultId)
        grants[lockId] = Grant(vaultId, SystemClock.elapsedRealtime() + 5 * 60_000L)
        scheduleExpiryLocked()
        _revision.value++
    }

    @Synchronized fun revokeLock(lockId: String) {
        grants.remove(lockId)
        scheduleExpiryLocked()
        _revision.value++
    }

    /** Relock any folder no longer on the active navigation path. */
    suspend fun retainLocksForFolder(vaultId: String, folderId: String?) {
        val retained = HashSet<String>()
        val visited = HashSet<String>()
        var cursor = folderId
        var valid = sessionManager.currentVaultId == vaultId
        while (cursor != null && valid) {
            if (!visited.add(cursor)) { valid = false; break }
            val folder = database.folderDao().getFolderForVault(cursor, vaultId)
            if (folder == null) { valid = false; break }
            folder.lockId?.let(retained::add)
            cursor = folder.parentId
        }
        synchronized(this) {
            grants.keys.retainAll(if (valid) retained else emptySet())
            scheduleExpiryLocked()
            _revision.value++
        }
    }

    /** One monotonic timer for the nearest grant; no foreground polling. */
    @Synchronized private fun scheduleExpiryLocked() {
        expiryJob?.cancel()
        val next = (grants.values.map { it.expiresAt } + listOfNotNull(hiddenGrant?.expiresAt)).minOrNull()
            ?: return
        expiryJob = scope.launch {
            delay((next - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            synchronized(this@FolderAccessManager) {
                val now = SystemClock.elapsedRealtime()
                val removed = grants.values.removeAll { it.expiresAt <= now }
                val hiddenExpired = hiddenGrant?.expiresAt?.let { it <= now } == true
                if (hiddenExpired) hiddenGrant = null
                if (removed || hiddenExpired) _revision.value++
                scheduleExpiryLocked()
            }
        }
    }

    @Synchronized fun hasLockGrant(vaultId: String, lockId: String): Boolean =
        sessionManager.currentVaultId == vaultId && grants[lockId]?.let {
            it.vaultId == vaultId && it.expiresAt > SystemClock.elapsedRealtime()
        } == true

    /** Null means an invalid or cross-vault hierarchy, never an unlocked folder. */
    suspend fun missingLockIds(vaultId: String, folderId: String): List<String>? {
        if (sessionManager.currentVaultId != vaultId) return null
        val required = ArrayList<String>()
        val visited = HashSet<String>()
        var current: String? = folderId
        while (current != null) {
            if (!visited.add(current)) return null
            val folder = database.folderDao().getFolderForVault(current, vaultId) ?: return null
            folder.lockId?.let { required += it }
            current = folder.parentId
        }
        return required.asReversed().filterNot { hasLockGrant(vaultId, it) }
    }

    suspend fun scopeForFolder(vaultId: String, folderId: String): ViewerAccessScope? {
        val folder = database.folderDao().getFolderForVault(folderId, vaultId) ?: return null
        if (!canOpen(vaultId, folderId)) return null
        val locks = ArrayList<String>()
        val seen = HashSet<String>()
        var cursor: String? = folderId
        while (cursor != null) {
            if (!seen.add(cursor)) return null
            val entity = database.folderDao().getFolderForVault(cursor, vaultId) ?: return null
            entity.lockId?.let(locks::add)
            cursor = entity.parentId
        }
        return ViewerAccessScope(vaultId, folder.effectiveHidden, locks, privacyEpoch)
    }

    fun isScopeValid(scope: ViewerAccessScope): Boolean =
        sessionManager.currentVaultId == scope.vaultId &&
            scope.privacyEpoch == synchronized(this) { privacyEpoch } &&
            (!scope.hidden || hasHiddenGrant(scope.vaultId)) &&
            scope.lockIds.all { hasLockGrant(scope.vaultId, it) }

    suspend fun canOpen(vaultId: String, folderId: String): Boolean {
        if (sessionManager.currentVaultId != vaultId) return false
        val folder = database.folderDao().getFolderForVault(folderId, vaultId) ?: return false
        if (folder.effectiveHidden && !hasHiddenGrant(vaultId)) return false
        return missingLockIds(vaultId, folderId)?.isEmpty() == true
    }

    suspend fun nextRequirement(
        vaultId: String,
        targetFolderId: String
    ): FolderAccessRequirement {
        if (sessionManager.currentVaultId != vaultId) return FolderAccessRequirement.InvalidHierarchy
        val folder = database.folderDao().getFolderForVault(targetFolderId, vaultId)
            ?: return FolderAccessRequirement.InvalidHierarchy

        if (folder.effectiveHidden && !hasHiddenGrant(vaultId)) {
            return FolderAccessRequirement.HiddenVaultAuth
        }

        val missing = missingLockIds(vaultId, targetFolderId)
            ?: return FolderAccessRequirement.InvalidHierarchy

        if (missing.isEmpty()) {
            return FolderAccessRequirement.Granted
        }

        val firstLockId = missing.first()
        val lock = database.folderLockDao().getForVault(vaultId, firstLockId)
            ?: return FolderAccessRequirement.InvalidHierarchy

        return if (lock.requiresCredentialReset) {
            FolderAccessRequirement.RecoveryReset(
                lockId = lock.id,
                folderId = lock.folderId,
                credentialTypeCode = lock.credentialTypeCode
            )
        } else {
            FolderAccessRequirement.Credential(
                lockId = lock.id,
                folderId = lock.folderId,
                credentialTypeCode = lock.credentialTypeCode,
                biometricIv = lock.biometricIv
            )
        }
    }
}

sealed interface FolderAccessRequirement {
    data object Granted : FolderAccessRequirement
    data object HiddenVaultAuth : FolderAccessRequirement

    data class Credential(
        val lockId: String,
        val folderId: String,
        val credentialTypeCode: Int,
        val biometricIv: ByteArray?
    ) : FolderAccessRequirement

    data class RecoveryReset(
        val lockId: String,
        val folderId: String,
        val credentialTypeCode: Int
    ) : FolderAccessRequirement

    data object InvalidHierarchy : FolderAccessRequirement
}
