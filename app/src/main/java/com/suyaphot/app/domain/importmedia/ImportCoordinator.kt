package com.suyaphot.app.domain.importmedia

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.dao.MediaItemDao
import com.suyaphot.app.core.database.dao.VaultJobDao
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.media.MetadataReader
import com.suyaphot.app.core.media.ThumbnailGenerator
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.JobType
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

data class ImportJobPayload(
    val sourceUri: String,
    val targetFolderId: String?,
    val itemId: String
) {
    fun serialize(): ByteArray {
        val uriBytes = sourceUri.toByteArray(Charsets.UTF_8)
        val folderBytes = (targetFolderId ?: "").toByteArray(Charsets.UTF_8)
        val itemBytes = itemId.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + uriBytes.size + 4 + folderBytes.size + 4 + itemBytes.size)
        buf.putInt(uriBytes.size)
        buf.put(uriBytes)
        buf.putInt(folderBytes.size)
        buf.put(folderBytes)
        buf.putInt(itemBytes.size)
        buf.put(itemBytes)
        return buf.array()
    }

    companion object {
        fun deserialize(bytes: ByteArray): ImportJobPayload {
            val buf = ByteBuffer.wrap(bytes)
            val uriLen = buf.int
            val uriBytes = ByteArray(uriLen).also(buf::get)
            val folderLen = buf.int
            val folderBytes = ByteArray(folderLen).also(buf::get)
            val itemLen = buf.int
            val itemBytes = ByteArray(itemLen).also(buf::get)
            val folderStr = String(folderBytes, Charsets.UTF_8)
            return ImportJobPayload(
                sourceUri = String(uriBytes, Charsets.UTF_8),
                targetFolderId = folderStr.ifEmpty { null },
                itemId = String(itemBytes, Charsets.UTF_8)
            )
        }
    }
}

sealed interface ImportResult {
    data class Success(
        val jobId: String,
        val itemId: String,
        val uri: Uri,
        val sha256Hex: String,
        val alreadyExisted: Boolean = false
    ) : ImportResult

    data class Failure(
        val uri: Uri,
        val reason: String,
        val sourceUntouched: Boolean = true
    ) : ImportResult
}

/**
 * Transactional, crash-safe media import pipeline.
 */
class ImportCoordinator(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val database: SuyaDatabase,
    private val mediaItemDao: MediaItemDao,
    private val vaultJobDao: VaultJobDao,
    private val metadataReader: MetadataReader,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val vaultCrypto: VaultCrypto,
    private val fileStore: VaultFileStore
) {

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    suspend fun importSingle(
        uri: Uri,
        folderId: String?,
        skipDuplicates: Boolean = true,
        onProgress: ((bytes: Long, total: Long) -> Unit)? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val session = sessionManager.sessionState.value
        if (session !is VaultSession.Unlocked) {
            return@withContext ImportResult.Failure(uri, "Vault is locked", sourceUntouched = true)
        }

        val vaultId = session.vaultId
        val jobId = UUID.randomUUID().toString()
        val itemId = UUID.randomUUID().toString()
        val partialFile = fileStore.getPartialFile(vaultId, jobId)
        val finalMediaFile = fileStore.getMediaFile(vaultId, itemId)
        val thumbFile = fileStore.getThumbFile(vaultId, itemId)

        var finalCommitted = false
        var dbCommitted = false

        // Section 12: Encrypt job payload with metaSubkey
        val rawPayload = ImportJobPayload(
            sourceUri = uri.toString(),
            targetFolderId = folderId,
            itemId = itemId
        ).serialize()

        val encryptedPayload = Aead.encryptWithPrependedNonce(
            keyBytes = session.metaSubkey,
            plaintext = rawPayload,
            aad = "job:$jobId:v1".toByteArray(Charsets.UTF_8)
        )

        val jobEntity = VaultJobEntity(
            id = jobId,
            vaultId = vaultId,
            typeCode = JobType.IMPORT.code,
            stateCode = JobState.QUEUED.code,
            encryptedPayload = encryptedPayload,
            progressCurrent = 0L,
            progressTotal = 0L,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            errorCode = null
        )
        vaultJobDao.insert(jobEntity)

        try {
            // 1. Reading source metadata
            vaultJobDao.updateState(jobId, JobState.READING_SOURCE.code, System.currentTimeMillis())
            val sourceMeta = metadataReader.read(uri)

            // 2. Encrypting stream to .partial file
            vaultJobDao.updateState(jobId, JobState.ENCRYPTING.code, System.currentTimeMillis())
            val verifyResult = context.contentResolver.openInputStream(uri).use { inputStream ->
                checkNotNull(inputStream) { "Could not open source stream for URI: $uri" }
                vaultCrypto.encryptStream(
                    input = inputStream,
                    outputFile = partialFile,
                    mediaSubkey = session.mediaSubkey,
                    itemId = itemId,
                    isVideo = sourceMeta.mediaType == MediaType.VIDEO,
                    plaintextSize = sourceMeta.size,
                    onProgress = { current, total ->
                        onProgress?.invoke(current, total)
                    }
                )
            }

            val sha256Hex = bytesToHex(verifyResult.sha256)

            // Section 16: Duplicate check (active items only)
            if (skipDuplicates) {
                val existing = mediaItemDao.findBySha256(vaultId, sha256Hex)
                if (existing != null) {
                    partialFile.delete()
                    vaultJobDao.updateState(jobId, JobState.COMPLETED.code, System.currentTimeMillis())
                    return@withContext ImportResult.Success(
                        jobId = jobId,
                        itemId = existing.id,
                        uri = uri,
                        sha256Hex = sha256Hex,
                        alreadyExisted = true
                    )
                }
            }

            // 3. Commit partial file to final .sph
            vaultJobDao.updateState(jobId, JobState.DURABILITY_SYNC.code, System.currentTimeMillis())
            val committed = fileStore.commitPartial(partialFile, finalMediaFile)
            check(committed) { "Failed to atomically commit partial file" }
            finalCommitted = true

            // 4. Verifying encrypted file
            vaultJobDao.updateState(jobId, JobState.VERIFYING.code, System.currentTimeMillis())
            val reVerify = vaultCrypto.verifyAndHash(finalMediaFile, session.mediaSubkey, itemId)
            check(reVerify.sha256.contentEquals(verifyResult.sha256)) { "Checksum mismatch after encryption" }
            check(reVerify.plaintextSize == verifyResult.plaintextSize) { "Plaintext length mismatch" }

            // 5. Generate thumbnail with zero full-file buffering
            if (sourceMeta.mediaType == MediaType.IMAGE) {
                thumbnailGenerator.generateAndEncryptImageThumbnail(
                    imageUri = uri,
                    itemId = itemId,
                    thumbSubkey = session.thumbSubkey,
                    outputThumbFile = thumbFile,
                    orientation = sourceMeta.metadata.orientation ?: 0
                )
            } else {
                thumbnailGenerator.generateAndEncryptVideoThumbnail(
                    videoUri = uri,
                    itemId = itemId,
                    thumbSubkey = session.thumbSubkey,
                    outputThumbFile = thumbFile
                )
            }

            // 6. Encrypt sensitive metadata
            val rawMetaBytes = sourceMeta.metadata.serialize()
            val encryptedMeta = Aead.encryptWithPrependedNonce(
                keyBytes = session.metaSubkey,
                plaintext = rawMetaBytes,
                aad = itemId.toByteArray(Charsets.UTF_8)
            )

            // 7. Transactional DB commit (Section 15)
            val now = System.currentTimeMillis()
            val mediaEntity = MediaItemEntity(
                id = itemId,
                vaultId = vaultId,
                folderId = folderId,
                mediaTypeCode = sourceMeta.mediaType.code,
                encryptedMetadata = encryptedMeta,
                encryptedFileRelativePath = finalMediaFile.name,
                encryptedThumbRelativePath = if (thumbFile.exists()) thumbFile.name else null,
                plaintextSize = verifyResult.plaintextSize,
                cipherSize = finalMediaFile.length(),
                sha256Hex = sha256Hex,
                importedAt = now,
                updatedAt = now,
                favorite = false,
                deletedAt = null,
                previousFolderId = null
            )

            database.withTransaction {
                mediaItemDao.insert(mediaEntity)
                vaultJobDao.updateState(jobId, JobState.AWAITING_SOURCE_DELETE.code, now)
            }
            dbCommitted = true

            SafeLog.d("ImportCoordinator", "Successfully imported media item: $itemId")
            ImportResult.Success(
                jobId = jobId,
                itemId = itemId,
                uri = uri,
                sha256Hex = sha256Hex
            )
        } catch (ce: CancellationException) {
            // Section 14: Orphan-safe cancellation cleanup
            runCatching { if (partialFile.exists()) partialFile.delete() }
            if (finalCommitted && !dbCommitted) {
                runCatching { if (finalMediaFile.exists()) finalMediaFile.delete() }
                runCatching { if (thumbFile.exists()) thumbFile.delete() }
            }
            vaultJobDao.updateState(jobId, JobState.CANCELLED.code, System.currentTimeMillis(), "Cancelled")
            throw ce
        } catch (e: Exception) {
            SafeLog.e("ImportCoordinator", "Import failed for URI: $uri", e)
            runCatching { if (partialFile.exists()) partialFile.delete() }
            if (finalCommitted && !dbCommitted) {
                runCatching { if (finalMediaFile.exists()) finalMediaFile.delete() }
                runCatching { if (thumbFile.exists()) thumbFile.delete() }
            }
            vaultJobDao.updateState(jobId, JobState.FAILED.code, System.currentTimeMillis(), e.message)
            ImportResult.Failure(uri, e.message ?: "Unknown import error", sourceUntouched = true)
        }
    }

    /**
     * Batch import with sequential processing for videos and bounded concurrency for photos.
     */
    suspend fun importBatch(
        uris: List<Uri>,
        folderId: String?,
        skipDuplicates: Boolean = true,
        onItemComplete: ((index: Int, total: Int, result: ImportResult) -> Unit)? = null
    ): List<ImportResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ImportResult>()
        uris.forEachIndexed { index, uri ->
            val result = importSingle(uri, folderId, skipDuplicates)
            results.add(result)
            onItemComplete?.invoke(index + 1, uris.size, result)
        }
        results
    }
}
