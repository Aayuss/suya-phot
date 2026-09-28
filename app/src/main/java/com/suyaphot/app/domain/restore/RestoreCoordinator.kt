package com.suyaphot.app.domain.restore

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.dao.MediaItemDao
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.media.ConflictResolver
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

sealed interface RestoreResult {
    data class Success(val mediaId: String, val publicUri: Uri) : RestoreResult
    data class Failure(val mediaId: String, val reason: String, val vaultCopyIntact: Boolean = true) : RestoreResult
}

/**
 * Transactional MediaStore restoration pipeline.
 */
class RestoreCoordinator(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val mediaItemDao: MediaItemDao,
    private val vaultCrypto: VaultCrypto,
    private val fileStore: VaultFileStore,
    private val conflictResolver: ConflictResolver
) {

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    suspend fun restoreItem(
        itemId: String,
        move: Boolean,
        customRelativePath: String? = null,
        onProgress: ((current: Long, total: Long) -> Unit)? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        val session = sessionManager.sessionState.value
        if (session !is VaultSession.Unlocked) {
            return@withContext RestoreResult.Failure(itemId, "Vault is locked", vaultCopyIntact = true)
        }

        val item = mediaItemDao.getItem(itemId)
            ?: return@withContext RestoreResult.Failure(itemId, "Item not found in vault", vaultCopyIntact = false)

        val vaultFile = fileStore.getMediaFile(session.vaultId, itemId)
        if (!vaultFile.exists()) {
            return@withContext RestoreResult.Failure(itemId, "Encrypted vault file missing on disk", vaultCopyIntact = false)
        }

        // 1. Decrypt metadata
        val metadata = try {
            val decryptedBytes = Aead.decryptWithPrependedNonce(
                keyBytes = session.metaSubkey,
                payload = item.encryptedMetadata,
                aad = itemId.toByteArray(Charsets.UTF_8)
            )
            PrivateMediaMetadata.deserialize(decryptedBytes)
        } catch (e: Exception) {
            return@withContext RestoreResult.Failure(itemId, "Failed decrypting metadata: ${e.message}", vaultCopyIntact = true)
        }

        val isVideo = item.mediaTypeCode == MediaType.VIDEO.code
        val collectionUri = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val defaultFolder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val restoreRelPath = customRelativePath
            ?: metadata.originalRelativePath
            ?: "$defaultFolder/Suya Phot Restored/"

        val safeDisplayName = conflictResolver.resolveName(
            desiredName = metadata.originalDisplayName,
            targetRelativePath = restoreRelPath,
            collectionUri = collectionUri
        )

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeDisplayName)
            put(MediaStore.MediaColumns.MIME_TYPE, metadata.originalMimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, restoreRelPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            metadata.dateTakenMs?.let {
                if (isVideo) {
                    put(MediaStore.Video.Media.DATE_TAKEN, it)
                } else {
                    put(MediaStore.Images.Media.DATE_TAKEN, it)
                }
            }
        }

        val resolver = context.contentResolver
        val insertedUri = try {
            resolver.insert(collectionUri, values)
        } catch (e: Exception) {
            SafeLog.e("RestoreCoordinator", "Failed inserting MediaStore row", e)
            null
        } ?: return@withContext RestoreResult.Failure(itemId, "Could not insert MediaStore pending record", vaultCopyIntact = true)

        try {
            // 2. Stream-decrypt into destination MediaStore file descriptor
            val verify = resolver.openFileDescriptor(insertedUri, "w").use { pfd ->
                checkNotNull(pfd) { "Failed opening destination file descriptor for $insertedUri" }
                FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                    vaultCrypto.decryptTo(
                        sourceEncryptedFile = vaultFile,
                        mediaSubkey = session.mediaSubkey,
                        itemId = itemId,
                        outputStream = outputStream,
                        onProgress = onProgress
                    ).also {
                        outputStream.flush()
                        pfd.fileDescriptor.sync()
                    }
                }
            }

            // 3. Verify integrity
            val restoredSha256Hex = bytesToHex(verify.sha256)
            check(restoredSha256Hex.equals(item.sha256Hex, ignoreCase = true)) {
                "Checksum mismatch during restoration (expected ${item.sha256Hex}, got $restoredSha256Hex)"
            }

            // 4. Publish to MediaStore (IS_PENDING = 0)
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(insertedUri, publishValues, null, null)

            // 5. If Move operation, delete vault copy now that restore is verified
            if (move) {
                vaultFile.delete()
                val thumbFile = fileStore.getThumbFile(session.vaultId, itemId)
                if (thumbFile.exists()) thumbFile.delete()
                mediaItemDao.delete(itemId)
            }

            SafeLog.d("RestoreCoordinator", "Successfully restored media $itemId to $insertedUri (move=$move)")
            RestoreResult.Success(itemId, insertedUri)
        } catch (e: Exception) {
            SafeLog.e("RestoreCoordinator", "Restoration failed for $itemId", e)
            try {
                resolver.delete(insertedUri, null, null)
            } catch (ignored: Exception) {}
            RestoreResult.Failure(itemId, e.message ?: "Restoration failed", vaultCopyIntact = true)
        }
    }
}
