package com.suyaphot.app.domain.backup

import org.json.JSONArray
import org.json.JSONObject

data class BackupFolderEntry(
    val id: String,
    val parentId: String?,
    val encryptedNameHex: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sortOrder: Long,
    val directHidden: Boolean,
    val effectiveHidden: Boolean,
    val lockId: String?,
    val effectiveProtected: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("parentId", parentId ?: JSONObject.NULL)
        put("encryptedNameHex", encryptedNameHex)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("sortOrder", sortOrder)
        put("directHidden", directHidden)
        put("effectiveHidden", effectiveHidden)
        put("lockId", lockId ?: JSONObject.NULL)
        put("effectiveProtected", effectiveProtected)
    }

    companion object {
        fun fromJson(json: JSONObject): BackupFolderEntry = BackupFolderEntry(
            id = json.getString("id"),
            parentId = if (json.isNull("parentId")) null else json.getString("parentId"),
            encryptedNameHex = json.getString("encryptedNameHex"),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            sortOrder = json.getLong("sortOrder"),
            directHidden = json.getBoolean("directHidden"),
            effectiveHidden = json.getBoolean("effectiveHidden"),
            lockId = if (json.isNull("lockId")) null else json.getString("lockId"),
            effectiveProtected = json.getBoolean("effectiveProtected")
        )
    }
}

data class BackupFolderLockEntry(
    val id: String,
    val folderId: String,
    val credentialTypeCode: Int,
    val credentialEnvelopeHex: String,
    val recoveryEnvelopeHex: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("folderId", folderId)
        put("credentialTypeCode", credentialTypeCode)
        put("credentialEnvelopeHex", credentialEnvelopeHex)
        put("recoveryEnvelopeHex", recoveryEnvelopeHex ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): BackupFolderLockEntry = BackupFolderLockEntry(
            id = json.getString("id"),
            folderId = json.getString("folderId"),
            credentialTypeCode = json.getInt("credentialTypeCode"),
            credentialEnvelopeHex = json.getString("credentialEnvelopeHex"),
            recoveryEnvelopeHex = if (json.isNull("recoveryEnvelopeHex")) null else json.getString("recoveryEnvelopeHex")
        )
    }
}

data class BackupMediaItemEntry(
    val id: String,
    val folderId: String?,
    val mediaTypeCode: Int,
    val encryptedMetadataHex: String,
    val plaintextSize: Long,
    val cipherSize: Long,
    val sha256Hex: String,
    val importedAt: Long,
    val updatedAt: Long,
    val favorite: Boolean,
    val deletedAt: Long?,
    val previousFolderId: String?,
    val dateTakenMs: Long?,
    val cleanupStateCode: Int,
    val concealed: Boolean,
    val hasThumb: Boolean,
    val hasPreview: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("folderId", folderId ?: JSONObject.NULL)
        put("mediaTypeCode", mediaTypeCode)
        put("encryptedMetadataHex", encryptedMetadataHex)
        put("plaintextSize", plaintextSize)
        put("cipherSize", cipherSize)
        put("sha256Hex", sha256Hex)
        put("importedAt", importedAt)
        put("updatedAt", updatedAt)
        put("favorite", favorite)
        put("deletedAt", deletedAt ?: JSONObject.NULL)
        put("previousFolderId", previousFolderId ?: JSONObject.NULL)
        put("dateTakenMs", dateTakenMs ?: JSONObject.NULL)
        put("cleanupStateCode", cleanupStateCode)
        put("concealed", concealed)
        put("hasThumb", hasThumb)
        put("hasPreview", hasPreview)
    }

    companion object {
        fun fromJson(json: JSONObject): BackupMediaItemEntry = BackupMediaItemEntry(
            id = json.getString("id"),
            folderId = if (json.isNull("folderId")) null else json.getString("folderId"),
            mediaTypeCode = json.getInt("mediaTypeCode"),
            encryptedMetadataHex = json.getString("encryptedMetadataHex"),
            plaintextSize = json.getLong("plaintextSize"),
            cipherSize = json.getLong("cipherSize"),
            sha256Hex = json.getString("sha256Hex"),
            importedAt = json.getLong("importedAt"),
            updatedAt = json.getLong("updatedAt"),
            favorite = json.getBoolean("favorite"),
            deletedAt = if (json.isNull("deletedAt")) null else json.getLong("deletedAt"),
            previousFolderId = if (json.isNull("previousFolderId")) null else json.getString("previousFolderId"),
            dateTakenMs = if (json.isNull("dateTakenMs")) null else json.getLong("dateTakenMs"),
            cleanupStateCode = json.getInt("cleanupStateCode"),
            concealed = json.getBoolean("concealed"),
            hasThumb = json.getBoolean("hasThumb"),
            hasPreview = json.getBoolean("hasPreview")
        )
    }
}

data class BackupFileDescriptor(
    val typeCode: Byte,
    val itemId: String,
    val cipherLength: Long,
    val cipherSha256Hex: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("typeCode", typeCode.toInt())
        put("itemId", itemId)
        put("cipherLength", cipherLength)
        put("cipherSha256Hex", cipherSha256Hex)
    }

    companion object {
        fun fromJson(json: JSONObject): BackupFileDescriptor = BackupFileDescriptor(
            typeCode = json.getInt("typeCode").toByte(),
            itemId = json.getString("itemId"),
            cipherLength = json.getLong("cipherLength"),
            cipherSha256Hex = json.getString("cipherSha256Hex")
        )
    }
}

data class BackupManifest(
    val archiveId: String,
    val version: Int,
    val createdAt: Long,
    val schemaVersion: Int,
    val vaultId: String,
    val vaultKindCode: Int,
    val folders: List<BackupFolderEntry>,
    val folderLocks: List<BackupFolderLockEntry>,
    val mediaItems: List<BackupMediaItemEntry>,
    val descriptors: List<BackupFileDescriptor> = emptyList()
) {
    fun toJsonString(): String {
        val root = JSONObject().apply {
            put("archiveId", archiveId)
            put("version", version)
            put("createdAt", createdAt)
            put("schemaVersion", schemaVersion)
            put("vaultId", vaultId)
            put("vaultKindCode", vaultKindCode)
            val foldersArr = JSONArray()
            folders.forEach { foldersArr.put(it.toJson()) }
            put("folders", foldersArr)
            val locksArr = JSONArray()
            folderLocks.forEach { locksArr.put(it.toJson()) }
            put("folderLocks", locksArr)
            val mediaArr = JSONArray()
            mediaItems.forEach { mediaArr.put(it.toJson()) }
            put("mediaItems", mediaArr)
            val descArr = JSONArray()
            descriptors.forEach { descArr.put(it.toJson()) }
            put("descriptors", descArr)
        }
        return root.toString()
    }

    companion object {
        fun fromJsonString(jsonStr: String): BackupManifest {
            val root = JSONObject(jsonStr)
            val foldersArr = root.getJSONArray("folders")
            val foldersList = ArrayList<BackupFolderEntry>(foldersArr.length())
            for (i in 0 until foldersArr.length()) {
                foldersList.add(BackupFolderEntry.fromJson(foldersArr.getJSONObject(i)))
            }
            val locksArr = root.getJSONArray("folderLocks")
            val locksList = ArrayList<BackupFolderLockEntry>(locksArr.length())
            for (i in 0 until locksArr.length()) {
                locksList.add(BackupFolderLockEntry.fromJson(locksArr.getJSONObject(i)))
            }
            val mediaArr = root.getJSONArray("mediaItems")
            val mediaList = ArrayList<BackupMediaItemEntry>(mediaArr.length())
            for (i in 0 until mediaArr.length()) {
                mediaList.add(BackupMediaItemEntry.fromJson(mediaArr.getJSONObject(i)))
            }
            val descList = if (root.has("descriptors")) {
                val descArr = root.getJSONArray("descriptors")
                val list = ArrayList<BackupFileDescriptor>(descArr.length())
                for (i in 0 until descArr.length()) {
                    list.add(BackupFileDescriptor.fromJson(descArr.getJSONObject(i)))
                }
                list
            } else {
                emptyList()
            }
            return BackupManifest(
                archiveId = root.getString("archiveId"),
                version = root.getInt("version"),
                createdAt = root.getLong("createdAt"),
                schemaVersion = root.getInt("schemaVersion"),
                vaultId = root.getString("vaultId"),
                vaultKindCode = root.getInt("vaultKindCode"),
                folders = foldersList,
                folderLocks = locksList,
                mediaItems = mediaList,
                descriptors = descList
            )
        }
    }
}
