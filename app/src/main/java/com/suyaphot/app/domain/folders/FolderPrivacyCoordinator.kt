package com.suyaphot.app.domain.folders

import androidx.room.withTransaction
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.FolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Materializes inherited privacy once per hierarchy change, never per gallery page. */
class FolderPrivacyCoordinator(private val database: SuyaDatabase) {
    suspend fun setHidden(vaultId: String, folderId: String, hidden: Boolean) = withContext(Dispatchers.IO) {
        database.withTransaction {
            check(database.folderDao().setDirectHidden(vaultId, folderId, hidden, System.currentTimeMillis()) == 1)
            recomputeInsideTransaction(vaultId)
        }
    }

    suspend fun recompute(vaultId: String) = withContext(Dispatchers.IO) {
        database.withTransaction { recomputeInsideTransaction(vaultId) }
    }

    suspend fun recomputeInsideTransaction(vaultId: String) {
        val folders = database.folderDao().getFoldersForVaultOnce(vaultId)
        val byParent = folders.groupBy { it.parentId }
        val visited = HashSet<String>(folders.size)
        val now = System.currentTimeMillis()

        suspend fun walk(folder: FolderEntity, inheritedHidden: Boolean, inheritedProtected: Boolean) {
            check(visited.add(folder.id)) { "Folder cycle detected" }
            val hidden = inheritedHidden || folder.directHidden
            val protected = inheritedProtected || folder.lockId != null
            if (folder.effectiveHidden != hidden || folder.effectiveProtected != protected) {
                check(database.folderDao().setEffectivePrivacy(vaultId, folder.id, hidden, protected, now) == 1)
            }
            byParent[folder.id].orEmpty().forEach { walk(it, hidden, protected) }
        }

        byParent[null].orEmpty().forEach { walk(it, false, false) }
        check(visited.size == folders.size) { "Orphaned or cyclic folder hierarchy" }
        database.mediaItemDao().recomputeActiveConcealment(vaultId)
    }
}
