package com.suyaphot.app.domain.trash

import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.folders.FolderPrivacyCoordinator
import com.suyaphot.app.domain.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

data class PermanentDeleteSummary(
    val deleted: Int,
    val cleanupPending: Int,
    val failedIds: List<String>
)

class TrashCoordinator(
    private val database: SuyaDatabase,
    private val fileStore: VaultFileStore,
    private val preferences: SecurityPreferences,
    private val accessManager: FolderAccessManager? = null,
    private val sessionManager: SessionManager? = null
) {
    companion object {
        private const val PERMANENT_DELETE_PENDING = 1
        private const val FILE_REMOVED_DB_PENDING = 2
    }

    /** Idempotent recovery for a process death between ciphertext removal and DB deletion. */
    suspend fun reconcilePending(vaultId: String): PermanentDeleteSummary = withContext(Dispatchers.IO) {
        var deleted = 0
        val failed = ArrayList<String>()
        while (true) {
            val batch = database.mediaItemDao().getPendingTrashCleanup(vaultId)
            if (batch.isEmpty()) break
            val result = permanentDeleteEntities(vaultId, batch)
            deleted += result.deleted
            failed += result.failedIds
            if (result.deleted == 0) break
        }
        PermanentDeleteSummary(deleted, failed.size, failed)
    }

    private suspend fun authorized(vaultId: String, item: MediaItemEntity): Boolean {
        if (!item.concealed) return true
        val access = accessManager ?: return false
        if (!access.hasHiddenGrant(vaultId)) return false
        val folderId = item.previousFolderId ?: return true
        val folder = database.folderDao().getFolderForVault(folderId, vaultId) ?: return true
        return access.canOpen(vaultId, folder.id)
    }
    suspend fun purgeExpired(vaultId: String): PermanentDeleteSummary = withContext(Dispatchers.IO) {
        val retentionDays = preferences.trashRetentionDays.first()
        if (retentionDays == 0) return@withContext PermanentDeleteSummary(0, 0, emptyList())
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
        var deleted = 0
        val failed = mutableListOf<String>()
        while (true) {
            val batch = database.mediaItemDao().getExpiredTrash(vaultId, cutoff, 100)
            if (batch.isEmpty()) break
            val summary = permanentDeleteEntities(vaultId, batch)
            deleted += summary.deleted
            failed += summary.failedIds
            if (summary.deleted == 0) break
        }
        PermanentDeleteSummary(deleted, failed.size, failed)
    }

    suspend fun permanentDelete(vaultId: String, ids: List<String>): PermanentDeleteSummary = withContext(Dispatchers.IO) {
        val all = database.mediaItemDao().getItemsByIdsForVault(vaultId, ids)
        val allowed = mutableListOf<MediaItemEntity>()
        val denied = mutableListOf<String>()
        for (item in all) {
            if (item.deletedAt != null && authorized(vaultId, item)) allowed += item else denied += item.id
        }
        val result = permanentDeleteEntities(vaultId, allowed)
        result.copy(cleanupPending = result.cleanupPending + denied.size, failedIds = result.failedIds + denied)
    }

    private suspend fun permanentDeleteEntities(
        vaultId: String,
        items: List<MediaItemEntity>
    ): PermanentDeleteSummary {
        var deleted = 0
        val failed = mutableListOf<String>()
        for (item in items) {
            if (database.mediaItemDao().markTrashCleanupState(
                    vaultId, item.id, PERMANENT_DELETE_PENDING, System.currentTimeMillis()
                ) != 1) {
                failed += item.id
                continue
            }
            val cleanup = fileStore.deleteMediaArtifacts(vaultId, item.id)
            if (!cleanup.allRemoved) {
                failed += item.id
                continue
            }
            if (database.mediaItemDao().markTrashCleanupState(
                    vaultId, item.id, FILE_REMOVED_DB_PENDING, System.currentTimeMillis()
                ) != 1) {
                failed += item.id
                continue
            }
            val removedRows = database.mediaItemDao().deleteForVault(item.id, vaultId)
            if (removedRows == 1 || database.mediaItemDao().getItemForVault(item.id, vaultId) == null) {
                deleted++
            } else failed += item.id
        }
        return PermanentDeleteSummary(deleted, failed.size, failed)
    }

    suspend fun restore(vaultId: String, ids: List<String>): Int = withContext(Dispatchers.IO) {
        val items = database.mediaItemDao().getItemsByIdsForVault(vaultId, ids)
        val now = System.currentTimeMillis()
        var restored = 0
        for (item in items) {
            if (!authorized(vaultId, item)) continue
            val validFolder = item.previousFolderId?.let { database.folderDao().getFolderForVault(it, vaultId) }
            val restoredFolder = if (item.concealed && (validFolder == null || (!validFolder.effectiveHidden && !validFolder.effectiveProtected))) {
                ensureRecoveredPrivateFolder(vaultId)
            } else validFolder
            // Failure to create the hidden fallback must never make private media visible at root.
            if (item.concealed && restoredFolder == null) continue
            restored += database.mediaItemDao().restoreFromTrashForVault(
                vaultId, item.id, restoredFolder?.id,
                item.concealed || restoredFolder?.let { it.effectiveHidden || it.effectiveProtected } == true,
                now
            )
        }
        restored
    }

    private suspend fun ensureRecoveredPrivateFolder(vaultId: String): FolderEntity? {
        val id = UUID.nameUUIDFromBytes("suya-phot:recovered-private:$vaultId".toByteArray()).toString()
        database.folderDao().getFolderForVault(id, vaultId)?.let { existing ->
            if (!existing.effectiveHidden) {
                FolderPrivacyCoordinator(database).setHidden(vaultId, id, true)
                return database.folderDao().getFolderForVault(id, vaultId)
            }
            return existing
        }
        val lease = sessionManager?.acquireOperationKeyLease() ?: return null
        try {
            if (lease.vaultId != vaultId) return null
            val name = "Recovered Private".toByteArray(Charsets.UTF_8)
            val encrypted = try { Aead.encryptWithPrependedNonce(lease.metaSubkey, name, id.toByteArray()) }
                finally { name.fill(0) }
            val now = System.currentTimeMillis()
            val folder = FolderEntity(id, vaultId, null, encrypted, now, now, null, 0L,
                directHidden = true, effectiveHidden = true)
            database.folderDao().insert(folder)
            return folder
        } finally { lease.close() }
    }
}
