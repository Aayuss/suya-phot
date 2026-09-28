package com.suyaphot.app.domain.gallery

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.sqlite.db.SimpleSQLiteQuery
import com.suyaphot.app.core.database.dao.MediaItemDao
import com.suyaphot.app.core.database.entity.MediaItemEntity
import kotlinx.coroutines.flow.Flow

enum class GalleryFilter { ALL, PHOTOS, VIDEOS, FAVORITES }

class GalleryRepository(private val dao: MediaItemDao) {
    fun paged(vaultId: String, filter: GalleryFilter, sort: String): Flow<PagingData<MediaItemEntity>> {
        val filterSql = when (filter) {
            GalleryFilter.ALL -> ""
            GalleryFilter.PHOTOS -> " AND mediaTypeCode = 0"
            GalleryFilter.VIDEOS -> " AND mediaTypeCode = 1"
            GalleryFilter.FAVORITES -> " AND favorite = 1"
        }
        val orderSql = when (sort) {
            "DATE_TAKEN_ASC" -> "COALESCE(dateTakenMs, importedAt) ASC"
            "IMPORTED_ASC" -> "importedAt ASC"
            "SIZE_DESC" -> "plaintextSize DESC"
            "SIZE_ASC" -> "plaintextSize ASC"
            "IMPORTED_DESC" -> "importedAt DESC"
            else -> "COALESCE(dateTakenMs, importedAt) DESC"
        }
        return Pager(PagingConfig(pageSize = 60, prefetchDistance = 20, enablePlaceholders = false)) {
            dao.pagingSource(
                SimpleSQLiteQuery(
                    "SELECT * FROM media_items WHERE vaultId = ? AND deletedAt IS NULL$filterSql ORDER BY $orderSql",
                    arrayOf(vaultId)
                )
            )
        }.flow
    }
}
