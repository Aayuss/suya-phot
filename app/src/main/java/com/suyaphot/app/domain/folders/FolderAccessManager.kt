package com.suyaphot.app.domain.folders

import android.os.SystemClock
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ViewerAccessScope(val vaultId: String, val hidden: Boolean, val lockIds: List<String>)

/** Ephemeral app-access grants; vault-level media crypto remains the at-rest boundary. */
class FolderAccessManager(
    private val database: SuyaDatabase,
    private val sessionManager: SessionManager,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private data class Grant(val vaultId: String, val expiresAt: Long)
    private val grants = HashMap<String, Grant>()
    private var hiddenGrant: Grant? = null
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
        grants.clear()
        hiddenGrant = null
        _revision.value++
    }

    @Synchronized fun grantHidden(vaultId: String) {
        require(sessionManager.currentVaultId == vaultId)
        hiddenGrant = Grant(vaultId, SystemClock.elapsedRealtime() + 5 * 60_000L)
        _revision.value++
    }

    @Synchronized fun hasHiddenGrant(vaultId: String): Boolean =
        sessionManager.currentVaultId == vaultId && hiddenGrant?.let {
            it.vaultId == vaultId && it.expiresAt > SystemClock.elapsedRealtime()
        } == true

    @Synchronized fun grantLock(vaultId: String, lockId: String) {
        require(sessionManager.currentVaultId == vaultId)
        grants[lockId] = Grant(vaultId, SystemClock.elapsedRealtime() + 5 * 60_000L)
        _revision.value++
    }

    @Synchronized fun revokeLock(lockId: String) {
        grants.remove(lockId)
        _revision.value++
    }

    /** Relock any folder no longer on the active navigation path. */
    suspend fun retainLocksForFolder(vaultId: String, folderId: String?) {
        val retained = HashSet<String>()
        val visited = HashSet<String>()
        var cursor = folderId
        while (cursor != null && visited.add(cursor)) {
            val folder = database.folderDao().getFolderForVault(cursor, vaultId) ?: break
            folder.lockId?.let(retained::add)
            cursor = folder.parentId
        }
        synchronized(this) {
            grants.keys.retainAll(retained)
            _revision.value++
        }
    }

    @Synchronized fun hasLockGrant(vaultId: String, lockId: String): Boolean =
        sessionManager.currentVaultId == vaultId && grants[lockId]?.let {
            it.vaultId == vaultId && it.expiresAt > SystemClock.elapsedRealtime()
        } == true

    suspend fun missingLockIds(vaultId: String, folderId: String): List<String> {
        if (sessionManager.currentVaultId != vaultId) return emptyList()
        val required = ArrayList<String>()
        val visited = HashSet<String>()
        var current: String? = folderId
        while (current != null) {
            check(visited.add(current)) { "Folder cycle detected" }
            val folder = database.folderDao().getFolderForVault(current, vaultId) ?: return emptyList()
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
        return ViewerAccessScope(vaultId, folder.effectiveHidden, locks)
    }

    fun isScopeValid(scope: ViewerAccessScope): Boolean =
        sessionManager.currentVaultId == scope.vaultId &&
            (!scope.hidden || hasHiddenGrant(scope.vaultId)) &&
            scope.lockIds.all { hasLockGrant(scope.vaultId, it) }

    suspend fun canOpen(vaultId: String, folderId: String): Boolean {
        val folder = database.folderDao().getFolderForVault(folderId, vaultId) ?: return false
        if (folder.effectiveHidden && !hasHiddenGrant(vaultId)) return false
        return missingLockIds(vaultId, folderId).isEmpty()
    }
}
