package com.suyaphot.app.domain.importmedia

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.suyaphot.app.core.media.MetadataReader
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.domain.auth.SessionManager
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Manages staging and ingestion of media shared to Suya Phot from external apps
 * without requiring the user to unlock the vault or interact with destination pickers.
 */
class PendingShareManager(
    private val context: Context,
    private val metadataReader: MetadataReader,
    private val importCoordinator: ImportCoordinator,
    private val sessionManager: SessionManager
) {
    private val pendingDir = File(context.filesDir, "pending_shares")
    private val ingestMutex = Mutex()

    init {
        if (!pendingDir.exists()) {
            pendingDir.mkdirs()
        }
    }

    fun hasPendingShares(): Boolean {
        return pendingDir.listFiles { _, name -> name.endsWith(".meta") }?.isNotEmpty() == true
    }

    data class StagedSharesResult(
        val stagedCount: Int,
        val deletionTargets: List<Uri>
    )

    /**
     * Immediately reads and stages shared media streams and metadata to app-private storage.
     * Must be called while the caller's URI permission is active.
     */
    suspend fun stageSharedMedia(uris: List<Uri>): StagedSharesResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext StagedSharesResult(0, emptyList())
        if (!pendingDir.exists()) pendingDir.mkdirs()

        var stagedCount = 0
        val deletionTargets = mutableListOf<Uri>()
        val now = System.currentTimeMillis()

        for (uri in uris) {
            try {
                val sourceMeta = runCatching { metadataReader.read(uri) }.getOrNull()
                val metadata = sourceMeta?.metadata ?: run {
                    val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "shared_media_$now"
                    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
                    PrivateMediaMetadata(
                        originalDisplayName = displayName,
                        originalRelativePath = "${Environment.DIRECTORY_DCIM}/Camera/",
                        originalMimeType = mime,
                        originalContentUri = uri.toString(),
                        dateTakenMs = now,
                        dateModifiedMs = now,
                        width = null,
                        height = null,
                        durationMs = null,
                        orientation = 0,
                        sourceVolume = null,
                        sourceMediaStoreId = null,
                        gpsWasAvailable = false,
                        originalFileExtension = displayName.substringAfterLast('.', "")
                    )
                }

                val shareId = UUID.randomUUID().toString()
                val partialFile = File(pendingDir, "$shareId.partial")
                val mediaFile = File(pendingDir, "$shareId.media")
                val metaFile = File(pendingDir, "$shareId.meta")

                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    SafeLog.e("PendingShareManager", "Failed to open input stream for URI: $uri")
                    continue
                }

                input.use { inStream ->
                    FileOutputStream(partialFile).use { outStream ->
                        inStream.copyTo(outStream)
                        outStream.flush()
                        outStream.fd.sync()
                    }
                }

                if (!partialFile.renameTo(mediaFile)) {
                    partialFile.delete()
                    SafeLog.e("PendingShareManager", "Failed to rename partial staged file for $shareId")
                    continue
                }

                val serializedMeta = metadata.serialize()
                metaFile.writeBytes(serializedMeta)

                val mediaType = if (metadata.originalMimeType.startsWith("video/")) {
                    com.suyaphot.app.core.model.MediaType.VIDEO
                } else {
                    com.suyaphot.app.core.model.MediaType.IMAGE
                }
                val resolvedDeletionUri = canonicalDeletionUri(
                    fallback = uri,
                    mediaType = mediaType,
                    metadata = metadata,
                    context = context
                ).let { candidate ->
                    if (candidate.authority == android.provider.MediaStore.AUTHORITY && !candidate.toString().contains("/picker")) {
                        candidate
                    } else {
                        runCatching { Uri.parse(metadata.originalContentUri) }.getOrNull() ?: candidate
                    }
                }

                val cleanUri = resolvedDeletionUri.buildUpon().clearQuery().build()

                if (cleanUri.authority == android.provider.MediaStore.AUTHORITY &&
                    !cleanUri.toString().contains("/picker")
                ) {
                    if (cleanUri !in deletionTargets) {
                        deletionTargets.add(cleanUri)
                    }
                }

                stagedCount++
                SafeLog.d("PendingShareManager", "Staged shared item: $shareId (${metadata.originalDisplayName}), deletionTarget=$cleanUri")
            } catch (e: Exception) {
                SafeLog.e("PendingShareManager", "Error staging shared item from URI: $uri", e)
            }
        }

        StagedSharesResult(stagedCount, deletionTargets)
    }

    /**
     * Ingests all pending staged media into the root folder of the active vault.
     */
    suspend fun processPendingShares(session: VaultSession.Unlocked): Int = withContext(Dispatchers.IO) {
        if (!hasPendingShares()) return@withContext 0

        ingestMutex.withLock {
            val metaFiles = pendingDir.listFiles { _, name -> name.endsWith(".meta") }?.sortedBy { it.lastModified() }
                ?: return@withContext 0

            var importedCount = 0

            for (metaFile in metaFiles) {
                if (sessionManager.sessionState.value !is VaultSession.Unlocked) {
                    SafeLog.d("PendingShareManager", "Vault locked during pending share ingestion, pausing.")
                    break
                }

                val baseName = metaFile.name.removeSuffix(".meta")
                val mediaFile = File(pendingDir, "$baseName.media")

                if (!mediaFile.exists()) {
                    metaFile.delete()
                    continue
                }

                val metadata = try {
                    val bytes = metaFile.readBytes()
                    PrivateMediaMetadata.deserialize(bytes)
                } catch (e: Exception) {
                    SafeLog.e("PendingShareManager", "Corrupted metadata file $metaFile", e)
                    mediaFile.delete()
                    metaFile.delete()
                    continue
                }

                val fileUri = Uri.fromFile(mediaFile)
                val result = importCoordinator.importSingle(
                    uri = fileUri,
                    folderId = null,
                    mode = ImportMode.COPY,
                    skipDuplicates = true,
                    overrideMetadata = metadata
                )

                when (result) {
                    is ImportResult.Success -> {
                        mediaFile.delete()
                        metaFile.delete()
                        importedCount++
                        SafeLog.d("PendingShareManager", "Successfully imported pending share: ${metadata.originalDisplayName}")
                    }
                    is ImportResult.Failure -> {
                        if (result.reason == "VAULT_LOCKED") {
                            break
                        } else {
                            SafeLog.e("PendingShareManager", "Failed to import pending share ${metadata.originalDisplayName}: ${result.reason}")
                            mediaFile.delete()
                            metaFile.delete()
                        }
                    }
                }
            }

            importedCount
        }
    }
}
