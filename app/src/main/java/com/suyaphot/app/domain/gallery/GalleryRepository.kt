package com.suyaphot.app.domain.gallery

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.sqlite.db.SimpleSQLiteQuery
import com.suyaphot.app.core.database.dao.MediaItemDao
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.domain.folders.FolderAccessManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

enum class GalleryFilter { ALL, PHOTOS, VIDEOS, FAVORITES }

sealed interface ViewerCollection {
    data class Gallery(val filter: GalleryFilter, val sort: String, val searchIds: List<String>? = null) : ViewerCollection
    data class Folder(val folderId: String?) : ViewerCollection
}

class GalleryRepository(private val dao: MediaItemDao, private val access: FolderAccessManager) {
    private val pagingConfig = PagingConfig(pageSize = 60, prefetchDistance = 20, enablePlaceholders = false)

    /** Never constructs a protected-content PagingSource until the folder hierarchy is authorized. */
    fun pagedFolder(vaultId: String, folderId: String?): Flow<PagingData<MediaItemEntity>> = flow {
        if (folderId != null && !access.canOpen(vaultId, folderId)) {
            emit(PagingData.empty())
            return@flow
        }
        val sql = "SELECT * FROM media_items WHERE vaultId = ? AND folderId IS ? AND deletedAt IS NULL " +
            "AND (? IS NOT NULL OR concealed = 0) ORDER BY importedAt DESC"
        emitAll(Pager(pagingConfig) {
            dao.pagingSource(SimpleSQLiteQuery(sql, arrayOf(vaultId, folderId, folderId)))
        }.flow)
    }

    fun pagedTrash(vaultId: String, privateMode: Boolean): Flow<PagingData<MediaItemEntity>> = flow {
        if (privateMode && !access.hasHiddenGrant(vaultId)) {
            emit(PagingData.empty())
            return@flow
        }
        val authorizationSql = if (!privateMode) "" else {
            val unlocked = dao.getPrivateTrashFolderIds(vaultId).filter { access.canOpen(vaultId, it) }
            val quoted = unlocked.joinToString(",") { "'${it.replace("'", "''")}'" }
            " AND (previousFolderId IS NULL OR NOT EXISTS (SELECT 1 FROM folders f " +
                "WHERE f.id = media_items.previousFolderId AND f.vaultId = media_items.vaultId)" +
                if (quoted.isEmpty()) ")" else " OR previousFolderId IN ($quoted))"
        }
        val sql = "SELECT * FROM media_items WHERE vaultId = ? AND deletedAt IS NOT NULL " +
            "AND concealed = ?$authorizationSql ORDER BY deletedAt DESC"
        emitAll(Pager(pagingConfig) {
            dao.pagingSource(SimpleSQLiteQuery(sql, arrayOf(vaultId, if (privateMode) 1 else 0)))
        }.flow)
    }

    suspend fun authorizedFolderIds(vaultId: String, folderId: String?): List<String> {
        if (folderId != null && !access.canOpen(vaultId, folderId)) return emptyList()
        return dao.getAllIdsInFolder(vaultId, folderId)
    }

data class ViewerWindow(
    val ids: List<String>,
    val currentIndex: Int,
    val totalCount: Int
)

    suspend fun viewerWindow(
        vaultId: String,
        collection: ViewerCollection,
        aroundId: String,
        windowSize: Int = 100
    ): ViewerWindow = when (collection) {
        is ViewerCollection.Folder -> {
            if (collection.folderId != null && !access.canOpen(vaultId, collection.folderId)) {
                ViewerWindow(emptyList(), 0, 0)
            } else {
                val total = dao.countAuthorizedInFolder(vaultId, collection.folderId)
                if (total <= windowSize) {
                    val all = dao.getAllIdsInFolder(vaultId, collection.folderId)
                    val idx = all.indexOf(aroundId).coerceAtLeast(0)
                    ViewerWindow(if (aroundId in all) all else listOf(aroundId) + all, idx, total)
                } else {
                    val targetEntity = dao.getItemForVault(aroundId, vaultId)
                    val offset = if (targetEntity != null) {
                        val rank = dao.rawCount(SimpleSQLiteQuery(
                            "SELECT COUNT(*) FROM media_items WHERE vaultId = ? AND folderId IS ? AND deletedAt IS NULL AND (? IS NOT NULL OR concealed = 0) AND (importedAt > ? OR (importedAt = ? AND id > ?))",
                            arrayOf(vaultId, collection.folderId, collection.folderId, targetEntity.importedAt, targetEntity.importedAt, aroundId)
                        ))
                        (rank - windowSize / 2).coerceIn(0, (total - windowSize).coerceAtLeast(0))
                    } else 0
                    val slice = dao.getPagedIdsInFolder(vaultId, collection.folderId, windowSize, offset)
                    val finalSlice = if (aroundId in slice) slice else listOf(aroundId) + slice
                    val idx = finalSlice.indexOf(aroundId).coerceAtLeast(0)
                    ViewerWindow(finalSlice, idx, total)
                }
            }
        }
        is ViewerCollection.Gallery -> {
            val searchIds = collection.searchIds
            if (searchIds != null) {
                val visible = dao.getAllVisibleIdsForFilter(vaultId, collection.filter.ordinal).toHashSet()
                val all = searchIds.filter { it in visible }
                val total = all.size
                if (total <= windowSize) {
                    val idx = all.indexOf(aroundId).coerceAtLeast(0)
                    ViewerWindow(if (aroundId in all) all else listOf(aroundId) + all, idx, total)
                } else {
                    val idx = all.indexOf(aroundId).coerceAtLeast(0)
                    val start = (idx - windowSize / 2).coerceIn(0, (total - windowSize).coerceAtLeast(0))
                    val slice = all.subList(start, start + windowSize)
                    val finalSlice = if (aroundId in slice) slice else listOf(aroundId) + slice
                    ViewerWindow(finalSlice, finalSlice.indexOf(aroundId).coerceAtLeast(0), total)
                }
            } else {
                val filterSql = when (collection.filter) {
                    GalleryFilter.ALL -> ""
                    GalleryFilter.PHOTOS -> " AND mediaTypeCode = 0"
                    GalleryFilter.VIDEOS -> " AND mediaTypeCode = 1"
                    GalleryFilter.FAVORITES -> " AND favorite = 1"
                }
                val orderSql = orderSql(collection.sort)
                val total = dao.rawCount(SimpleSQLiteQuery(
                    "SELECT COUNT(*) FROM media_items WHERE vaultId = ? AND deletedAt IS NULL AND concealed = 0$filterSql",
                    arrayOf(vaultId)
                ))
                if (total <= windowSize) {
                    val all = dao.viewerIds(SimpleSQLiteQuery(
                        "SELECT id FROM media_items WHERE vaultId = ? AND deletedAt IS NULL AND concealed = 0$filterSql ORDER BY $orderSql",
                        arrayOf(vaultId)
                    ))
                    val idx = all.indexOf(aroundId).coerceAtLeast(0)
                    ViewerWindow(if (aroundId in all) all else listOf(aroundId) + all, idx, total)
                } else {
                    val rowNumRows = dao.viewerIds(SimpleSQLiteQuery(
                        "SELECT CAST(row_num AS TEXT) FROM (SELECT id, (ROW_NUMBER() OVER (ORDER BY $orderSql)) - 1 AS row_num FROM media_items WHERE vaultId = ? AND deletedAt IS NULL AND concealed = 0$filterSql) WHERE id = ?",
                        arrayOf(vaultId, aroundId)
                    ))
                    val rowNum = rowNumRows.firstOrNull()?.toIntOrNull() ?: 0
                    val offset = (rowNum - windowSize / 2).coerceIn(0, (total - windowSize).coerceAtLeast(0))
                    val slice = dao.viewerIds(SimpleSQLiteQuery(
                        "SELECT id FROM media_items WHERE vaultId = ? AND deletedAt IS NULL AND concealed = 0$filterSql ORDER BY $orderSql LIMIT ? OFFSET ?",
                        arrayOf(vaultId, windowSize, offset)
                    ))
                    val finalSlice = if (aroundId in slice) slice else listOf(aroundId) + slice
                    val idx = finalSlice.indexOf(aroundId).coerceAtLeast(0)
                    ViewerWindow(finalSlice, idx, total)
                }
            }
        }
    }

    suspend fun viewerIds(vaultId: String, collection: ViewerCollection): List<String> = when (collection) {
        is ViewerCollection.Folder -> authorizedFolderIds(vaultId, collection.folderId)
        is ViewerCollection.Gallery -> {
            val searchIds = collection.searchIds
            if (searchIds != null) {
                // Search results are already ordered by the search index. Recheck DB visibility on open.
                val visible = dao.getAllVisibleIdsForFilter(vaultId, collection.filter.ordinal).toHashSet()
                searchIds.filter { it in visible }
            } else {
                val filterSql = when (collection.filter) {
                    GalleryFilter.ALL -> ""
                    GalleryFilter.PHOTOS -> " AND mediaTypeCode = 0"
                    GalleryFilter.VIDEOS -> " AND mediaTypeCode = 1"
                    GalleryFilter.FAVORITES -> " AND favorite = 1"
                }
                val orderSql = orderSql(collection.sort)
                dao.viewerIds(SimpleSQLiteQuery(
                    "SELECT id FROM media_items WHERE vaultId = ? AND deletedAt IS NULL AND concealed = 0$filterSql ORDER BY $orderSql",
                    arrayOf(vaultId)
                ))
            }
        }
    }

    private fun orderSql(sort: String): String = when (sort) {
        "DATE_TAKEN_ASC" -> "COALESCE(dateTakenMs, importedAt) ASC"
        "IMPORTED_ASC" -> "importedAt ASC"
        "SIZE_DESC" -> "plaintextSize DESC"
        "SIZE_ASC" -> "plaintextSize ASC"
        "IMPORTED_DESC" -> "importedAt DESC"
        else -> "COALESCE(dateTakenMs, importedAt) DESC"
    }

    fun paged(vaultId: String, filter: GalleryFilter, sort: String): Flow<PagingData<MediaItemEntity>> {
        val filterSql = when (filter) {
            GalleryFilter.ALL -> ""
            GalleryFilter.PHOTOS -> " AND mediaTypeCode = 0"
            GalleryFilter.VIDEOS -> " AND mediaTypeCode = 1"
            GalleryFilter.FAVORITES -> " AND favorite = 1"
        }
        val orderSql = orderSql(sort)
        return Pager(pagingConfig) {
            dao.pagingSource(
                SimpleSQLiteQuery(
                    "SELECT * FROM media_items WHERE vaultId = ? AND deletedAt IS NULL AND concealed = 0$filterSql ORDER BY $orderSql",
                    arrayOf(vaultId)
                )
            )
        }.flow
    }
}
