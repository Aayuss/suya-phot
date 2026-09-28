package com.suyaphot.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.IntruderEventEntity
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.database.entity.VaultJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vault: VaultEntity)

    @Query("SELECT * FROM vaults WHERE id = :vaultId LIMIT 1")
    suspend fun getVault(vaultId: String): VaultEntity?

    @Query("SELECT * FROM vaults WHERE kindCode = :kindCode LIMIT 1")
    suspend fun getVaultByKind(kindCode: Int): VaultEntity?

    @Query("SELECT * FROM vaults")
    suspend fun getAllVaults(): List<VaultEntity>

    @Query("UPDATE vaults SET pinEnvelope = :newPinEnvelope WHERE id = :vaultId")
    suspend fun updatePinEnvelope(vaultId: String, newPinEnvelope: ByteArray)

    @Query("UPDATE vaults SET recoveryEnvelope = :newRecoveryEnvelope WHERE id = :vaultId")
    suspend fun updateRecoveryEnvelope(vaultId: String, newRecoveryEnvelope: ByteArray)

    @Query("UPDATE vaults SET biometricEnvelope = :envelope, biometricIv = :iv WHERE id = :vaultId")
    suspend fun updateBiometricEnvelope(vaultId: String, envelope: ByteArray?, iv: ByteArray?)
}

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun delete(folderId: String)

    @Query("SELECT * FROM folders WHERE id = :folderId LIMIT 1")
    suspend fun getFolder(folderId: String): FolderEntity?

    @Query("SELECT parentId FROM folders WHERE id = :folderId LIMIT 1")
    suspend fun getParentId(folderId: String): String?

    @Query("SELECT * FROM folders WHERE vaultId = :vaultId ORDER BY sortOrder ASC, createdAt DESC")
    fun getFoldersForVault(vaultId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE vaultId = :vaultId AND parentId IS :parentId ORDER BY sortOrder ASC, createdAt DESC")
    fun getSubFolders(vaultId: String, parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE vaultId = :vaultId AND parentId IS :parentId")
    suspend fun getSubFoldersSync(vaultId: String, parentId: String?): List<FolderEntity>

    @Query("SELECT id FROM folders WHERE parentId = :parentId")
    suspend fun getChildFolderIds(parentId: String): List<String>

    @Query("SELECT COUNT(*) FROM folders WHERE vaultId = :vaultId")
    suspend fun countFolders(vaultId: String): Int

    @Query("UPDATE folders SET parentId = :newParentId, updatedAt = :now WHERE id = :folderId")
    suspend fun moveFolder(folderId: String, newParentId: String?, now: Long)

    @Query("UPDATE folders SET encryptedName = :nameBytes, updatedAt = :now WHERE id = :folderId")
    suspend fun renameFolder(folderId: String, nameBytes: ByteArray, now: Long)

    @Query("UPDATE folders SET coverMediaId = :mediaId WHERE id = :folderId")
    suspend fun setCover(folderId: String, mediaId: String?)
}

@Dao
interface MediaItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Update
    suspend fun update(item: MediaItemEntity)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM media_items WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getItem(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE id IN (:ids)")
    suspend fun getItemsByIds(ids: List<String>): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL ORDER BY importedAt DESC")
    fun getAllActive(vaultId: String): Flow<List<MediaItemEntity>>

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

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND sha256Hex = :sha256Hex LIMIT 1")
    suspend fun findBySha256(vaultId: String, sha256Hex: String): MediaItemEntity?

    @Query("SELECT COUNT(*) FROM media_items WHERE vaultId = :vaultId AND folderId IS :folderId AND deletedAt IS NULL")
    suspend fun countInFolder(vaultId: String, folderId: String?): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL")
    suspend fun countAllActive(vaultId: String): Int

    @Query("SELECT COALESCE(SUM(plaintextSize), 0) FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL")
    suspend fun sumPlaintextSize(vaultId: String): Long

    @Query("SELECT COALESCE(SUM(cipherSize), 0) FROM media_items WHERE vaultId = :vaultId")
    suspend fun sumCipherSize(vaultId: String): Long

    @Query("UPDATE media_items SET folderId = :newFolderId, updatedAt = :now WHERE id IN (:ids)")
    suspend fun moveItemsToFolder(ids: List<String>, newFolderId: String?, now: Long)

    @Query("UPDATE media_items SET favorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun updateFavorite(id: String, favorite: Boolean, now: Long)

    @Query("UPDATE media_items SET previousFolderId = folderId, folderId = null, deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<String>, deletedAt: Long)

    @Query("UPDATE media_items SET folderId = previousFolderId, previousFolderId = null, deletedAt = null, updatedAt = :restoredAt WHERE id IN (:ids)")
    suspend fun restoreFromTrash(ids: List<String>, restoredAt: Long)

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId")
    suspend fun getAllForIntegrityCheck(vaultId: String): List<MediaItemEntity>
}

@Dao
interface VaultJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    @Query("DELETE FROM jobs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface IntruderEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: IntruderEventEntity)

    @Query("SELECT * FROM intruder_events ORDER BY createdAt DESC")
    fun getAllEvents(): Flow<List<IntruderEventEntity>>

    @Query("DELETE FROM intruder_events WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM intruder_events")
    suspend fun deleteAll()
}
