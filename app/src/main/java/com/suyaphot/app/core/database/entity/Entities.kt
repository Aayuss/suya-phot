package com.suyaphot.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vaults",
    indices = [
        Index(value = ["kindCode"], unique = true)
    ]
)
data class VaultEntity(
    @PrimaryKey val id: String,
    val kindCode: Int, // 0 = REAL, 1 = SECONDARY
    val createdAt: Long,
    val schemaVersion: Int,
    val pinEnvelope: ByteArray,
    val recoveryEnvelope: ByteArray? = null,
    val biometricEnvelope: ByteArray? = null,
    val biometricIv: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultEntity) return false
        return id == other.id &&
                kindCode == other.kindCode &&
                createdAt == other.createdAt &&
                pinEnvelope.contentEquals(other.pinEnvelope)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kindCode
        result = 31 * result + pinEnvelope.contentHashCode()
        return result
    }
}

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = VaultEntity::class,
            parentColumns = ["id"],
            childColumns = ["vaultId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("vaultId"),
        Index("parentId"),
        Index(value = ["vaultId", "parentId"])
    ]
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val vaultId: String,
    val parentId: String?,
    val encryptedName: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val coverMediaId: String?,
    val sortOrder: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FolderEntity) return false
        return id == other.id &&
                vaultId == other.vaultId &&
                parentId == other.parentId &&
                encryptedName.contentEquals(other.encryptedName)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + vaultId.hashCode()
        result = 31 * result + encryptedName.contentHashCode()
        return result
    }
}

@Entity(
    tableName = "media_items",
    foreignKeys = [
        ForeignKey(
            entity = VaultEntity::class,
            parentColumns = ["id"],
            childColumns = ["vaultId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("vaultId"),
        Index("folderId"),
        Index("importedAt"),
        Index("deletedAt"),
        Index("favorite"),
        Index("mediaTypeCode"),
        Index(value = ["vaultId", "sha256Hex"])
    ]
)
data class MediaItemEntity(
    @PrimaryKey val id: String,
    val vaultId: String,
    val folderId: String?,
    val mediaTypeCode: Int, // 0 = IMAGE, 1 = VIDEO
    val encryptedMetadata: ByteArray,
    val encryptedFileRelativePath: String,
    val encryptedThumbRelativePath: String?,
    val plaintextSize: Long,
    val cipherSize: Long,
    val sha256Hex: String,
    val importedAt: Long,
    val updatedAt: Long,
    val favorite: Boolean,
    val deletedAt: Long?,
    val previousFolderId: String?,
    val dateTakenMs: Long? = null,
    val encryptedPreviewRelativePath: String? = null,
    val cleanupStateCode: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaItemEntity) return false
        return id == other.id &&
                vaultId == other.vaultId &&
                sha256Hex == other.sha256Hex
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + vaultId.hashCode()
        result = 31 * result + sha256Hex.hashCode()
        return result
    }
}

@Entity(
    tableName = "restore_jobs",
    foreignKeys = [
        ForeignKey(
            entity = VaultEntity::class,
            parentColumns = ["id"],
            childColumns = ["vaultId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("vaultId"), Index("mediaId"), Index("phaseCode")]
)
data class RestoreJobEntity(
    @PrimaryKey val id: String,
    val vaultId: String,
    val mediaId: String,
    val phaseCode: Int,
    val encryptedDestinationUri: ByteArray?,
    val move: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val safeErrorCode: String?
)

@Entity(
    tableName = "jobs",
    foreignKeys = [
        ForeignKey(
            entity = VaultEntity::class,
            parentColumns = ["id"],
            childColumns = ["vaultId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("stateCode"),
        Index("vaultId")
    ]
)
data class VaultJobEntity(
    @PrimaryKey val id: String,
    val vaultId: String,
    val typeCode: Int, // 0 = IMPORT, 1 = RESTORE
    val stateCode: Int,
    val encryptedPayload: ByteArray,
    val progressCurrent: Long,
    val progressTotal: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val errorCode: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultJobEntity) return false
        return id == other.id && stateCode == other.stateCode
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + stateCode
        return result
    }
}

@Entity(
    tableName = "intruder_events",
    indices = [
        Index("realVaultId"),
        Index("createdAt")
    ]
)
data class IntruderEventEntity(
    @PrimaryKey val id: String,
    val realVaultId: String,
    val createdAt: Long,
    val reasonCode: Int, // 1 = PIN_FAILED, 2 = LOCKOUT
    val encryptedImageRelativePath: String?,
    val encryptedDetails: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IntruderEventEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
