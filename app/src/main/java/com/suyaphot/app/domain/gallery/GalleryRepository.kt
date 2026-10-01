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
            "AND (? IS NOT NULL OR concealed = 0) ORDER BY COALESCE(dateTakenMs, importedAt) DESC, id DESC"
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
            "AND concealed = ?$authorizationSql ORDER BY deletedAt DESC, id DESC"
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
    val totalCount: Int,
    val absoluteStart: Int = 0
)

    suspend fun viewerWindow(
        vaultId: String,
        collection: ViewerCollection,
        aroundId: String,
        windowSize: Int = 100
    ): ViewerWindow = when (collection) {
        is ViewerCollection.Folder -> {
            if (collection.folderId != null && !access.canOpen(vaultId, collection.folderId)) {
                ViewerWindow(emptyList(), 0, 0, 0)
            } else {
                val total = dao.countAuthorizedInFolder(vaultId, collection.folderId)
                if (total <= windowSize) {
                    val all = dao.getAllIdsInFolder(vaultId, collection.folderId)
                    val idx = all.indexOf(aroundId).coerceAtLeast(0)
                    ViewerWindow(if (aroundId in all) all else listOf(aroundId) + all, idx, total, 0)
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
                    ViewerWindow(finalSlice, idx, total, offset)
                }
            }
        }
        is ViewerCollection.Gallery -> {
            val searchIds = collection.searchIds
            if (searchIds != null) {
                var start = 0
                val candidateSlice = if (searchIds.size <= windowSize * 2) {
                    searchIds
                } else {
                    val aroundIdx = searchIds.indexOf(aroundId).let { if (it == -1) 0 else it }
                    start = (aroundIdx - windowSize / 2).coerceIn(0, (searchIds.size - windowSize).coerceAtLeast(0))
                    searchIds.subList(start, (start + windowSize).coerceAtMost(searchIds.size))
                }
                val visible = dao.getVisibleIdsAmong(vaultId, candidateSlice, collection.filter.ordinal).toHashSet()
                val all = candidateSlice.filter { it in visible }
                val total = if (searchIds.size <= windowSize * 2) all.size else searchIds.size
                val finalSlice = if (aroundId in all) all else listOf(aroundId) + all
                val idx = finalSlice.indexOf(aroundId).coerceAtLeast(0)
                ViewerWindow(finalSlice, idx, total, absoluteStart = start)
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
                    ViewerWindow(if (aroundId in all) all else listOf(aroundId) + all, idx, total, 0)
                } else {
                    val target = dao.getItemForVault(aroundId, vaultId)
                    val rowNum = if (target != null) {
                        val (predicate, _, args) = buildKeysetQuery(collection.sort, target, isNext = false, vaultId = vaultId, limit = 0)
                        val countArgs = args.copyOfRange(0, args.size - 1)
                        dao.rawCount(SimpleSQLiteQuery(
                            "SELECT COUNT(*) FROM media_items WHERE vaultId = ? AND deletedAt IS NULL AND concealed = 0$filterSql AND $predicate",
                            countArgs
                        ))
                    } else 0
                    val offset = (rowNum - windowSize / 2).coerceIn(0, (total - windowSize).coerceAtLeast(0))
                    val slice = dao.viewerIds(SimpleSQLiteQuery(
                        "SELECT id FROM media_items WHERE vaultId = ? AND deletedAt IS NULL AND concealed = 0$filterSql ORDER BY $orderSql LIMIT ? OFFSET ?",
                        arrayOf(vaultId, windowSize, offset)
                    ))
                    val finalSlice = if (aroundId in slice) slice else listOf(aroundId) + slice
                    val idx = finalSlice.indexOf(aroundId).coerceAtLeast(0)
                    ViewerWindow(finalSlice, idx, total, offset)
                }
            }
        }
    }

    suspend fun fetchNextViewerBatch(
        vaultId: String,
        collection: ViewerCollection,
        afterId: String,
        limit: Int = 40
    ): List<String> = when (collection) {
        is ViewerCollection.Folder -> {
            if (collection.folderId != null && !access.canOpen(vaultId, collection.folderId)) {
                emptyList()
            } else {
                val target = dao.getItemForVault(afterId, vaultId) ?: return emptyList()
                val sql = "SELECT id FROM media_items WHERE vaultId = ? AND folderId IS ? AND deletedAt IS NULL AND (? IS NOT NULL OR concealed = 0) AND (importedAt < ? OR (importedAt = ? AND id < ?)) ORDER BY importedAt DESC, id DESC LIMIT ?"
                dao.viewerIds(SimpleSQLiteQuery(sql, arrayOf(vaultId, collection.folderId, collection.folderId, target.importedAt, target.importedAt, afterId, limit)))
            }
        }
        is ViewerCollection.Gallery -> {
            val searchIds = collection.searchIds
            if (searchIds != null) {
                val idx = searchIds.indexOf(afterId)
                if (idx != -1 && idx + 1 < searchIds.size) {
                    val result = mutableListOf<String>()
                    var cursor = idx + 1
                    val chunkSize = 200
                    while (cursor < searchIds.size && result.size < limit) {
                        val end = (cursor + chunkSize).coerceAtMost(searchIds.size)
                        val candidateChunk = searchIds.subList(cursor, end)
                        val visibleSet = dao.getVisibleIdsAmong(vaultId, candidateChunk, collection.filter.ordinal).toHashSet()
                        for (id in candidateChunk) {
                            if (id in visibleSet) {
                                result.add(id)
                                if (result.size == limit) break
                            }
                        }
                        cursor = end
                    }
                    result
                } else emptyList()
            } else {
                val target = dao.getItemForVault(afterId, vaultId) ?: return emptyList()
                val filterSql = when (collection.filter) {
                    GalleryFilter.ALL -> ""
                    GalleryFilter.PHOTOS -> " AND mediaTypeCode = 0"
                    GalleryFilter.VIDEOS -> " AND mediaTypeCode = 1"
                    GalleryFilter.FAVORITES -> " AND favorite = 1"
                }
                val (predicate, order, args) = buildKeysetQuery(collection.sort, target, isNext = true, vaultId = vaultId, limit = limit)
                dao.viewerIds(SimpleSQLiteQuery("SELECT id FROM media_items WHERE vaultId = ? AND deletedAt IS NULL AND concealed = 0$filterSql AND $predicate ORDER BY $order LIMIT ?", args))
            }
        }
    }

    suspend fun fetchPreviousViewerBatch(
        vaultId: String,
        collection: ViewerCollection,
        beforeId: String,
        limit: Int = 40
    ): List<String> = when (collection) {
        is ViewerCollection.Folder -> {
            if (collection.folderId != null && !access.canOpen(vaultId, collection.folderId)) {
                emptyList()
            } else {
                val target = dao.getItemForVault(beforeId, vaultId) ?: return emptyList()
                val sql = "SELECT id FROM media_items WHERE vaultId = ? AND folderId IS ? AND deletedAt IS NULL AND (? IS NOT NULL OR concealed = 0) AND (importedAt > ? OR (importedAt = ? AND id > ?)) ORDER BY importedAt ASC, id ASC LIMIT ?"
                val ascIds = dao.viewerIds(SimpleSQLiteQuery(sql, arrayOf(vaultId, collection.folderId, collection.folderId, target.importedAt, target.importedAt, beforeId, limit)))
                ascIds.reversed()
            }
        }
        is ViewerCollection.Gallery -> {
            val searchIds = collection.searchIds
            if (searchIds != null) {
                val idx = searchIds.indexOf(beforeId)
                if (idx > 0) {
                    val result = mutableListOf<String>()
                    var cursor = idx
                    val chunkSize = 200
                    while (cursor > 0 && result.size < limit) {
                        val start = (cursor - chunkSize).coerceAtLeast(0)
                        val candidateChunk = searchIds.subList(start, cursor)
                        val visibleSet = dao.getVisibleIdsAmong(vaultId, candidateChunk, collection.filter.ordinal).toHashSet()
                        for (i in candidateChunk.indices.reversed()) {
                            val id = candidateChunk[i]
                            if (id in visibleSet) {
                                result.add(id)
                                if (result.size == limit) break
                            }
                        }
                        cursor = start
                    }
                    result.reversed()
                } else emptyList()
            } else {
                val target = dao.getItemForVault(beforeId, vaultId) ?: return emptyList()
                val filterSql = when (collection.filter) {
                    GalleryFilter.ALL -> ""
                    GalleryFilter.PHOTOS -> " AND mediaTypeCode = 0"
                    GalleryFilter.VIDEOS -> " AND mediaTypeCode = 1"
                    GalleryFilter.FAVORITES -> " AND favorite = 1"
                }
                val (predicate, order, args) = buildKeysetQuery(collection.sort, target, isNext = false, vaultId = vaultId, limit = limit)
                val ascIds = dao.viewerIds(SimpleSQLiteQuery("SELECT id FROM media_items WHERE vaultId = ? AND deletedAt IS NULL AND concealed = 0$filterSql AND $predicate ORDER BY $order LIMIT ?", args))
                ascIds.reversed()
            }
        }
    }

    private data class KeysetParams(val predicate: String, val orderClause: String, val args: Array<Any>)

    private fun buildKeysetQuery(
        sort: String,
        target: MediaItemEntity,
        isNext: Boolean,
        vaultId: String,
        limit: Int
    ): KeysetParams {
        return when (sort) {
            "DATE_TAKEN_ASC" -> {
                val targetTime = target.dateTakenMs ?: target.importedAt
                if (isNext) {
                    KeysetParams(
                        predicate = "(COALESCE(dateTakenMs, importedAt) > ? OR (COALESCE(dateTakenMs, importedAt) = ? AND id > ?))",
                        orderClause = "COALESCE(dateTakenMs, importedAt) ASC, id ASC",
                        args = arrayOf(vaultId, targetTime, targetTime, target.id, limit)
                    )
                } else {
                    KeysetParams(
                        predicate = "(COALESCE(dateTakenMs, importedAt) < ? OR (COALESCE(dateTakenMs, importedAt) = ? AND id < ?))",
                        orderClause = "COALESCE(dateTakenMs, importedAt) DESC, id DESC",
                        args = arrayOf(vaultId, targetTime, targetTime, target.id, limit)
                    )
                }
            }
            "IMPORTED_ASC" -> {
                if (isNext) {
                    KeysetParams(
                        predicate = "(importedAt > ? OR (importedAt = ? AND id > ?))",
                        orderClause = "importedAt ASC, id ASC",
                        args = arrayOf(vaultId, target.importedAt, target.importedAt, target.id, limit)
                    )
                } else {
                    KeysetParams(
                        predicate = "(importedAt < ? OR (importedAt = ? AND id < ?))",
                        orderClause = "importedAt DESC, id DESC",
                        args = arrayOf(vaultId, target.importedAt, target.importedAt, target.id, limit)
                    )
                }
            }
            "IMPORTED_DESC" -> {
                if (isNext) {
                    KeysetParams(
                        predicate = "(importedAt < ? OR (importedAt = ? AND id < ?))",
                        orderClause = "importedAt DESC, id DESC",
                        args = arrayOf(vaultId, target.importedAt, target.importedAt, target.id, limit)
                    )
                } else {
                    KeysetParams(
                        predicate = "(importedAt > ? OR (importedAt = ? AND id > ?))",
                        orderClause = "importedAt ASC, id ASC",
                        args = arrayOf(vaultId, target.importedAt, target.importedAt, target.id, limit)
                    )
                }
            }
            "SIZE_DESC" -> {
                if (isNext) {
                    KeysetParams(
                        predicate = "(plaintextSize < ? OR (plaintextSize = ? AND id < ?))",
                        orderClause = "plaintextSize DESC, id DESC",
                        args = arrayOf(vaultId, target.plaintextSize, target.plaintextSize, target.id, limit)
                    )
                } else {
                    KeysetParams(
                        predicate = "(plaintextSize > ? OR (plaintextSize = ? AND id > ?))",
                        orderClause = "plaintextSize ASC, id ASC",
                        args = arrayOf(vaultId, target.plaintextSize, target.plaintextSize, target.id, limit)
                    )
                }
            }
            "SIZE_ASC" -> {
                if (isNext) {
                    KeysetParams(
                        predicate = "(plaintextSize > ? OR (plaintextSize = ? AND id > ?))",
                        orderClause = "plaintextSize ASC, id ASC",
                        args = arrayOf(vaultId, target.plaintextSize, target.plaintextSize, target.id, limit)
                    )
                } else {
                    KeysetParams(
                        predicate = "(plaintextSize < ? OR (plaintextSize = ? AND id < ?))",
                        orderClause = "plaintextSize DESC, id DESC",
                        args = arrayOf(vaultId, target.plaintextSize, target.plaintextSize, target.id, limit)
                    )
                }
            }
            else -> { // "DATE_TAKEN_DESC"
                val targetTime = target.dateTakenMs ?: target.importedAt
                if (isNext) {
                    KeysetParams(
                        predicate = "(COALESCE(dateTakenMs, importedAt) < ? OR (COALESCE(dateTakenMs, importedAt) = ? AND id < ?))",
                        orderClause = "COALESCE(dateTakenMs, importedAt) DESC, id DESC",
                        args = arrayOf(vaultId, targetTime, targetTime, target.id, limit)
                    )
                } else {
                    KeysetParams(
                        predicate = "(COALESCE(dateTakenMs, importedAt) > ? OR (COALESCE(dateTakenMs, importedAt) = ? AND id > ?))",
                        orderClause = "COALESCE(dateTakenMs, importedAt) ASC, id ASC",
                        args = arrayOf(vaultId, targetTime, targetTime, target.id, limit)
                    )
                }
            }
        }
    }

    suspend fun viewerIds(vaultId: String, collection: ViewerCollection): List<String> = when (collection) {
        is ViewerCollection.Folder -> authorizedFolderIds(vaultId, collection.folderId)
        is ViewerCollection.Gallery -> {
            val searchIds = collection.searchIds
            if (searchIds != null) {
                // Search results are already ordered by the search index. Recheck DB visibility on open in bounded batches.
                searchIds.chunked(500).flatMap { chunk ->
                    val visible = dao.getVisibleIdsAmong(vaultId, chunk, collection.filter.ordinal).toHashSet()
                    chunk.filter { it in visible }
                }
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
        "DATE_TAKEN_ASC" -> "COALESCE(dateTakenMs, importedAt) ASC, id ASC"
        "DATE_TAKEN_DESC" -> "COALESCE(dateTakenMs, importedAt) DESC, id DESC"
        "IMPORTED_ASC" -> "importedAt ASC, id ASC"
        "IMPORTED_DESC" -> "importedAt DESC, id DESC"
        "SIZE_ASC" -> "plaintextSize ASC, id ASC"
        "SIZE_DESC" -> "plaintextSize DESC, id DESC"
        else -> "COALESCE(dateTakenMs, importedAt) DESC, id DESC"
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
