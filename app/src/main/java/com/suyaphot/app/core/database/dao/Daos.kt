package com.suyaphot.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.IntruderEventEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.database.entity.RestoreJobEntity
import androidx.paging.PagingSource
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(vault: VaultEntity)

    @Query("SELECT * FROM vaults WHERE id = :vaultId LIMIT 1")
    suspend fun getVault(vaultId: String): VaultEntity?

    @Query("SELECT * FROM vaults WHERE kindCode = :kindCode LIMIT 1")
    suspend fun getVaultByKind(kindCode: Int): VaultEntity?

    @Query("SELECT * FROM vaults WHERE kindCode = :kindCode LIMIT 1")
    fun observeVaultByKind(kindCode: Int): Flow<VaultEntity?>

    @Query("SELECT * FROM vaults")
    suspend fun getAllVaults(): List<VaultEntity>

    @Query("UPDATE vaults SET pinEnvelope = :newPinEnvelope WHERE id = :vaultId")
    suspend fun updatePinEnvelope(vaultId: String, newPinEnvelope: ByteArray)

    @Query("UPDATE vaults SET recoveryEnvelope = :newRecoveryEnvelope WHERE id = :vaultId")
    suspend fun updateRecoveryEnvelope(vaultId: String, newRecoveryEnvelope: ByteArray?)

    @Query("UPDATE vaults SET biometricEnvelope = :envelope, biometricIv = :iv WHERE id = :vaultId")
    suspend fun updateBiometricEnvelope(vaultId: String, envelope: ByteArray?, iv: ByteArray?)
}

data class FolderWithCount(
    @androidx.room.Embedded val folder: FolderEntity,
    val itemCount: Int
)

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :folderId AND vaultId = :vaultId")
    suspend fun deleteForVault(folderId: String, vaultId: String)

    @Query("SELECT * FROM folders WHERE id = :folderId AND vaultId = :vaultId LIMIT 1")
    suspend fun getFolderForVault(folderId: String, vaultId: String): FolderEntity?

    @Query("SELECT parentId FROM folders WHERE id = :folderId LIMIT 1")
    suspend fun getParentId(folderId: String): String?

    @Query("SELECT parentId FROM folders WHERE id = :folderId AND vaultId = :vaultId LIMIT 1")
    suspend fun getParentIdForVault(vaultId: String, folderId: String): String?

    @Query("SELECT * FROM folders WHERE vaultId = :vaultId ORDER BY sortOrder ASC, createdAt DESC")
    fun getFoldersForVault(vaultId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE vaultId = :vaultId AND parentId IS :parentId ORDER BY sortOrder ASC, createdAt DESC")
    fun getSubFolders(vaultId: String, parentId: String?): Flow<List<FolderEntity>>

    @Query("""
        SELECT f.*, (SELECT COUNT(*) FROM media_items m WHERE m.vaultId = f.vaultId AND m.folderId = f.id AND m.deletedAt IS NULL) AS itemCount
        FROM folders f
        WHERE f.vaultId = :vaultId AND f.parentId IS :parentId
        ORDER BY f.sortOrder ASC, f.createdAt DESC
    """)
    fun getSubFoldersWithCount(vaultId: String, parentId: String?): Flow<List<FolderWithCount>>

    @Query("SELECT * FROM folders WHERE vaultId = :vaultId AND parentId IS :parentId")
    suspend fun getSubFoldersSync(vaultId: String, parentId: String?): List<FolderEntity>

    @Query("SELECT COUNT(*) FROM folders WHERE vaultId = :vaultId")
    suspend fun countFolders(vaultId: String): Int

    @Query("UPDATE folders SET parentId = :newParentId, updatedAt = :now WHERE id = :folderId AND vaultId = :vaultId")
    suspend fun moveFolderForVault(vaultId: String, folderId: String, newParentId: String?, now: Long): Int

    @Query("UPDATE folders SET parentId = :newParentId, updatedAt = :now WHERE vaultId = :vaultId AND parentId = :oldParentId")
    suspend fun reparentChildren(vaultId: String, oldParentId: String, newParentId: String?, now: Long)

    @Query("UPDATE folders SET encryptedName = :nameBytes, updatedAt = :now WHERE id = :folderId AND vaultId = :vaultId")
    suspend fun renameFolderForVault(vaultId: String, folderId: String, nameBytes: ByteArray, now: Long): Int

}

@Dao
interface MediaItemDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Update
    suspend fun update(item: MediaItemEntity)

    @Query("DELETE FROM media_items WHERE id = :id AND vaultId = :vaultId")
    suspend fun deleteForVault(id: String, vaultId: String): Int

    @Query("SELECT * FROM media_items WHERE id = :id AND vaultId = :vaultId LIMIT 1")
    suspend fun getItemForVault(id: String, vaultId: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND id IN (:ids)")
    suspend fun getItemsByIdsForVault(vaultId: String, ids: List<String>): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL ORDER BY importedAt DESC")
    fun getAllActive(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL ORDER BY importedAt DESC")
    suspend fun getAllActiveOnce(vaultId: String): List<MediaItemEntity>

    @RawQuery(observedEntities = [MediaItemEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND folderId IS :folderId AND deletedAt IS NULL ORDER BY importedAt DESC")
    fun getByFolder(vaultId: String, folderId: String?): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND favorite = 1 AND deletedAt IS NULL ORDER BY importedAt DESC")
    fun getFavorites(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND mediaTypeCode = 0 AND deletedAt IS NULL ORDER BY importedAt DESC")
    fun getPhotosOnly(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND mediaTypeCode = 1 AND deletedAt IS NULL ORDER BY importedAt DESC")
    fun getVideosOnly(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getTrashItems(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NOT NULL AND deletedAt < :cutoffTimestamp LIMIT :limit")
    suspend fun getExpiredTrash(vaultId: String, cutoffTimestamp: Long, limit: Int = 100): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND sha256Hex = :sha256Hex AND deletedAt IS NULL LIMIT 1")
    suspend fun findBySha256(vaultId: String, sha256Hex: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND sha256Hex = :sha256Hex AND deletedAt IS NOT NULL LIMIT 1")
    suspend fun findTrashBySha256(vaultId: String, sha256Hex: String): MediaItemEntity?

    @Query("SELECT id FROM media_items WHERE vaultId = :vaultId AND folderId = :folderId AND deletedAt IS NULL")
    suspend fun getActiveIdsInFolder(vaultId: String, folderId: String): List<String>

    @Query("SELECT COUNT(*) FROM media_items WHERE vaultId = :vaultId AND folderId IS :folderId AND deletedAt IS NULL")
    suspend fun countInFolder(vaultId: String, folderId: String?): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL")
    suspend fun countAllActive(vaultId: String): Int

    @Query("SELECT COALESCE(SUM(plaintextSize), 0) FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL")
    suspend fun sumPlaintextSize(vaultId: String): Long

    @Query("SELECT COALESCE(SUM(cipherSize), 0) FROM media_items WHERE vaultId = :vaultId")
    suspend fun sumCipherSize(vaultId: String): Long

    @Query("UPDATE media_items SET folderId = :newFolderId, updatedAt = :now WHERE vaultId = :vaultId AND id IN (:ids) AND deletedAt IS NULL")
    suspend fun moveItemsToFolderForVault(vaultId: String, ids: List<String>, newFolderId: String?, now: Long): Int

    @Query("UPDATE media_items SET folderId = :targetFolderId, updatedAt = :now WHERE vaultId = :vaultId AND folderId = :sourceFolderId AND deletedAt IS NULL")
    suspend fun moveFolderContents(vaultId: String, sourceFolderId: String, targetFolderId: String?, now: Long)

    @Query("UPDATE media_items SET favorite = :favorite, updatedAt = :now WHERE id = :id AND vaultId = :vaultId AND deletedAt IS NULL")
    suspend fun updateFavoriteForVault(vaultId: String, id: String, favorite: Boolean, now: Long): Int

    @Query("UPDATE media_items SET previousFolderId = folderId, folderId = NULL, deletedAt = :now, updatedAt = :now WHERE vaultId = :vaultId AND id IN (:ids) AND deletedAt IS NULL")
    suspend fun softDeleteForVault(vaultId: String, ids: List<String>, now: Long): Int

    @Query("UPDATE media_items SET previousFolderId = folderId, folderId = NULL, deletedAt = :now, updatedAt = :now WHERE vaultId = :vaultId AND folderId = :folderId AND deletedAt IS NULL")
    suspend fun trashFolderContents(vaultId: String, folderId: String, now: Long)

    @Query("UPDATE media_items SET folderId = :folderId, previousFolderId = NULL, deletedAt = NULL, updatedAt = :restoredAt WHERE vaultId = :vaultId AND id = :id AND deletedAt IS NOT NULL")
    suspend fun restoreFromTrashForVault(vaultId: String, id: String, folderId: String?, restoredAt: Long): Int

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId")
    suspend fun getAllForIntegrityCheck(vaultId: String): List<MediaItemEntity>
}

@Dao
interface RestoreJobDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: RestoreJobEntity)

    @Query("UPDATE restore_jobs SET phaseCode = :phaseCode, encryptedDestinationUri = COALESCE(:destination, encryptedDestinationUri), updatedAt = :now, safeErrorCode = :errorCode WHERE id = :id")
    suspend fun updatePhase(id: String, phaseCode: Int, destination: ByteArray?, now: Long, errorCode: String? = null)

    @Query("SELECT * FROM restore_jobs WHERE vaultId = :vaultId AND phaseCode NOT IN (7, 8)")
    suspend fun getActiveForVault(vaultId: String): List<RestoreJobEntity>
}

@Dao
interface VaultJobDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: VaultJobEntity)

    @Update
    suspend fun update(job: VaultJobEntity)

    @Query("UPDATE jobs SET stateCode = :stateCode, updatedAt = :now, errorCode = :errorCode WHERE id = :id")
    suspend fun updateState(id: String, stateCode: Int, now: Long, errorCode: String? = null)

    @Query("UPDATE jobs SET progressCurrent = :current, progressTotal = :total, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: String, current: Long, total: Long, now: Long)

    @Query("SELECT * FROM jobs WHERE id = :id LIMIT 1")
    suspend fun getJob(id: String): VaultJobEntity?

    @Query("SELECT * FROM jobs WHERE stateCode NOT IN (7, 8, 9)") // Not in (COMPLETED, CANCELLED, FAILED)
    suspend fun getActiveJobs(): List<VaultJobEntity>

    @Query("SELECT * FROM jobs WHERE typeCode = :typeCode AND stateCode NOT IN (7, 8, 9)")
    suspend fun getActiveJobsForType(typeCode: Int): List<VaultJobEntity>

    @Query("DELETE FROM jobs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface IntruderEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: IntruderEventEntity)

    @Query("SELECT * FROM intruder_events WHERE realVaultId = :realVaultId ORDER BY createdAt DESC")
    fun getEventsForVault(realVaultId: String): Flow<List<IntruderEventEntity>>

    @Query("DELETE FROM intruder_events WHERE id = :id AND realVaultId = :realVaultId")
    suspend fun deleteForVault(id: String, realVaultId: String)

    @Query("DELETE FROM intruder_events WHERE realVaultId = :realVaultId")
    suspend fun deleteAllForVault(realVaultId: String)

}
