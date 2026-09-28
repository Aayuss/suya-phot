package com.suyaphot.app.domain.gallery

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.database.dao.MediaItemDao
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.model.PrivateMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class VaultSearchIndex {
    private data class Entry(val entity: MediaItemEntity, val normalizedName: String)
    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun rebuild(vaultId: String, metaSubkey: ByteArray, dao: MediaItemDao) = withContext(Dispatchers.IO) {
        try {
            entries.clear()
            dao.getAllActiveOnce(vaultId).chunked(100).forEach { batch ->
                batch.forEach { entity ->
                val name = runCatching {
                    val bytes = Aead.decryptWithPrependedNonce(
                        metaSubkey,
                        entity.encryptedMetadata,
                        entity.id.toByteArray(Charsets.UTF_8)
                    )
                    try {
                        PrivateMediaMetadata.deserialize(bytes).originalDisplayName.lowercase()
                    } finally {
                        bytes.fill(0)
                    }
                }.getOrDefault("")
                    entries[entity.id] = Entry(entity, name)
                }
            }
        } finally {
            metaSubkey.fill(0)
        }
    }

    suspend fun search(query: String, filter: GalleryFilter, sort: String): List<MediaItemEntity> =
        withContext(Dispatchers.Default) {
            val q = query.trim().lowercase()
            val comparator = when (sort) {
                "DATE_TAKEN_ASC" -> compareBy<MediaItemEntity> { it.dateTakenMs ?: it.importedAt }
                "IMPORTED_ASC" -> compareBy { it.importedAt }
                "SIZE_DESC" -> compareByDescending { it.plaintextSize }
                "SIZE_ASC" -> compareBy { it.plaintextSize }
                "IMPORTED_DESC" -> compareByDescending { it.importedAt }
                else -> compareByDescending { it.dateTakenMs ?: it.importedAt }
            }
            entries.values.asSequence()
                .filter { it.normalizedName.contains(q) || it.entity.id.lowercase().contains(q) }
                .filter {
                    when (filter) {
                        GalleryFilter.ALL -> true
                        GalleryFilter.PHOTOS -> it.entity.mediaTypeCode == 0
                        GalleryFilter.VIDEOS -> it.entity.mediaTypeCode == 1
                        GalleryFilter.FAVORITES -> it.entity.favorite
                    }
                }
                .map { it.entity }
                .sortedWith(comparator)
                .toList()
        }

    fun clear() = entries.clear()
}
