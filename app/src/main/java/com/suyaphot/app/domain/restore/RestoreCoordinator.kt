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
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.RestoreJobEntity
import com.suyaphot.app.core.media.ConflictResolver
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.folders.FolderAccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

sealed interface RestoreResult {
    data class Success(val mediaId: String, val publicUri: Uri) : RestoreResult
    data class SuccessWithCleanupPending(val mediaId: String, val publicUri: Uri) : RestoreResult
    data class Failure(val mediaId: String, val reason: String, val vaultCopyIntact: Boolean = true) : RestoreResult
}

enum class RestorePhase(val code: Int) {
    CREATED(0), PUBLIC_PENDING_CREATED(1), WRITING(2), PLAINTEXT_VERIFIED(3),
    PUBLIC_PUBLISHED(4), VAULT_MEDIA_REMOVED(5), DB_FINALIZED(6), COMPLETED(7),
    FAILED_BEFORE_PUBLISH(8), CLEANUP_PENDING(9)
}

/**
 * Transactional MediaStore restoration pipeline with vault isolation and verification.
 */
class RestoreCoordinator(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val database: SuyaDatabase,
    private val mediaItemDao: MediaItemDao,
    private val vaultCrypto: VaultCrypto,
    private val fileStore: VaultFileStore,
    private val conflictResolver: ConflictResolver,
    private val folderAccessManager: FolderAccessManager? = null
) {

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun safeRelativePath(candidate: String?, fallback: String): String {
        val segments = candidate
            ?.replace('\\', '/')
            ?.trimStart('/')
            ?.split('/')
            ?.filter { it.isNotBlank() && it != "." && it != ".." && it.none(Char::isISOControl) }
            .orEmpty()
        return if (segments.isEmpty()) fallback else segments.joinToString("/", postfix = "/")
    }

    private fun safeDisplayName(candidate: String, itemId: String): String = candidate
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filterNot(Char::isISOControl)
        .trim()
        .take(180)
        .ifBlank { "restored_$itemId" }

    suspend fun restoreItem(
        itemId: String,
        move: Boolean,
        customRelativePath: String? = null,
        onProgress: ((current: Long, total: Long) -> Unit)? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        if (sessionManager.sessionState.value !is VaultSession.Unlocked) {
            return@withContext RestoreResult.Failure(itemId, "Vault is locked", vaultCopyIntact = true)
        }
        val session = sessionManager.acquireOperationKeyLease()
            ?: return@withContext RestoreResult.Failure(itemId, "Vault is locked", vaultCopyIntact = true)
        try {

        // Section 19: Verify item belongs to current vault session
        val item = mediaItemDao.getItemForVault(itemId, session.vaultId)
            ?: return@withContext RestoreResult.Failure(itemId, "Item not found in vault", vaultCopyIntact = false)
        if (item.concealed && (item.folderId == null || folderAccessManager?.canOpen(session.vaultId, item.folderId) != true)) {
            return@withContext RestoreResult.Failure(itemId, "PROTECTED_FOLDER_AUTH_REQUIRED", vaultCopyIntact = true)
        }

        val vaultFile = fileStore.getMediaFile(session.vaultId, itemId)
        if (!vaultFile.exists()) {
            return@withContext RestoreResult.Failure(itemId, "Encrypted vault file missing on disk", vaultCopyIntact = false)
        }

        val jobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        database.restoreJobDao().insert(
            RestoreJobEntity(jobId, session.vaultId, itemId, RestorePhase.CREATED.code, null, move, now, now, null)
        )

        // 1. Decrypt metadata
        val metadata = try {
            val decryptedBytes = Aead.decryptWithPrependedNonce(
                keyBytes = session.metaSubkey,
                payload = item.encryptedMetadata,
                aad = itemId.toByteArray(Charsets.UTF_8)
            )
            try {
                PrivateMediaMetadata.deserialize(decryptedBytes)
            } finally {
                decryptedBytes.fill(0)
            }
        } catch (e: Exception) {
            return@withContext RestoreResult.Failure(itemId, "METADATA_UNAVAILABLE", vaultCopyIntact = true)
        }

        val isVideo = item.mediaTypeCode == MediaType.VIDEO.code
        val availableVolumes = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())
        } else emptySet()
        val targetVolume = metadata.sourceVolume?.takeIf { it in availableVolumes }
            ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        fun collectionFor(volume: String): Uri = if (isVideo) {
            MediaStore.Video.Media.getContentUri(volume)
        } else MediaStore.Images.Media.getContentUri(volume)
        var collectionUri = collectionFor(targetVolume)

        val defaultFolder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val fallbackPath = "$defaultFolder/Suya Phot Restored/"
        val restoreRelPath = safeRelativePath(customRelativePath ?: metadata.originalRelativePath, fallbackPath)
        val restoredDisplayName = safeDisplayName(metadata.originalDisplayName, itemId)
        val safeMimeType = metadata.originalMimeType.takeIf {
            if (isVideo) it.startsWith("video/") else it.startsWith("image/")
        } ?: if (isVideo) "video/mp4" else "image/jpeg"

        val safeDisplayName = conflictResolver.resolveName(
            desiredName = restoredDisplayName,
            targetRelativePath = restoreRelPath,
            collectionUri = collectionUri
        )

        val targetDateTakenMs = metadata.dateTakenMs
            ?: item.dateTakenMs
            ?: metadata.dateModifiedMs
            ?: metadata.additional["dateAddedSec"]?.toLongOrNull()?.times(1000L)
            ?: System.currentTimeMillis()

        val targetDateModifiedMs = metadata.dateModifiedMs
            ?: metadata.dateTakenMs
            ?: item.dateTakenMs
            ?: metadata.additional["dateAddedSec"]?.toLongOrNull()?.times(1000L)
            ?: System.currentTimeMillis()

        val targetDateAddedSec = metadata.additional["dateAddedSec"]?.toLongOrNull()
            ?: (targetDateTakenMs / 1000L)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeDisplayName)
            put(MediaStore.MediaColumns.MIME_TYPE, safeMimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, restoreRelPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, targetDateTakenMs)
            put(MediaStore.MediaColumns.DATE_ADDED, targetDateAddedSec)
            put(MediaStore.MediaColumns.DATE_MODIFIED, targetDateModifiedMs / 1000L)
        }

        val resolver = context.contentResolver
        var insertedUri = try {
            resolver.insert(collectionUri, values)
        } catch (e: Exception) {
            SafeLog.e("RestoreCoordinator", "Failed inserting MediaStore row", e)
            null
        }
        if (insertedUri == null && targetVolume != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            collectionUri = collectionFor(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val fallbackName = conflictResolver.resolveName(restoredDisplayName, restoreRelPath, collectionUri)
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fallbackName)
            insertedUri = runCatching { resolver.insert(collectionUri, values) }.getOrNull()
        }
        insertedUri ?: return@withContext RestoreResult.Failure(itemId, "Could not insert MediaStore pending record", vaultCopyIntact = true)

        val destinationBytes = insertedUri.toString().toByteArray(Charsets.UTF_8)
        val encryptedDestination = try {
            Aead.encryptWithPrependedNonce(
                session.metaSubkey,
                destinationBytes,
                "restore:$jobId:v1".toByteArray(Charsets.UTF_8)
            )
        } finally {
            destinationBytes.fill(0)
        }
        database.restoreJobDao().updatePhase(
            jobId, RestorePhase.PUBLIC_PENDING_CREATED.code, encryptedDestination, System.currentTimeMillis()
        )

        var publicPublished = false
        try {
            database.restoreJobDao().updatePhase(jobId, RestorePhase.WRITING.code, null, System.currentTimeMillis())
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

            // Set file last-modified timestamp while pending
            runCatching {
                resolver.query(insertedUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (pathCol != -1 && !cursor.isNull(pathCol)) {
                            val path = cursor.getString(pathCol)
                            if (!path.isNullOrBlank()) {
                                java.io.File(path).setLastModified(targetDateModifiedMs)
                            }
                        }
                    }
                }
            }

            // 3. Verify integrity
            val restoredSha256Hex = bytesToHex(verify.sha256)
            check(restoredSha256Hex.equals(item.sha256Hex, ignoreCase = true)) {
                "Checksum mismatch during restoration (expected ${item.sha256Hex}, got $restoredSha256Hex)"
            }
            database.restoreJobDao().updatePhase(jobId, RestorePhase.PLAINTEXT_VERIFIED.code, null, System.currentTimeMillis())

            // 4. Publish to MediaStore (IS_PENDING = 0) & verify publish succeeded (Section 20)
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_TAKEN, targetDateTakenMs)
                put(MediaStore.MediaColumns.DATE_ADDED, targetDateAddedSec)
                put(MediaStore.MediaColumns.DATE_MODIFIED, targetDateModifiedMs / 1000L)
            }
            val updatedRows = resolver.update(insertedUri, publishValues, null, null)
            check(updatedRows == 1) { "Failed to publish restored MediaStore item: update returned $updatedRows" }

            // Ensure MediaScanner does not wipe original dates post-publish
            val postPublishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_TAKEN, targetDateTakenMs)
                put(MediaStore.MediaColumns.DATE_ADDED, targetDateAddedSec)
                put(MediaStore.MediaColumns.DATE_MODIFIED, targetDateModifiedMs / 1000L)
            }
            runCatching { resolver.update(insertedUri, postPublishValues, null, null) }
            runCatching {
                resolver.query(insertedUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (pathCol != -1 && !cursor.isNull(pathCol)) {
                            val path = cursor.getString(pathCol)
                            if (!path.isNullOrBlank()) {
                                java.io.File(path).setLastModified(targetDateModifiedMs)
                            }
                        }
                    }
                }
            }

            publicPublished = true
            database.restoreJobDao().updatePhase(jobId, RestorePhase.PUBLIC_PUBLISHED.code, null, System.currentTimeMillis())

            // Re-open and hash the public copy before any private deletion.
            resolver.openFileDescriptor(insertedUri, "r")?.use { pfd ->
                if (pfd.statSize >= 0L) {
                    check(pfd.statSize == item.plaintextSize) { "Published size mismatch" }
                }
            } ?: error("Failed to open restored public file after publish")
            val publicDigest = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(insertedUri).use { input ->
                checkNotNull(input) { "Failed to reopen restored public file" }
                val buffer = ByteArray(256 * 1024)
                try {
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        publicDigest.update(buffer, 0, read)
                    }
                } finally {
                    buffer.fill(0)
                }
            }
            check(bytesToHex(publicDigest.digest()).equals(item.sha256Hex, ignoreCase = true)) {
                "Published checksum mismatch"
            }

            // 5. Two-phase removal on Move (Section 21)
            if (move) {
                val mediaDeleted = !vaultFile.exists() || vaultFile.delete()
                val thumbFile = fileStore.getThumbFile(session.vaultId, itemId)
                if (thumbFile.exists() && !thumbFile.delete()) {
                    SafeLog.w("RestoreCoordinator", "Thumbnail cleanup pending for restored media")
                }
                val previewFile = fileStore.getPreviewFile(session.vaultId, itemId)
                if (previewFile.exists() && !previewFile.delete()) {
                    SafeLog.w("RestoreCoordinator", "Preview cleanup pending for restored media")
                }

                if (!mediaDeleted) {
                    database.restoreJobDao().updatePhase(
                        jobId, RestorePhase.CLEANUP_PENDING.code, null, System.currentTimeMillis(), "PRIVATE_MEDIA_DELETE_FAILED"
                    )
                    return@withContext RestoreResult.SuccessWithCleanupPending(itemId, insertedUri)
                }

                database.restoreJobDao().updatePhase(jobId, RestorePhase.VAULT_MEDIA_REMOVED.code, null, System.currentTimeMillis())

                val deletedRows = mediaItemDao.deleteForVault(itemId, session.vaultId)
                if (deletedRows != 1 && mediaItemDao.getItemForVault(itemId, session.vaultId) != null) {
                    database.restoreJobDao().updatePhase(
                        jobId, RestorePhase.CLEANUP_PENDING.code, null, System.currentTimeMillis(), "DB_DELETE_PENDING"
                    )
                    return@withContext RestoreResult.SuccessWithCleanupPending(itemId, insertedUri)
                }
            }

            database.restoreJobDao().updatePhase(jobId, RestorePhase.DB_FINALIZED.code, null, System.currentTimeMillis())
            database.restoreJobDao().updatePhase(jobId, RestorePhase.COMPLETED.code, null, System.currentTimeMillis())

            SafeLog.d("RestoreCoordinator", "Media restore completed (move=$move)")
            RestoreResult.Success(itemId, insertedUri)
        } catch (e: Exception) {
            SafeLog.e("RestoreCoordinator", "Media restore failed")
            if (!publicPublished) {
                database.restoreJobDao().updatePhase(
                    jobId, RestorePhase.FAILED_BEFORE_PUBLISH.code, null, System.currentTimeMillis(), "RESTORE_FAILED_BEFORE_PUBLISH"
                )
                runCatching { resolver.delete(insertedUri, null, null) }
                RestoreResult.Failure(
                    mediaId = itemId,
                    reason = "RESTORE_FAILED_BEFORE_PUBLISH",
                    vaultCopyIntact = vaultFile.exists()
                )
            } else {
                // The verified public item is now the safety anchor. Never delete it here.
                database.restoreJobDao().updatePhase(
                    jobId, RestorePhase.CLEANUP_PENDING.code, null, System.currentTimeMillis(), "PRIVATE_CLEANUP_PENDING"
                )
                RestoreResult.SuccessWithCleanupPending(itemId, insertedUri)
            }
        }
        } finally {
            session.close()
        }
    }
}
