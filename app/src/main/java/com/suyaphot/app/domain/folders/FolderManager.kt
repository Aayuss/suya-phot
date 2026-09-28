package com.suyaphot.app.domain.folders

import androidx.room.withTransaction
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.dao.FolderDao
import com.suyaphot.app.core.database.dao.MediaItemDao
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.model.Folder
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

enum class FolderDeletePolicy {
    MOVE_CONTENTS_TO_PARENT,
    DELETE_CONTENTS_TO_TRASH
}

/**
 * Manages nested logical folder hierarchy and cycle prevention.
 */
class FolderManager(
    private val sessionManager: SessionManager? = null,
    private val folderDao: FolderDao,
    private val mediaItemDao: MediaItemDao? = null,
    private val database: SuyaDatabase? = null
) {

    private fun getCurrentSession(): VaultSession.Unlocked {
        val sm = checkNotNull(sessionManager) { "SessionManager is required" }
        val s = sm.sessionState.value
        check(s is VaultSession.Unlocked) { "Vault is locked" }
        return s
    }

    companion object {
        fun normalizeFolderName(raw: String): String {
            val name = raw.trim()
            require(name.isNotEmpty()) { "Folder name cannot be blank" }
            require(name.length <= 120) { "Folder name cannot exceed 120 characters" }
            require(name.none { it.isISOControl() }) { "Folder name contains invalid characters" }
            return name
        }
    }

    /**
     * Creates a new folder at root or inside [parentId].
     */
    suspend fun createFolder(name: String, parentId: String?): String = withContext(Dispatchers.IO) {
        val session = getCurrentSession()
        val cleanName = normalizeFolderName(name)

        if (parentId != null) {
            val parent = folderDao.getFolderForVault(parentId, session.vaultId)
            requireNotNull(parent) { "Parent folder does not exist in current vault" }
        }

        // Validate sibling names don't conflict case-insensitively
        val siblings = folderDao.getSubFoldersSync(session.vaultId, parentId)
        val conflict = siblings.any { sibling ->
            val sibName = try {
                val dec = Aead.decryptWithPrependedNonce(session.metaSubkey, sibling.encryptedName, sibling.id.toByteArray(Charsets.UTF_8))
                String(dec, Charsets.UTF_8)
            } catch (e: Exception) { "" }
            sibName.equals(cleanName, ignoreCase = true)
        }
        require(!conflict) { "A folder with this name already exists in this folder" }

        val folderId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val encryptedName = Aead.encryptWithPrependedNonce(
            keyBytes = session.metaSubkey,
            plaintext = cleanName.toByteArray(Charsets.UTF_8),
            aad = folderId.toByteArray(Charsets.UTF_8)
        )

        val entity = FolderEntity(
            id = folderId,
            vaultId = session.vaultId,
            parentId = parentId,
            encryptedName = encryptedName,
            createdAt = now,
            updatedAt = now,
            coverMediaId = null,
            sortOrder = 0L
        )
        folderDao.insert(entity)
        folderId
    }

    /**
     * Renames an existing folder.
     */
    suspend fun renameFolder(folderId: String, newName: String) = withContext(Dispatchers.IO) {
        val session = getCurrentSession()
        val cleanName = normalizeFolderName(newName)

        val folder = folderDao.getFolderForVault(folderId, session.vaultId)
        requireNotNull(folder) { "Folder not found in current vault" }

        val siblings = folderDao.getSubFoldersSync(session.vaultId, folder.parentId)
        val conflict = siblings.any { sibling ->
            if (sibling.id == folderId) return@any false
            val sibName = try {
                val dec = Aead.decryptWithPrependedNonce(session.metaSubkey, sibling.encryptedName, sibling.id.toByteArray(Charsets.UTF_8))
                String(dec, Charsets.UTF_8)
            } catch (e: Exception) { "" }
            sibName.equals(cleanName, ignoreCase = true)
        }
        require(!conflict) { "A folder with this name already exists in this folder" }

        val encryptedName = Aead.encryptWithPrependedNonce(
            keyBytes = session.metaSubkey,
            plaintext = cleanName.toByteArray(Charsets.UTF_8),
            aad = folderId.toByteArray(Charsets.UTF_8)
        )
        check(folderDao.renameFolderForVault(session.vaultId, folderId, encryptedName, System.currentTimeMillis()) == 1)
    }

    /**
     * Checks if moving [folderId] under [newParentId] would cause a cycle.
     */
    suspend fun canMoveFolder(folderId: String, newParentId: String?): Boolean = withContext(Dispatchers.IO) {
        val vaultId = (sessionManager?.sessionState?.value as? VaultSession.Unlocked)?.vaultId
        if (vaultId != null && folderDao.getFolderForVault(folderId, vaultId) == null) return@withContext false
        if (newParentId == null) return@withContext true
        if (folderId == newParentId) return@withContext false
        if (vaultId != null && folderDao.getFolderForVault(newParentId, vaultId) == null) return@withContext false

        var cursor: String? = newParentId
        val visited = mutableSetOf<String>()

        while (cursor != null) {
            if (!visited.add(cursor)) return@withContext false // Cycle loop guard
            if (cursor == folderId) return@withContext false
            cursor = if (vaultId != null) folderDao.getParentIdForVault(vaultId, cursor) else folderDao.getParentId(cursor)
        }
        true
    }

    /**
     * Moves a folder under [newParentId] if cycle check passes.
     */
    suspend fun moveFolder(folderId: String, newParentId: String?): Boolean = withContext(Dispatchers.IO) {
        if (!canMoveFolder(folderId, newParentId)) return@withContext false
        val session = getCurrentSession()
        folderDao.moveFolderForVault(session.vaultId, folderId, newParentId, System.currentTimeMillis()) == 1
    }

    /**
     * Deletes a folder according to the chosen policy for its contents using a Room transaction.
     */
    suspend fun deleteFolder(
        folderId: String,
        policy: FolderDeletePolicy
    ) = withContext(Dispatchers.IO) {
        val session = getCurrentSession()

        val db = checkNotNull(database) { "SuyaDatabase is required" }
        val mDao = checkNotNull(mediaItemDao) { "MediaItemDao is required" }

        db.withTransaction {
            val folder = folderDao.getFolderForVault(
                folderId = folderId,
                vaultId = session.vaultId
            ) ?: return@withTransaction

            val parentId = folder.parentId
            val now = System.currentTimeMillis()

            when (policy) {
                FolderDeletePolicy.MOVE_CONTENTS_TO_PARENT -> {
                    mDao.moveFolderContents(
                        vaultId = session.vaultId,
                        sourceFolderId = folderId,
                        targetFolderId = parentId,
                        now = now
                    )
                    folderDao.reparentChildren(
                        vaultId = session.vaultId,
                        oldParentId = folderId,
                        newParentId = parentId,
                        now = now
                    )
                }

                FolderDeletePolicy.DELETE_CONTENTS_TO_TRASH -> {
                    val subtree = mutableListOf<String>()
                    suspend fun collect(parent: String) {
                        subtree += parent
                        folderDao.getSubFoldersSync(session.vaultId, parent).forEach { collect(it.id) }
                    }
                    collect(folderId)
                    subtree.forEach { id -> mDao.trashFolderContents(session.vaultId, id, now) }
                    subtree.asReversed().forEach { id -> folderDao.deleteForVault(id, session.vaultId) }
                }
            }

            if (policy == FolderDeletePolicy.MOVE_CONTENTS_TO_PARENT) {
                folderDao.deleteForVault(folderId = folderId, vaultId = session.vaultId)
            }
        }
    }

    /**
     * Moves media items between folders without re-encryption.
     */
    suspend fun moveMediaToFolder(mediaIds: List<String>, targetFolderId: String?) = withContext(Dispatchers.IO) {
        val session = getCurrentSession()
        val mDao = checkNotNull(mediaItemDao) { "MediaItemDao is required" }
        if (targetFolderId != null) {
            requireNotNull(folderDao.getFolderForVault(targetFolderId, session.vaultId)) {
                "Target folder does not exist in current vault"
            }
        }
        val updated = mDao.moveItemsToFolderForVault(session.vaultId, mediaIds, targetFolderId, System.currentTimeMillis())
        require(updated == mediaIds.distinct().size) { "Some selected media did not belong to the active vault" }
    }

    /**
     * Builds the breadcrumb trail from root to the given folder.
     */
    suspend fun getBreadcrumbs(folderId: String?): List<Pair<String?, String>> = withContext(Dispatchers.IO) {
        if (folderId == null) {
            return@withContext listOf(null to "All Photos")
        }

        val session = getCurrentSession()
        val trail = mutableListOf<Pair<String?, String>>()
        val visited = mutableSetOf<String>()
        var cursor: String? = folderId

        while (cursor != null) {
            if (!visited.add(cursor)) {
                // Cycle detected, stop traversing to prevent infinite loop
                break
            }
            val entity = folderDao.getFolderForVault(cursor, session.vaultId) ?: break
            val name = try {
                val dec = Aead.decryptWithPrependedNonce(session.metaSubkey, entity.encryptedName, entity.id.toByteArray(Charsets.UTF_8))
                String(dec, Charsets.UTF_8)
            } catch (e: Exception) {
                "Folder"
            }
            trail.add(0, cursor to name)
            cursor = entity.parentId
        }

        listOf<Pair<String?, String>>(null to "All Photos") + trail
    }

    /**
     * Returns subfolders of [parentId] mapped to domain models.
     */
    fun getSubFoldersFlow(parentId: String?): Flow<List<Folder>> {
        val session = getCurrentSession()
        return folderDao.getSubFoldersWithCount(session.vaultId, parentId).map { list ->
            list.map { item ->
                val entity = item.folder
                val name = try {
                    val dec = Aead.decryptWithPrependedNonce(session.metaSubkey, entity.encryptedName, entity.id.toByteArray(Charsets.UTF_8))
                    String(dec, Charsets.UTF_8)
                } catch (e: Exception) {
                    "Folder"
                }
                Folder(
                    id = entity.id,
                    vaultId = entity.vaultId,
                    parentId = entity.parentId,
                    name = name,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                    coverMediaId = entity.coverMediaId,
                    sortOrder = entity.sortOrder,
                    itemCount = item.itemCount
                )
            }
        }
    }
}
