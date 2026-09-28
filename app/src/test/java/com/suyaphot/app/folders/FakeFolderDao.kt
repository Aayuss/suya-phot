package com.suyaphot.app.folders

import com.suyaphot.app.core.database.dao.FolderDao
import com.suyaphot.app.core.database.dao.FolderWithCount
import com.suyaphot.app.core.database.entity.FolderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeFolderDao : FolderDao {
    val folders = mutableMapOf<String, FolderEntity>()

    fun setParent(childId: String, parentId: String?) {
        val existing = folders[childId] ?: FolderEntity(
            id = childId,
            vaultId = "vault1",
            parentId = parentId,
            encryptedName = ByteArray(0),
            createdAt = 0L,
            updatedAt = 0L,
            coverMediaId = null,
            sortOrder = 0L
        )
        folders[childId] = existing.copy(parentId = parentId)
    }

    override suspend fun insert(folder: FolderEntity) {
        folders[folder.id] = folder
    }

    override suspend fun update(folder: FolderEntity) {
        folders[folder.id] = folder
    }

    override suspend fun deleteForVault(folderId: String, vaultId: String) {
        folders.remove(folderId)
    }

    override suspend fun getFolderForVault(folderId: String, vaultId: String): FolderEntity? =
        folders[folderId]?.takeIf { it.vaultId == vaultId }

    override suspend fun getParentId(folderId: String): String? = folders[folderId]?.parentId

    override suspend fun getParentIdForVault(vaultId: String, folderId: String): String? =
        folders[folderId]?.takeIf { it.vaultId == vaultId }?.parentId

    override fun getFoldersForVault(vaultId: String): Flow<List<FolderEntity>> =
        flowOf(folders.values.filter { it.vaultId == vaultId })

    override fun getSubFolders(vaultId: String, parentId: String?): Flow<List<FolderEntity>> =
        flowOf(folders.values.filter { it.vaultId == vaultId && it.parentId == parentId })

    override fun getSubFoldersWithCount(vaultId: String, parentId: String?): Flow<List<FolderWithCount>> =
        flowOf(folders.values.filter { it.vaultId == vaultId && it.parentId == parentId }.map { FolderWithCount(it, 0) })

    override suspend fun getSubFoldersSync(vaultId: String, parentId: String?): List<FolderEntity> =
        folders.values.filter { it.vaultId == vaultId && it.parentId == parentId }

    override suspend fun countFolders(vaultId: String): Int = folders.size

    override suspend fun moveFolderForVault(vaultId: String, folderId: String, newParentId: String?, now: Long): Int {
        val folder = folders[folderId]?.takeIf { it.vaultId == vaultId } ?: return 0
        folders[folderId] = folder.copy(parentId = newParentId, updatedAt = now)
        return 1
    }

    override suspend fun reparentChildren(vaultId: String, oldParentId: String, newParentId: String?, now: Long) {
        folders.forEach { (id, f) ->
            if (f.vaultId == vaultId && f.parentId == oldParentId) {
                folders[id] = f.copy(parentId = newParentId, updatedAt = now)
            }
        }
    }

    override suspend fun renameFolderForVault(vaultId: String, folderId: String, nameBytes: ByteArray, now: Long): Int {
        val folder = folders[folderId]?.takeIf { it.vaultId == vaultId } ?: return 0
        folders[folderId] = folder.copy(encryptedName = nameBytes, updatedAt = now)
        return 1
    }

}
