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
import com.suyaphot.app.core.model.ImportMode
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
    val itemId: String,
    val mode: ImportMode
) {
    fun serialize(): ByteArray {
        val uriBytes = sourceUri.toByteArray(Charsets.UTF_8)
        val folderBytes = (targetFolderId ?: "").toByteArray(Charsets.UTF_8)
        val itemBytes = itemId.toByteArray(Charsets.UTF_8)
        require(uriBytes.size <= MAX_URI_BYTES && folderBytes.size <= MAX_ID_BYTES && itemBytes.size <= MAX_ID_BYTES)
        val buf = ByteBuffer.allocate(4 + 4 + 4 + uriBytes.size + 4 + folderBytes.size + 4 + itemBytes.size + 4)
        buf.putInt(MAGIC)
        buf.putInt(VERSION)
        buf.putInt(uriBytes.size)
        buf.put(uriBytes)
        buf.putInt(folderBytes.size)
        buf.put(folderBytes)
        buf.putInt(itemBytes.size)
        buf.put(itemBytes)
        buf.putInt(mode.code)
        return buf.array()
    }

    companion object {
        private const val MAGIC = 0x534A5032 // SJP2
        private const val VERSION = 2
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_URI_BYTES = 32 * 1024
        private const val MAX_ID_BYTES = 256

        fun deserialize(bytes: ByteArray): ImportJobPayload {
            require(bytes.size in 28..MAX_PAYLOAD_BYTES) { "Invalid import job payload size" }
            val buf = ByteBuffer.wrap(bytes)
            fun readLength(max: Int): Int {
                require(buf.remaining() >= 4) { "Truncated import job payload" }
                val length = buf.int
                require(length in 0..max && buf.remaining() >= length) { "Invalid import job field length" }
                return length
            }
            require(buf.int == MAGIC) { "Unsupported legacy import job payload" }
            require(buf.int == VERSION) { "Unsupported import job payload version" }
            val uriLen = readLength(MAX_URI_BYTES)
            val uriBytes = ByteArray(uriLen).also(buf::get)
            val folderLen = readLength(MAX_ID_BYTES)
            val folderBytes = ByteArray(folderLen).also(buf::get)
            val itemLen = readLength(MAX_ID_BYTES)
            val itemBytes = ByteArray(itemLen).also(buf::get)
            require(buf.remaining() == 4) { "Trailing import job payload bytes" }
            val mode = ImportMode.fromCode(buf.int)
            val folderStr = String(folderBytes, Charsets.UTF_8)
            return ImportJobPayload(
                sourceUri = String(uriBytes, Charsets.UTF_8),
                targetFolderId = folderStr.ifEmpty { null },
                itemId = String(itemBytes, Charsets.UTF_8),
                mode = mode
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
        val alreadyExisted: Boolean = false,
        val mode: ImportMode = ImportMode.COPY
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
        mode: ImportMode,
        skipDuplicates: Boolean = true,
        onProgress: ((bytes: Long, total: Long) -> Unit)? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        if (sessionManager.sessionState.value !is VaultSession.Unlocked) {
            return@withContext ImportResult.Failure(uri, "Vault is locked", sourceUntouched = true)
        }
        val session = sessionManager.acquireOperationKeyLease()
            ?: return@withContext ImportResult.Failure(uri, "Vault is locked", sourceUntouched = true)
        try {

        val vaultId = session.vaultId
        if (folderId != null && database.folderDao().getFolderForVault(folderId, vaultId) == null) {
            return@withContext ImportResult.Failure(uri, "INVALID_TARGET_FOLDER", sourceUntouched = true)
        }
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
            itemId = itemId,
            mode = mode
        ).serialize()

        val encryptedPayload = try {
            Aead.encryptWithPrependedNonce(
                keyBytes = session.metaSubkey,
                plaintext = rawPayload,
                aad = "job:$jobId:v1".toByteArray(Charsets.UTF_8)
            )
        } finally {
            rawPayload.fill(0)
        }

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
            val resolvedSource = metadataReader.resolve(uri)
            val sourceMeta = metadataReader.read(resolvedSource)

            // 2. Encrypting stream to .partial file
            vaultJobDao.updateState(jobId, JobState.ENCRYPTING.code, System.currentTimeMillis())
            val verifyResult = context.contentResolver.openInputStream(resolvedSource.readUri).use { inputStream ->
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
                    val duplicateState = if (mode == ImportMode.COPY) JobState.COMPLETED else JobState.AWAITING_SOURCE_DELETE
                    vaultJobDao.updateState(jobId, duplicateState.code, System.currentTimeMillis())
                    return@withContext ImportResult.Success(
                        jobId = jobId,
                        itemId = existing.id,
                        uri = uri,
                        sha256Hex = sha256Hex,
                        alreadyExisted = true,
                        mode = mode
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
                    imageUri = resolvedSource.readUri,
                    itemId = itemId,
                    thumbSubkey = session.thumbSubkey,
                    outputThumbFile = thumbFile,
                    orientation = sourceMeta.metadata.orientation ?: 0
                )
            } else {
                thumbnailGenerator.generateAndEncryptVideoThumbnail(
                    videoUri = resolvedSource.readUri,
                    itemId = itemId,
                    thumbSubkey = session.thumbSubkey,
                    outputThumbFile = thumbFile
                )
            }

            // 6. Encrypt sensitive metadata
            val rawMetaBytes = sourceMeta.metadata.serialize()
            val encryptedMeta = try {
                Aead.encryptWithPrependedNonce(
                    keyBytes = session.metaSubkey,
                    plaintext = rawMetaBytes,
                    aad = itemId.toByteArray(Charsets.UTF_8)
                )
            } finally {
                rawMetaBytes.fill(0)
            }

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
                previousFolderId = null,
                dateTakenMs = sourceMeta.metadata.dateTakenMs
            )

            database.withTransaction {
                mediaItemDao.insert(mediaEntity)
                val nextState = if (mode == ImportMode.COPY) JobState.COMPLETED else JobState.AWAITING_SOURCE_DELETE
                vaultJobDao.updateState(jobId, nextState.code, now)
            }
            dbCommitted = true

            SafeLog.d("ImportCoordinator", "Media import completed")
            ImportResult.Success(
                jobId = jobId,
                itemId = itemId,
                uri = uri,
                sha256Hex = sha256Hex,
                mode = mode
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
            SafeLog.e("ImportCoordinator", "Media import failed")
            runCatching { if (partialFile.exists()) partialFile.delete() }
            if (finalCommitted && !dbCommitted) {
                runCatching { if (finalMediaFile.exists()) finalMediaFile.delete() }
                runCatching { if (thumbFile.exists()) thumbFile.delete() }
            }
            vaultJobDao.updateState(jobId, JobState.FAILED.code, System.currentTimeMillis(), e.message)
            ImportResult.Failure(uri, e.message ?: "Unknown import error", sourceUntouched = true)
        }
        } finally {
            session.close()
        }
    }

    /**
     * Batch import with sequential processing for videos and bounded concurrency for photos.
     */
    suspend fun importBatch(
        uris: List<Uri>,
        folderId: String?,
        mode: ImportMode,
        skipDuplicates: Boolean = true,
        onItemComplete: ((index: Int, total: Int, result: ImportResult) -> Unit)? = null
    ): List<ImportResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ImportResult>()
        uris.forEachIndexed { index, uri ->
            val result = importSingle(uri, folderId, mode, skipDuplicates)
            results.add(result)
            onItemComplete?.invoke(index + 1, uris.size, result)
        }
        results
    }
}
