package com.suyaphot.app.core.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class VaultKind(val code: Int) {
    REAL(0),
    SECONDARY(1);

    companion object {
        fun fromCode(code: Int): VaultKind =
            entries.firstOrNull { it.code == code } ?: error("Unknown VaultKind code: $code")
    }
}

enum class MediaType(val code: Int) {
    IMAGE(0),
    VIDEO(1);

    companion object {
        fun fromCode(code: Int): MediaType =
            entries.firstOrNull { it.code == code } ?: error("Unknown MediaType code: $code")
    }
}

enum class JobType(val code: Int) {
    IMPORT(0),
    RESTORE(1);

    companion object {
        fun fromCode(code: Int): JobType =
            entries.firstOrNull { it.code == code } ?: error("Unknown JobType code: $code")
    }
}

enum class JobState(val code: Int) {
    QUEUED(0),
    READING_SOURCE(1),
    ENCRYPTING(2),
    DURABILITY_SYNC(3),
    VERIFYING(4),
    DB_COMMITTED(5),
    AWAITING_SOURCE_DELETE(6),
    COMPLETED(7),
    CANCELLED(8),
    FAILED(9);

    companion object {
        fun fromCode(code: Int): JobState =
            entries.firstOrNull { it.code == code } ?: error("Unknown JobState code: $code")
    }
}

enum class ImportMode(val code: Int) {
    COPY(0),
    MOVE(1);

    companion object {
        fun fromCode(code: Int): ImportMode = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unknown import mode")
    }
}

enum class SourceDisposition(val code: Int) {
    NOT_APPLICABLE(0),
    PENDING_DELETE(1),
    DELETED(2),
    RETAINED_BY_USER(3),
    RETAINED_AFTER_INTERRUPTION(4),
    DELETE_FAILED(5);

    companion object {
        fun fromCode(code: Int?): SourceDisposition? = entries.firstOrNull { it.code == code }
    }
}

data class PrivateMediaMetadata(
    val originalDisplayName: String,
    val originalRelativePath: String?,
    val originalMimeType: String,
    val originalContentUri: String?,
    val dateTakenMs: Long?,
    val dateModifiedMs: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val orientation: Int?,
    val sourceVolume: String?,
    val sourceMediaStoreId: Long?,
    val gpsWasAvailable: Boolean,
    val originalFileExtension: String?,
    val additional: Map<String, String> = emptyMap()
) {
    fun serialize(): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(METADATA_MAGIC)
            out.writeInt(METADATA_VERSION)
            out.writeUTF(originalDisplayName)
            out.writeBoolean(originalRelativePath != null)
            originalRelativePath?.let { out.writeUTF(it) }
            out.writeUTF(originalMimeType)
            out.writeBoolean(originalContentUri != null)
            originalContentUri?.let { out.writeUTF(it) }
            out.writeLong(dateTakenMs ?: -1L)
            out.writeLong(dateModifiedMs ?: -1L)
            out.writeInt(width ?: -1)
            out.writeInt(height ?: -1)
            out.writeLong(durationMs ?: -1L)
            out.writeInt(orientation ?: 0)
            out.writeBoolean(sourceVolume != null)
            sourceVolume?.let { out.writeUTF(it) }
            out.writeLong(sourceMediaStoreId ?: -1L)
            out.writeBoolean(gpsWasAvailable)
            out.writeBoolean(originalFileExtension != null)
            originalFileExtension?.let { out.writeUTF(it) }
            out.writeInt(additional.size)
            for ((k, v) in additional) {
                out.writeUTF(k)
                out.writeUTF(v)
            }
        }
        return baos.toByteArray()
    }

    companion object {
        private const val METADATA_MAGIC = 0x53504D31 // "SPM1"
        private const val METADATA_VERSION = 1

        fun deserialize(bytes: ByteArray): PrivateMediaMetadata {
            require(bytes.size in 1..1_048_576) { "Invalid metadata payload size" }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val isVersioned = if (bytes.size >= 8) {
                    val magic = ((bytes[0].toInt() and 0xFF) shl 24) or
                            ((bytes[1].toInt() and 0xFF) shl 16) or
                            ((bytes[2].toInt() and 0xFF) shl 8) or
                            (bytes[3].toInt() and 0xFF)
                    magic == METADATA_MAGIC
                } else false

                if (isVersioned) {
                    input.readInt() // skip magic
                    val version = input.readInt()
                    require(version == METADATA_VERSION) { "Unsupported metadata version: $version" }
                }

                val displayName = input.readUTF()
                val relPath = if (input.readBoolean()) input.readUTF() else null
                val mimeType = input.readUTF()
                val contentUri = if (input.readBoolean()) input.readUTF() else null
                val dateTaken = input.readLong().let { if (it == -1L) null else it }
                val dateModified = input.readLong().let { if (it == -1L) null else it }
                val width = input.readInt().let { if (it == -1) null else it }
                val height = input.readInt().let { if (it == -1) null else it }
                val duration = input.readLong().let { if (it == -1L) null else it }
                val orientation = input.readInt()
                val volume = if (input.readBoolean()) input.readUTF() else null
                val mediaStoreId = input.readLong().let { if (it == -1L) null else it }
                val gps = input.readBoolean()
                val ext = if (input.readBoolean()) input.readUTF() else null
                val addCount = input.readInt()
                require(addCount in 0..100) { "Suspicious metadata additional count: $addCount" }
                val map = mutableMapOf<String, String>()
                for (i in 0 until addCount) {
                    val key = input.readUTF()
                    val value = input.readUTF()
                    require(key.length <= 128 && value.length <= 4096) { "Metadata field exceeds limit" }
                    map[key] = value
                }
                require(input.available() == 0) { "Trailing metadata bytes" }
                return PrivateMediaMetadata(
                    originalDisplayName = displayName,
                    originalRelativePath = relPath,
                    originalMimeType = mimeType,
                    originalContentUri = contentUri,
                    dateTakenMs = dateTaken,
                    dateModifiedMs = dateModified,
                    width = width,
                    height = height,
                    durationMs = duration,
                    orientation = orientation,
                    sourceVolume = volume,
                    sourceMediaStoreId = mediaStoreId,
                    gpsWasAvailable = gps,
                    originalFileExtension = ext,
                    additional = map
                )
            }
        }
    }
}

data class MediaItem(
    val id: String,
    val vaultId: String,
    val folderId: String?,
    val type: MediaType,
    val plaintextSize: Long,
    val cipherSize: Long,
    val sha256Hex: String,
    val importedAt: Long,
    val updatedAt: Long,
    val favorite: Boolean,
    val deletedAt: Long?,
    val previousFolderId: String?,
    // Decrypted on demand for viewer / details / list:
    val metadata: PrivateMediaMetadata? = null
)

data class Folder(
    val id: String,
    val vaultId: String,
    val parentId: String?,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val coverMediaId: String?,
    val sortOrder: Long,
    val itemCount: Int = 0,
    val childFolderCount: Int = 0,
    val directHidden: Boolean = false,
    val effectiveHidden: Boolean = false,
    val lockId: String? = null,
    val effectiveProtected: Boolean = false
)

data class IntruderEvent(
    val id: String,
    val createdAt: Long,
    val failureType: String,
    val imageRelativePath: String?,
    val details: String
)
