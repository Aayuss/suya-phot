package com.suyaphot.app.core.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class VaultKind(val code: Int) {
    REAL(0),
    SECONDARY(1);

    companion object {
        fun fromCode(code: Int): VaultKind = if (code == 1) SECONDARY else REAL
    }
}

enum class MediaType(val code: Int) {
    IMAGE(0),
    VIDEO(1);

    companion object {
        fun fromCode(code: Int): MediaType = if (code == 1) VIDEO else IMAGE
    }
}

enum class JobType(val code: Int) {
    IMPORT(0),
    RESTORE(1);

    companion object {
        fun fromCode(code: Int): JobType = if (code == 1) RESTORE else IMPORT
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
        fun fromCode(code: Int): JobState = entries.firstOrNull { it.code == code } ?: QUEUED
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
        fun deserialize(bytes: ByteArray): PrivateMediaMetadata {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
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
                val map = mutableMapOf<String, String>()
                for (i in 0 until addCount) {
                    map[input.readUTF()] = input.readUTF()
                }
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
    val itemCount: Int = 0
)

data class IntruderEvent(
    val id: String,
    val createdAt: Long,
    val failureType: String,
    val imageRelativePath: String?,
    val details: String
)
