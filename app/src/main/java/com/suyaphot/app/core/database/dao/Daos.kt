package com.suyaphot.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.suyaphot.app.core.database.entity.FolderEntity
import com.suyaphot.app.core.database.entity.FolderLockEntity
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

    @Query("UPDATE vaults SET pinEnvelope = :envelope, credentialTypeCode = :typeCode WHERE id = :vaultId")
    suspend fun updateCredential(vaultId: String, envelope: ByteArray, typeCode: Int): Int

    @Query("UPDATE vaults SET recoveryEnvelope = :newRecoveryEnvelope WHERE id = :vaultId")
    suspend fun updateRecoveryEnvelope(vaultId: String, newRecoveryEnvelope: ByteArray?)

    @Query("UPDATE vaults SET biometricEnvelope = :envelope, biometricIv = :iv WHERE id = :vaultId")
    suspend fun updateBiometricEnvelope(vaultId: String, envelope: ByteArray?, iv: ByteArray?)
}

data class FolderWithCount(
    @androidx.room.Embedded val folder: FolderEntity,
    val itemCount: Int
)

data class VisibleSearchHeader(
    val id: String,
    val mediaTypeCode: Int,
    val favorite: Boolean,
    val importedAt: Long,
    val dateTakenMs: Long?,
    val plaintextSize: Long
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

    @Query("SELECT * FROM folders WHERE vaultId = :vaultId")
    suspend fun getFoldersForVaultOnce(vaultId: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE vaultId = :vaultId AND parentId IS :parentId ORDER BY sortOrder ASC, createdAt DESC")
    fun getSubFolders(vaultId: String, parentId: String?): Flow<List<FolderEntity>>

    @Query("""
        SELECT f.*,
          (
            SELECT COUNT(*) FROM media_items m
            WHERE m.vaultId = f.vaultId
              AND m.folderId = f.id
              AND m.deletedAt IS NULL
              AND m.concealed = 0
          ) +
          (
            SELECT COUNT(*) FROM folders c
            WHERE c.vaultId = f.vaultId
              AND c.parentId = f.id
              AND c.effectiveHidden = 0
          ) AS itemCount
        FROM folders f
        WHERE f.vaultId = :vaultId
          AND f.parentId IS :parentId
          AND f.effectiveHidden = 0
        ORDER BY f.sortOrder ASC, f.createdAt DESC
    """)
    fun getSubFoldersWithCount(vaultId: String, parentId: String?): Flow<List<FolderWithCount>>

    @Query("""
        SELECT f.*,
          (
            SELECT COUNT(*) FROM media_items m
            WHERE m.vaultId = f.vaultId
              AND m.folderId = f.id
              AND m.deletedAt IS NULL
          ) +
          (
            SELECT COUNT(*) FROM folders c
            WHERE c.vaultId = f.vaultId
              AND c.parentId = f.id
          ) AS itemCount
        FROM folders f
        WHERE f.vaultId = :vaultId AND f.directHidden = 1
          AND (f.parentId IS NULL OR NOT EXISTS (
              SELECT 1 FROM folders p WHERE p.id = f.parentId AND p.vaultId = f.vaultId AND p.effectiveHidden = 1
          ))
        ORDER BY f.sortOrder ASC, f.createdAt DESC
    """)
    fun getHiddenRoots(vaultId: String): Flow<List<FolderWithCount>>

    @Query("SELECT * FROM folders WHERE vaultId = :vaultId AND parentId IS :parentId ORDER BY sortOrder ASC, createdAt DESC")
    fun getAllSubFolders(vaultId: String, parentId: String?): Flow<List<FolderEntity>>

    @Query("""
        SELECT f.*,
          (
            SELECT COUNT(*) FROM media_items m
            WHERE m.vaultId = f.vaultId
              AND m.folderId = f.id
              AND m.deletedAt IS NULL
          ) +
          (
            SELECT COUNT(*) FROM folders c
            WHERE c.vaultId = f.vaultId
              AND c.parentId = f.id
          ) AS itemCount
        FROM folders f
        WHERE f.vaultId = :vaultId
          AND f.parentId IS :parentId
        ORDER BY f.sortOrder ASC, f.createdAt DESC
    """)
    fun getAllSubFoldersWithCount(vaultId: String, parentId: String?): Flow<List<FolderWithCount>>

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

    @Query("UPDATE folders SET directHidden = :hidden, updatedAt = :now WHERE vaultId = :vaultId AND id = :folderId")
    suspend fun setDirectHidden(vaultId: String, folderId: String, hidden: Boolean, now: Long): Int

    @Query("UPDATE folders SET effectiveHidden = :hidden, effectiveProtected = :protected, updatedAt = :now WHERE vaultId = :vaultId AND id = :folderId")
    suspend fun setEffectivePrivacy(vaultId: String, folderId: String, hidden: Boolean, protected: Boolean, now: Long): Int

    @Query("UPDATE folders SET lockId = :lockId, updatedAt = :now WHERE vaultId = :vaultId AND id = :folderId")
    suspend fun setLockId(vaultId: String, folderId: String, lockId: String?, now: Long): Int

}

@Dao
interface FolderLockDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(lock: FolderLockEntity)

    @Query("SELECT * FROM folder_locks WHERE vaultId = :vaultId AND folderId = :folderId LIMIT 1")
    suspend fun getForFolder(vaultId: String, folderId: String): FolderLockEntity?

    @Query("SELECT * FROM folder_locks WHERE vaultId = :vaultId AND id = :lockId LIMIT 1")
    suspend fun getForVault(vaultId: String, lockId: String): FolderLockEntity?

    @Query("UPDATE folder_locks SET biometricEnvelope = :envelope, biometricIv = :iv, updatedAt = :now WHERE vaultId = :vaultId AND id = :lockId")
    suspend fun updateBiometric(vaultId: String, lockId: String, envelope: ByteArray?, iv: ByteArray?, now: Long): Int

    @Query("UPDATE folder_locks SET credentialEnvelope = :envelope, credentialTypeCode = :typeCode, recoveryEnvelope = :recoveryEnvelope, requiresCredentialReset = 0, updatedAt = :now WHERE vaultId = :vaultId AND id = :lockId")
    suspend fun updateCredential(vaultId: String, lockId: String, envelope: ByteArray, typeCode: Int, recoveryEnvelope: ByteArray?, now: Long): Int

    @Query("UPDATE folder_locks SET credentialEnvelope = :envelope, credentialTypeCode = :typeCode, recoveryEnvelope = :recoveryEnvelope, requiresCredentialReset = 0, biometricEnvelope = NULL, biometricIv = NULL, updatedAt = :now WHERE vaultId = :vaultId AND id = :lockId")
    suspend fun resetCredentialFromRecovery(vaultId: String, lockId: String, envelope: ByteArray, typeCode: Int, recoveryEnvelope: ByteArray?, now: Long): Int

    @Query("UPDATE folder_locks SET recoveryEnvelope = :recoveryEnvelope, updatedAt = :now WHERE vaultId = :vaultId AND id = :lockId")
    suspend fun updateRecovery(vaultId: String, lockId: String, recoveryEnvelope: ByteArray, now: Long): Int

    @Query("SELECT * FROM folder_locks WHERE vaultId = :vaultId")
    suspend fun getAllForVault(vaultId: String): List<FolderLockEntity>

    @Query("DELETE FROM folder_locks WHERE vaultId = :vaultId AND id = :lockId")
    suspend fun delete(vaultId: String, lockId: String): Int
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

    @Query("UPDATE media_items SET encryptedPreviewRelativePath = :path WHERE id = :id AND vaultId = :vaultId AND deletedAt IS NULL")
    suspend fun setPreviewPathForVault(vaultId: String, id: String, path: String): Int

    @Query("UPDATE media_items SET encryptedThumbRelativePath = :path WHERE id = :id AND vaultId = :vaultId AND deletedAt IS NULL")
    suspend fun setThumbPathForVault(vaultId: String, id: String, path: String): Int

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND id IN (:ids)")
    suspend fun getItemsByIdsForVault(vaultId: String, ids: List<String>): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL AND concealed = 0 ORDER BY importedAt DESC, id DESC")
    fun getAllActive(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT COUNT(*) FROM media_items WHERE vaultId = :vaultId")
    fun observeMediaChanges(vaultId: String): Flow<Int>

    @Query("""SELECT id FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL AND concealed = 0
        AND (:filterCode = 0 OR (:filterCode = 1 AND mediaTypeCode = 0)
            OR (:filterCode = 2 AND mediaTypeCode = 1) OR (:filterCode = 3 AND favorite = 1))""")
    suspend fun getAllVisibleIdsForFilter(vaultId: String, filterCode: Int): List<String>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL AND concealed = 0 ORDER BY importedAt DESC, id DESC")
    suspend fun getAllActiveOnce(vaultId: String): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL AND concealed = 0 ORDER BY importedAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun getSearchBatch(vaultId: String, limit: Int, offset: Int): List<MediaItemEntity>

    @Query("SELECT id, mediaTypeCode, favorite, importedAt, dateTakenMs, plaintextSize FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL AND concealed = 0")
    suspend fun getVisibleSearchHeaders(vaultId: String): List<VisibleSearchHeader>

    @RawQuery(observedEntities = [MediaItemEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, MediaItemEntity>

    @RawQuery
    suspend fun viewerIds(query: SupportSQLiteQuery): List<String>

    @RawQuery
    suspend fun rawCount(query: SupportSQLiteQuery): Int

    @Query("""SELECT id FROM media_items WHERE vaultId = :vaultId AND id IN (:candidateIds) AND deletedAt IS NULL AND concealed = 0
        AND (:filterCode = 0 OR (:filterCode = 1 AND mediaTypeCode = 0)
            OR (:filterCode = 2 AND mediaTypeCode = 1) OR (:filterCode = 3 AND favorite = 1))""")
    suspend fun getVisibleIdsAmong(vaultId: String, candidateIds: List<String>, filterCode: Int): List<String>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND folderId IS :folderId AND deletedAt IS NULL AND (:folderId IS NOT NULL OR concealed = 0) ORDER BY importedAt DESC, id DESC")
    fun getByFolderPrivileged(vaultId: String, folderId: String?): Flow<List<MediaItemEntity>>

    @Query("SELECT id FROM media_items WHERE vaultId = :vaultId AND folderId IS :folderId AND deletedAt IS NULL AND (:folderId IS NOT NULL OR concealed = 0) ORDER BY importedAt DESC, id DESC")
    suspend fun getAllIdsInFolder(vaultId: String, folderId: String?): List<String>

    @Query("SELECT id FROM media_items WHERE vaultId = :vaultId AND folderId IS :folderId AND deletedAt IS NULL AND (:folderId IS NOT NULL OR concealed = 0) ORDER BY importedAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun getPagedIdsInFolder(vaultId: String, folderId: String?, limit: Int, offset: Int): List<String>

    @Query("SELECT COUNT(*) FROM media_items WHERE vaultId = :vaultId AND folderId IS :folderId AND deletedAt IS NULL AND (:folderId IS NOT NULL OR concealed = 0)")
    suspend fun countAuthorizedInFolder(vaultId: String, folderId: String?): Int

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND favorite = 1 AND deletedAt IS NULL AND concealed = 0 ORDER BY importedAt DESC, id DESC")
    fun getFavorites(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND mediaTypeCode = 0 AND deletedAt IS NULL AND concealed = 0 ORDER BY importedAt DESC, id DESC")
    fun getPhotosOnly(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND mediaTypeCode = 1 AND deletedAt IS NULL AND concealed = 0 ORDER BY importedAt DESC, id DESC")
    fun getVideosOnly(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NOT NULL AND concealed = 0 ORDER BY deletedAt DESC, id DESC")
    fun getTrashItems(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NOT NULL AND concealed = 1 ORDER BY deletedAt DESC, id DESC")
    fun getPrivateTrashItems(vaultId: String): Flow<List<MediaItemEntity>>

    @Query("SELECT id FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NOT NULL AND concealed = :concealed ORDER BY deletedAt DESC, id DESC")
    suspend fun getAllTrashIds(vaultId: String, concealed: Boolean): List<String>

    @Query("""
        SELECT COUNT(*)
        FROM media_items
        WHERE vaultId = :vaultId
          AND cleanupStateCode != 0
    """)
    suspend fun countPendingTrashCleanup(vaultId: String): Int

    @Query("SELECT DISTINCT previousFolderId FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NOT NULL AND concealed = 1 AND previousFolderId IS NOT NULL")
    suspend fun getPrivateTrashFolderIds(vaultId: String): List<String>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NOT NULL AND deletedAt < :cutoffTimestamp LIMIT :limit")
    suspend fun getExpiredTrash(vaultId: String, cutoffTimestamp: Long, limit: Int = 100): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND sha256Hex = :sha256Hex AND deletedAt IS NULL LIMIT 1")
    suspend fun findBySha256(vaultId: String, sha256Hex: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND sha256Hex = :sha256Hex AND deletedAt IS NOT NULL LIMIT 1")
    suspend fun findTrashBySha256(vaultId: String, sha256Hex: String): MediaItemEntity?

    @Query("SELECT id FROM media_items WHERE vaultId = :vaultId AND folderId = :folderId AND deletedAt IS NULL ORDER BY importedAt DESC, id DESC")
    suspend fun getActiveIdsInFolder(vaultId: String, folderId: String): List<String>

    @Query("SELECT COUNT(*) FROM media_items WHERE vaultId = :vaultId AND folderId IS :folderId AND deletedAt IS NULL")
    suspend fun countInFolder(vaultId: String, folderId: String?): Int

    @Query("SELECT COUNT(*) FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL")
    suspend fun countAllActive(vaultId: String): Int

    @Query("SELECT COALESCE(SUM(plaintextSize), 0) FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NULL")
    suspend fun sumPlaintextSize(vaultId: String): Long

    @Query("SELECT COALESCE(SUM(cipherSize), 0) FROM media_items WHERE vaultId = :vaultId")
    suspend fun sumCipherSize(vaultId: String): Long

    @Query("UPDATE media_items SET folderId = :newFolderId, concealed = :concealed, updatedAt = :now WHERE vaultId = :vaultId AND id IN (:ids) AND deletedAt IS NULL")
    suspend fun moveItemsToFolderForVault(vaultId: String, ids: List<String>, newFolderId: String?, concealed: Boolean, now: Long): Int

    @Query("UPDATE media_items SET folderId = :targetFolderId, updatedAt = :now WHERE vaultId = :vaultId AND folderId = :sourceFolderId AND deletedAt IS NULL")
    suspend fun moveFolderContents(vaultId: String, sourceFolderId: String, targetFolderId: String?, now: Long)

    @Query("""UPDATE media_items SET concealed = COALESCE(
        (SELECT CASE WHEN f.effectiveHidden = 1 OR f.effectiveProtected = 1 THEN 1 ELSE 0 END
         FROM folders f WHERE f.id = media_items.folderId AND f.vaultId = media_items.vaultId), 0)
        WHERE vaultId = :vaultId AND deletedAt IS NULL""")
    suspend fun recomputeActiveConcealment(vaultId: String): Int

    @Query("""UPDATE media_items SET concealed =
        CASE WHEN EXISTS (
            SELECT 1 FROM folders f
            WHERE f.id = media_items.previousFolderId AND f.vaultId = media_items.vaultId
        ) THEN COALESCE((
            SELECT CASE WHEN f.effectiveHidden = 1 OR f.effectiveProtected = 1 THEN 1 ELSE 0 END
            FROM folders f
            WHERE f.id = media_items.previousFolderId AND f.vaultId = media_items.vaultId
            LIMIT 1
        ), concealed) ELSE concealed END
        WHERE vaultId = :vaultId AND deletedAt IS NOT NULL AND previousFolderId IS NOT NULL""")
    suspend fun recomputeTrashConcealment(vaultId: String): Int

    @Query("UPDATE media_items SET cleanupStateCode = :stateCode, updatedAt = :now WHERE id = :id AND vaultId = :vaultId AND deletedAt IS NOT NULL")
    suspend fun markTrashCleanupState(vaultId: String, id: String, stateCode: Int, now: Long): Int

    @Query("SELECT * FROM media_items WHERE vaultId = :vaultId AND deletedAt IS NOT NULL AND cleanupStateCode != 0 LIMIT :limit")
    suspend fun getPendingTrashCleanup(vaultId: String, limit: Int = 100): List<MediaItemEntity>

    @Query("UPDATE media_items SET favorite = :favorite, updatedAt = :now WHERE id = :id AND vaultId = :vaultId AND deletedAt IS NULL")
    suspend fun updateFavoriteForVault(vaultId: String, id: String, favorite: Boolean, now: Long): Int

    @Query("UPDATE media_items SET previousFolderId = folderId, folderId = NULL, deletedAt = :now, updatedAt = :now WHERE vaultId = :vaultId AND id IN (:ids) AND deletedAt IS NULL")
    suspend fun softDeleteForVault(vaultId: String, ids: List<String>, now: Long): Int

    @Query("UPDATE media_items SET previousFolderId = folderId, folderId = NULL, deletedAt = :now, updatedAt = :now WHERE vaultId = :vaultId AND folderId = :folderId AND deletedAt IS NULL")
    suspend fun trashFolderContents(vaultId: String, folderId: String, now: Long)

    @Query("UPDATE media_items SET folderId = :folderId, concealed = :concealed, previousFolderId = NULL, deletedAt = NULL, updatedAt = :restoredAt WHERE vaultId = :vaultId AND id = :id AND deletedAt IS NOT NULL")
    suspend fun restoreFromTrashForVault(vaultId: String, id: String, folderId: String?, concealed: Boolean, restoredAt: Long): Int

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

    @Query("UPDATE jobs SET encryptedPayload = :payload, updatedAt = :now WHERE id = :id AND vaultId = :vaultId")
    suspend fun updateEncryptedPayload(id: String, vaultId: String, payload: ByteArray, now: Long): Int

    @Query("SELECT * FROM jobs WHERE id = :id LIMIT 1")
    suspend fun getJob(id: String): VaultJobEntity?

    @Query("SELECT * FROM jobs WHERE stateCode NOT IN (7, 8, 9)") // Not in (COMPLETED, CANCELLED, FAILED)
    suspend fun getActiveJobs(): List<VaultJobEntity>

    @Query("SELECT * FROM jobs WHERE typeCode = :typeCode AND stateCode NOT IN (7, 8, 9)")
    suspend fun getActiveJobsForType(typeCode: Int): List<VaultJobEntity>

    @Query("DELETE FROM jobs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE jobs SET sourceDispositionCode = :code, updatedAt = :now WHERE id = :id")
    suspend fun updateSourceDisposition(id: String, code: Int?, now: Long)

    @Query("""
        SELECT * FROM jobs 
        WHERE vaultId = :vaultId 
          AND typeCode = :typeCode 
          AND sourceDispositionCode IN (:dispositionCodes)
        ORDER BY updatedAt DESC
    """)
    fun observeJobsWithSourceDispositions(
        vaultId: String, 
        typeCode: Int, 
        dispositionCodes: List<Int>
    ): Flow<List<VaultJobEntity>>

    @Query("""
        SELECT * FROM jobs 
        WHERE vaultId = :vaultId 
          AND typeCode = :typeCode 
          AND sourceDispositionCode IN (:dispositionCodes)
        ORDER BY updatedAt DESC
    """)
    suspend fun getJobsWithSourceDispositions(
        vaultId: String, 
        typeCode: Int, 
        dispositionCodes: List<Int>
    ): List<VaultJobEntity>

    @Query("""
        UPDATE jobs
        SET stateCode = :stateCode,
            sourceDispositionCode = :sourceDispositionCode,
            errorCode = :errorCode,
            updatedAt = :now
        WHERE id = :id
          AND vaultId = :vaultId
    """)
    suspend fun updateTerminalImportState(
        id: String,
        vaultId: String,
        stateCode: Int,
        sourceDispositionCode: Int,
        errorCode: String?,
        now: Long
    ): Int

    @Query("""
        DELETE FROM jobs
        WHERE vaultId = :vaultId
          AND stateCode IN (7, 8, 9)
          AND (sourceDispositionCode IS NULL OR sourceDispositionCode IN (0, 2, 3))
          AND updatedAt < :cutoffTimestamp
    """)
    suspend fun purgeResolvedCompletedJobs(vaultId: String, cutoffTimestamp: Long): Int
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
