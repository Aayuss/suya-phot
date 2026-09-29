package com.suyaphot.app.domain.gallery

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.database.dao.MediaItemDao
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

    suspend fun rebuild(vaultId: String, metaSubkey: ByteArray, dao: MediaItemDao) = withContext(Dispatchers.IO) {
        try {
            val startedAt = generation
            val next = HashMap<String, Entry>()
            var offset = 0
            while (true) {
                val batch = dao.getSearchBatch(vaultId, 100, offset)
                if (batch.isEmpty()) break
                for (entity in batch) {
                    val name = runCatching {
                        val bytes = Aead.decryptWithPrependedNonce(
                            metaSubkey, entity.encryptedMetadata, entity.id.toByteArray(Charsets.UTF_8)
                        )
                        try { PrivateMediaMetadata.deserialize(bytes).originalDisplayName.lowercase() }
                        finally { bytes.fill(0) }
                    }.getOrNull() ?: continue
                    next[entity.id] = Entry(
                        entity.id, name, entity.mediaTypeCode, entity.favorite,
                        entity.importedAt, entity.dateTakenMs, entity.plaintextSize
                    )
                }
                offset += batch.size
            }
            if (startedAt == generation) entries = next
        } finally {
            metaSubkey.fill(0)
        }
    }

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

    @Synchronized fun clear() { generation++; entries = emptyMap() }
}
