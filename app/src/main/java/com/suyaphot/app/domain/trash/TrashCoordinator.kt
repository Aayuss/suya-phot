package com.suyaphot.app.domain.trash

import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.datastore.SecurityPreferences
import com.suyaphot.app.core.util.VaultFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class PermanentDeleteSummary(
    val deleted: Int,
    val cleanupPending: Int,
    val failedIds: List<String>
)

class TrashCoordinator(
    private val database: SuyaDatabase,
    private val fileStore: VaultFileStore,
    private val preferences: SecurityPreferences
) {
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
        permanentDeleteEntities(vaultId, database.mediaItemDao().getItemsByIdsForVault(vaultId, ids))
    }

    private suspend fun permanentDeleteEntities(
        vaultId: String,
        items: List<MediaItemEntity>
    ): PermanentDeleteSummary {
        var deleted = 0
        val failed = mutableListOf<String>()
        for (item in items) {
            val media = fileStore.getMediaFile(vaultId, item.id)
            val mediaRemoved = !media.exists() || media.delete()
            if (!mediaRemoved) {
                failed += item.id
                continue
            }
            runCatching { fileStore.getThumbFile(vaultId, item.id).delete() }
            if (database.mediaItemDao().deleteForVault(item.id, vaultId) == 1) deleted++ else failed += item.id
        }
        return PermanentDeleteSummary(deleted, failed.size, failed)
    }

    suspend fun restore(vaultId: String, ids: List<String>): Int = withContext(Dispatchers.IO) {
        val items = database.mediaItemDao().getItemsByIdsForVault(vaultId, ids)
        val now = System.currentTimeMillis()
        var restored = 0
        for (item in items) {
            val validFolder = item.previousFolderId?.takeIf {
                database.folderDao().getFolderForVault(it, vaultId) != null
            }
            restored += database.mediaItemDao().restoreFromTrashForVault(
                vaultId, item.id, validFolder, now
            )
        }
        restored
    }
}
