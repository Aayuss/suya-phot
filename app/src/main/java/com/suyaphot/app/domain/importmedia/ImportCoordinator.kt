package com.suyaphot.app.domain.importmedia

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.model.SourceDisposition
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.folders.FolderAccessManager
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

data class ImportJobPayload(
    val sourceUri: String,
    val targetFolderId: String?,
    val itemId: String,
    val mode: ImportMode,
    val stagedMedia: StagedMedia? = null
) {
    data class StagedMedia(
        val mediaTypeCode: Int,
        val plaintextSize: Long,
        val sha256Hex: String,
        val encryptedMetadata: ByteArray,
        val thumbnailFileName: String?,
        val dateTakenMs: Long?,
        val importedAt: Long,
        val concealed: Boolean
    )

    fun serialize(): ByteArray {
        val uriBytes = sourceUri.toByteArray(Charsets.UTF_8)
        val folderBytes = (targetFolderId ?: "").toByteArray(Charsets.UTF_8)
        val itemBytes = itemId.toByteArray(Charsets.UTF_8)
        require(uriBytes.size <= MAX_URI_BYTES && folderBytes.size <= MAX_ID_BYTES && itemBytes.size <= MAX_ID_BYTES)
        val thumbBytes = stagedMedia?.thumbnailFileName?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val shaBytes = stagedMedia?.sha256Hex?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0)
        val stagedSize = stagedMedia?.let { 4 + 8 + 4 + shaBytes.size + 4 + it.encryptedMetadata.size + 4 + thumbBytes.size + 8 + 8 + 1 } ?: 0
        require(thumbBytes.size <= MAX_ID_BYTES && shaBytes.size == 64 || stagedMedia == null)
        require(stagedMedia == null || stagedMedia.encryptedMetadata.size in 1..MAX_METADATA_BYTES)
        val buf = ByteBuffer.allocate(4 + 4 + 4 + uriBytes.size + 4 + folderBytes.size + 4 + itemBytes.size + 4 + stagedSize)
        buf.putInt(MAGIC)
        buf.putInt(if (stagedMedia == null) VERSION else STAGED_VERSION)
        buf.putInt(uriBytes.size)
        buf.put(uriBytes)
        buf.putInt(folderBytes.size)
        buf.put(folderBytes)
        buf.putInt(itemBytes.size)
        buf.put(itemBytes)
        buf.putInt(mode.code)
        stagedMedia?.let { staged ->
            buf.putInt(staged.mediaTypeCode)
            buf.putLong(staged.plaintextSize)
            buf.putInt(shaBytes.size).put(shaBytes)
            buf.putInt(staged.encryptedMetadata.size).put(staged.encryptedMetadata)
            buf.putInt(thumbBytes.size).put(thumbBytes)
            buf.putLong(staged.dateTakenMs ?: -1L)
            buf.putLong(staged.importedAt)
            buf.put(if (staged.concealed) 1.toByte() else 0.toByte())
        }
        return buf.array()
    }

    companion object {
        private const val MAGIC = 0x534A5032 // SJP2
        private const val VERSION = 2
        private const val STAGED_VERSION = 3
        private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
        private const val MAX_URI_BYTES = 32 * 1024
        private const val MAX_ID_BYTES = 256
        private const val MAX_METADATA_BYTES = 1024 * 1024

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
            val version = buf.int
            require(version == VERSION || version == STAGED_VERSION) { "Unsupported import job payload version" }
            val uriLen = readLength(MAX_URI_BYTES)
            val uriBytes = ByteArray(uriLen).also(buf::get)
            val folderLen = readLength(MAX_ID_BYTES)
            val folderBytes = ByteArray(folderLen).also(buf::get)
            val itemLen = readLength(MAX_ID_BYTES)
            val itemBytes = ByteArray(itemLen).also(buf::get)
            require(buf.remaining() >= 4) { "Truncated import mode" }
            val mode = ImportMode.fromCode(buf.int)
            val staged = if (version == STAGED_VERSION) {
                require(buf.remaining() >= 12) { "Truncated staged media" }
                val mediaType = buf.int
                val plaintextSize = buf.long
                require(mediaType in 0..1 && plaintextSize >= 0L)
                val shaLen = readLength(64)
                require(shaLen == 64)
                val sha = ByteArray(shaLen).also(buf::get)
                val metaLen = readLength(MAX_METADATA_BYTES)
                require(metaLen > 0)
                val metadata = ByteArray(metaLen).also(buf::get)
                val thumbLen = readLength(MAX_ID_BYTES)
                val thumb = ByteArray(thumbLen).also(buf::get)
                require(buf.remaining() == 17) { "Invalid staged media tail" }
                val dateTaken = buf.long.let { if (it < 0L) null else it }
                val importedAt = buf.long
                val concealedByte = buf.get().toInt()
                require(concealedByte in 0..1 && importedAt >= 0L)
                StagedMedia(
                    mediaType, plaintextSize, String(sha, Charsets.US_ASCII), metadata,
                    String(thumb, Charsets.UTF_8).ifEmpty { null }, dateTaken, importedAt, concealedByte == 1
                )
            } else null
            require(!buf.hasRemaining()) { "Trailing import job payload bytes" }
            val folderStr = String(folderBytes, Charsets.UTF_8)
            return ImportJobPayload(
                sourceUri = String(uriBytes, Charsets.UTF_8),
                targetFolderId = folderStr.ifEmpty { null },
                itemId = String(itemBytes, Charsets.UTF_8),
                mode = mode,
                stagedMedia = staged
            )
        }
    }
}

sealed interface ImportResult {
    data class Success(
        val jobId: String,
        val itemId: String,
        /** URI used to read the source during import. */
        val uri: Uri,
        /**
         * Canonical MediaStore URI used for MOVE deletion when it can be resolved.
         * Android Photo Picker URIs are intentionally read-only and cannot reliably
         * be passed to MediaStore.createDeleteRequest().
         */
        val deletionUri: Uri = uri,
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

enum class ImportErrorCode {
    INVALID_TARGET_FOLDER,
    SOURCE_UNAVAILABLE,
    PERMISSION_DENIED,
    UNSUPPORTED_MEDIA,
    ENCRYPTION_FAILED,
    VERIFY_FAILED,
    DATABASE_FAILED,
    CANCELLED,
    UNKNOWN
}

/**
 * Photo Picker URIs are mediated read-only handles. When the provider exposes the
 * original MediaStore volume + row ID, reconstruct the canonical item URI so MOVE
 * can request deletion consent against the real public media row.
 */
internal fun canonicalDeletionUri(
    fallback: Uri,
    mediaType: MediaType,
    metadata: PrivateMediaMetadata,
    context: Context? = null
): Uri {
    val segments = fallback.pathSegments
    val isLocalPhotoPickerUri =
        fallback.authority == MediaStore.AUTHORITY &&
            segments.size >= 4 &&
            (segments[0] == "picker" || segments[0] == "picker_get_content") &&
            isLocalPhotoPickerProvider(segments) &&
            segments.getOrNull(segments.size - 2) == "media"

    val pickerLocalId = if (isLocalPhotoPickerUri) {
        segments.lastOrNull()?.toLongOrNull()?.takeIf { it > 0L }
    } else {
        null
    }

    val id = metadata.sourceMediaStoreId?.takeIf { it > 0L }
        ?: pickerLocalId
        ?: resolveMediaStoreIdFromCatalog(context, mediaType, metadata)
        ?: return fallback

    if (id <= 0L) return fallback

    val volume = metadata.sourceVolume?.takeIf { it.isNotBlank() }
        ?: MediaStore.VOLUME_EXTERNAL_PRIMARY

    return runCatching {
        val collection = when (mediaType) {
            MediaType.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            MediaType.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
        }
        ContentUris.withAppendedId(collection, id)
    }.getOrDefault(fallback)
}

private fun isLocalPhotoPickerProvider(segments: List<String>): Boolean {
    val provider = if (segments.size >= 5) segments[2] else if (segments.size >= 4) segments[1] else null
    if (provider == null || provider == "media") return true
    if (provider.startsWith("com.example.") || provider.contains("cloud") || provider.contains("drive")) {
        return false
    }
    return provider == "com.android.providers.media.photopicker" ||
        provider == "com.google.android.providers.media.module" ||
        provider == "com.android.providers.media" ||
        provider.endsWith(".providers.media.photopicker") ||
        provider.endsWith(".providers.media.module") ||
        provider.endsWith(".providers.media")
}

private fun resolveMediaStoreIdFromCatalog(
    context: Context?,
    mediaType: MediaType,
    metadata: PrivateMediaMetadata
): Long? {
    val ctx = context ?: return null
    val displayName = metadata.originalDisplayName.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val collection = when (mediaType) {
            MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(displayName)
        ctx.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst() && cursor.count == 1) {
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                if (idCol != -1 && !cursor.isNull(idCol)) {
                    val candidate = cursor.getLong(idCol)
                    if (candidate > 0L) candidate else null
                } else null
            } else null
        }
    }.getOrNull()
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
    private val fileStore: VaultFileStore,
    private val folderAccessManager: FolderAccessManager
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
            return@withContext ImportResult.Failure(uri, "VAULT_LOCKED", sourceUntouched = true)
        }
        val session = sessionManager.acquireOperationKeyLease()
            ?: return@withContext ImportResult.Failure(uri, "VAULT_LOCKED", sourceUntouched = true)
        try {

        val vaultId = session.vaultId
        val targetFolder = folderId?.let { database.folderDao().getFolderForVault(it, vaultId) }
        if (folderId != null && targetFolder == null) {
            return@withContext ImportResult.Failure(uri, "INVALID_TARGET_FOLDER", sourceUntouched = true)
        }
        if (folderId != null && !folderAccessManager.canOpen(vaultId, folderId)) {
            return@withContext ImportResult.Failure(uri, "TARGET_FOLDER_LOCKED", sourceUntouched = true)
        }
        val jobId = UUID.randomUUID().toString()
        val itemId = UUID.randomUUID().toString()
        val partialFile = fileStore.getPartialFile(vaultId, jobId)
        val finalMediaFile = fileStore.getMediaFile(vaultId, itemId)
        val thumbFile = fileStore.getThumbFile(vaultId, itemId)
        val previewFile = fileStore.getPreviewFile(vaultId, itemId)

        var finalCommitted = false
        var dbCommitted = false
        var stagedPersisted = false

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
            val deletionUri = if (mode == ImportMode.MOVE) {
                canonicalDeletionUri(uri, sourceMeta.mediaType, sourceMeta.metadata, context)
            } else {
                uri
            }

            // 2. Encrypting stream to .partial file
            vaultJobDao.updateState(jobId, JobState.ENCRYPTING.code, System.currentTimeMillis())
            val progressUpdates = Channel<Pair<Long, Long>>(Channel.CONFLATED)
            val progressWriter = launch(Dispatchers.IO) {
                var lastWriteAt = 0L
                for ((current, total) in progressUpdates) {
                    val nowProgress = System.currentTimeMillis()
                    if (nowProgress - lastWriteAt >= 250L || current >= total) {
                        vaultJobDao.updateProgress(jobId, current, total, nowProgress)
                        lastWriteAt = nowProgress
                    }
                }
            }
            val verifyResult = try {
                context.contentResolver.openInputStream(resolvedSource.readUri).use { inputStream ->
                    checkNotNull(inputStream) { "Could not open source stream for URI: $uri" }
                    vaultCrypto.encryptStream(
                        input = inputStream,
                        outputFile = partialFile,
                        mediaSubkey = session.mediaSubkey,
                        itemId = itemId,
                        isVideo = sourceMeta.mediaType == MediaType.VIDEO,
                        plaintextSize = sourceMeta.size,
                        onProgress = { current, total ->
                            progressUpdates.trySend(current to total)
                            onProgress?.invoke(current, total)
                        }
                    )
                }
            } finally {
                progressUpdates.close()
                progressWriter.join()
            }

            val sha256Hex = bytesToHex(verifyResult.sha256)

            // Section 16: Duplicate check (active items only)
            if (skipDuplicates) {
                val existing = mediaItemDao.findBySha256(vaultId, sha256Hex)
                if (existing != null) {
                    partialFile.delete()
                    val nowDuplicate = System.currentTimeMillis()
                    if (mode == ImportMode.COPY) {
                        vaultJobDao.updateTerminalImportState(
                            id = jobId,
                            vaultId = vaultId,
                            stateCode = JobState.COMPLETED.code,
                            sourceDispositionCode = SourceDisposition.NOT_APPLICABLE.code,
                            errorCode = null,
                            now = nowDuplicate
                        )
                    } else {
                        // Persist the canonical deletion URI before entering the
                        // crash-recoverable source-delete phase. This is especially
                        // important for Android Photo Picker URIs, which are read-only
                        // handles and may not be valid delete-request targets after a
                        // process restart.
                        val deletePayloadRaw = ImportJobPayload(
                            sourceUri = deletionUri.toString(),
                            targetFolderId = existing.folderId,
                            itemId = existing.id,
                            mode = mode
                        ).serialize()
                        val deletePayloadEncrypted = try {
                            Aead.encryptWithPrependedNonce(
                                session.metaSubkey,
                                deletePayloadRaw,
                                "job:$jobId:v1".toByteArray(Charsets.UTF_8)
                            )
                        } finally {
                            deletePayloadRaw.fill(0)
                        }
                        check(
                            vaultJobDao.updateEncryptedPayload(
                                jobId,
                                vaultId,
                                deletePayloadEncrypted,
                                nowDuplicate
                            ) == 1
                        )
                        vaultJobDao.updateState(jobId, JobState.AWAITING_SOURCE_DELETE.code, nowDuplicate)
                    }
                    return@withContext ImportResult.Success(
                        jobId = jobId,
                        itemId = existing.id,
                        uri = uri,
                        deletionUri = deletionUri,
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
                thumbnailGenerator.generateAndEncryptImagePreview(
                    imageUri = resolvedSource.readUri,
                    itemId = itemId,
                    thumbSubkey = session.thumbSubkey,
                    outputPreviewFile = previewFile,
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
            val stagedRaw = ImportJobPayload(
                sourceUri = deletionUri.toString(), targetFolderId = folderId, itemId = itemId, mode = mode,
                stagedMedia = ImportJobPayload.StagedMedia(
                    mediaTypeCode = sourceMeta.mediaType.code,
                    plaintextSize = verifyResult.plaintextSize,
                    sha256Hex = sha256Hex,
                    encryptedMetadata = encryptedMeta,
                    thumbnailFileName = if (thumbFile.exists()) thumbFile.name else null,
                    dateTakenMs = sourceMeta.metadata.dateTakenMs,
                    importedAt = now,
                    concealed = targetFolder?.let { it.effectiveHidden || it.effectiveProtected } ?: false
                )
            ).serialize()
            val stagedEncrypted = try {
                Aead.encryptWithPrependedNonce(session.metaSubkey, stagedRaw, "job:$jobId:v1".toByteArray())
            } finally { stagedRaw.fill(0) }
            check(vaultJobDao.updateEncryptedPayload(jobId, vaultId, stagedEncrypted, now) == 1)
            stagedPersisted = true
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
                dateTakenMs = sourceMeta.metadata.dateTakenMs,
                encryptedPreviewRelativePath = previewFile.name.takeIf { previewFile.exists() },
                concealed = targetFolder?.let { it.effectiveHidden || it.effectiveProtected } ?: false
            )

            database.withTransaction {
                val currentTarget = folderId?.let { database.folderDao().getFolderForVault(it, vaultId) }
                check(folderId == null || (currentTarget != null && folderAccessManager.canOpen(vaultId, folderId))) {
                    "Target folder no longer available"
                }
                mediaItemDao.insert(mediaEntity.copy(
                    concealed = currentTarget?.let { it.effectiveHidden || it.effectiveProtected } ?: false
                ))
                if (mode == ImportMode.COPY) {
                    vaultJobDao.updateTerminalImportState(
                        id = jobId,
                        vaultId = vaultId,
                        stateCode = JobState.COMPLETED.code,
                        sourceDispositionCode = SourceDisposition.NOT_APPLICABLE.code,
                        errorCode = null,
                        now = now
                    )
                } else {
                    vaultJobDao.updateState(jobId, JobState.AWAITING_SOURCE_DELETE.code, now)
                }
            }
            dbCommitted = true

            SafeLog.d("ImportCoordinator", "Media import completed")
            ImportResult.Success(
                jobId = jobId,
                itemId = itemId,
                uri = uri,
                deletionUri = deletionUri,
                sha256Hex = sha256Hex,
                mode = mode
            )
        } catch (ce: CancellationException) {
            // Section 14: Orphan-safe cancellation cleanup
            runCatching { if (partialFile.exists()) partialFile.delete() }
            if (finalCommitted && !dbCommitted) {
                runCatching { if (finalMediaFile.exists()) finalMediaFile.delete() }
                runCatching { if (thumbFile.exists()) thumbFile.delete() }
                runCatching { if (previewFile.exists()) previewFile.delete() }
            }
            vaultJobDao.updateState(jobId, JobState.CANCELLED.code, System.currentTimeMillis(), ImportErrorCode.CANCELLED.name)
            throw ce
        } catch (e: Exception) {
            SafeLog.e("ImportCoordinator", "Media import failed", e)
            runCatching { if (partialFile.exists()) partialFile.delete() }
            if (finalCommitted && !dbCommitted && !stagedPersisted) {
                runCatching { if (finalMediaFile.exists()) finalMediaFile.delete() }
                runCatching { if (thumbFile.exists()) thumbFile.delete() }
                runCatching { if (previewFile.exists()) previewFile.delete() }
            }
            val safeCode = when (e) {
                is SecurityException -> ImportErrorCode.PERMISSION_DENIED
                is java.io.FileNotFoundException, is java.io.IOException -> ImportErrorCode.SOURCE_UNAVAILABLE
                is IllegalArgumentException -> ImportErrorCode.UNSUPPORTED_MEDIA
                is android.database.sqlite.SQLiteException -> ImportErrorCode.DATABASE_FAILED
                else -> ImportErrorCode.UNKNOWN
            }
            vaultJobDao.updateState(
                jobId, if (stagedPersisted && !dbCommitted) JobState.VERIFYING.code else JobState.FAILED.code,
                System.currentTimeMillis(),
                if (stagedPersisted && !dbCommitted) "DATABASE_RETRY_PENDING" else safeCode.name
            )
            ImportResult.Failure(uri, safeCode.name, sourceUntouched = true)
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
