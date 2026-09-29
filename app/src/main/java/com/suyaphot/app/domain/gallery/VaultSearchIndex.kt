package com.suyaphot.app.domain.gallery

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.database.dao.MediaItemDao
import com.suyaphot.app.core.database.dao.VisibleSearchHeader
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.model.PrivateMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A small-field, visible-only index. Encrypted metadata is fetched in DB batches. */
class VaultSearchIndex {
    private data class Entry(
        val mediaId: String,
        val normalizedName: String,
        val mediaTypeCode: Int,
        val favorite: Boolean,
        val importedAt: Long,
        val dateTakenMs: Long?,
        val plaintextSize: Long
    )

    @Volatile private var entries: Map<String, Entry> = emptyMap()
    @Volatile private var generation = 0L
    @Volatile private var indexedVaultId: String? = null

    suspend fun rebuild(vaultId: String, metaSubkey: ByteArray, dao: MediaItemDao) = withContext(Dispatchers.IO) {
        try {
            val startedAt = generation
            val previous = if (indexedVaultId == vaultId) entries else emptyMap()
            val headers = dao.getVisibleSearchHeaders(vaultId)
            val headersById = headers.associateBy { it.id }
            val next = HashMap<String, Entry>(headers.size)
            val newlyVisible = headers.filter { it.id !in previous }
            for (header in headers) {
                previous[header.id]?.let { old ->
                    next[header.id] = old.withHeader(header)
                }
            }
            for (ids in newlyVisible.map { it.id }.chunked(100)) {
                val batch = dao.getItemsByIdsForVault(vaultId, ids)
                for (entity in batch.filter { it.deletedAt == null && !it.concealed }) {
                    val name = runCatching {
                        val bytes = Aead.decryptWithPrependedNonce(
                            metaSubkey, entity.encryptedMetadata, entity.id.toByteArray(Charsets.UTF_8)
                        )
                        try { PrivateMediaMetadata.deserialize(bytes).originalDisplayName.lowercase() }
                        finally { bytes.fill(0) }
                    }.getOrNull() ?: continue
                    val header = headersById[entity.id] ?: continue
                    next[entity.id] = Entry(entity.id, name, header.mediaTypeCode, header.favorite,
                        header.importedAt, header.dateTakenMs, header.plaintextSize)
                }
            }
            if (startedAt == generation) {
                indexedVaultId = vaultId
                entries = next
            }
        } finally {
            metaSubkey.fill(0)
        }
    }

    private fun Entry.withHeader(header: VisibleSearchHeader): Entry = copy(
        mediaTypeCode = header.mediaTypeCode,
        favorite = header.favorite,
        importedAt = header.importedAt,
        dateTakenMs = header.dateTakenMs,
        plaintextSize = header.plaintextSize
    )

    suspend fun search(query: String, filter: GalleryFilter, sort: String, vaultId: String, dao: MediaItemDao): List<MediaItemEntity> =
        withContext(Dispatchers.Default) {
            val q = query.trim().lowercase()
            val comparator = when (sort) {
                "DATE_TAKEN_ASC" -> compareBy<Entry> { it.dateTakenMs ?: it.importedAt }
                "IMPORTED_ASC" -> compareBy { it.importedAt }
                "SIZE_DESC" -> compareByDescending { it.plaintextSize }
                "SIZE_ASC" -> compareBy { it.plaintextSize }
                "IMPORTED_DESC" -> compareByDescending { it.importedAt }
                else -> compareByDescending { it.dateTakenMs ?: it.importedAt }
            }
            val ids = entries.values.asSequence()
                .filter { it.normalizedName.contains(q) || it.mediaId.lowercase().contains(q) }
                .filter {
                    when (filter) {
                        GalleryFilter.ALL -> true
                        GalleryFilter.PHOTOS -> it.mediaTypeCode == 0
                        GalleryFilter.VIDEOS -> it.mediaTypeCode == 1
                        GalleryFilter.FAVORITES -> it.favorite
                    }
                }
                .sortedWith(comparator)
                .map { it.mediaId }
                .toList()
            val rows = ids.chunked(100).flatMap { dao.getItemsByIdsForVault(vaultId, it) }
                .filter { it.deletedAt == null && !it.concealed }
                .associateBy { it.id }
            ids.mapNotNull(rows::get)
        }

    @Synchronized fun clear() { generation++; indexedVaultId = null; entries = emptyMap() }
}
