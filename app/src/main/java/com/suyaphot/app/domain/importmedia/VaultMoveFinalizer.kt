package com.suyaphot.app.domain.importmedia

import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.MediaType
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.model.SourceDisposition
import com.suyaphot.app.core.media.MetadataReader
import com.suyaphot.app.domain.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finishes MOVE imports only after the encrypted vault copy has already been committed.
 *
 * Public originals are never removed before ImportCoordinator has committed and verified
 * the private copy. Android consent is handled by the UI using [RequiresConsent].
 */
class VaultMoveFinalizer(
    private val database: SuyaDatabase,
    private val sourceDeletionCoordinator: SourceDeletionCoordinator,
    private val sessionManager: SessionManager,
    private val metadataReader: MetadataReader
) {
    data class Summary(
        val deletedCount: Int,
        val retainedCount: Int,
        val failedCount: Int
    )

    private data class SourceTarget(
        val result: ImportResult.Success,
        val deleteUri: Uri
    )

    sealed interface Result {
        data class Completed(val summary: Summary) : Result
        data class RequiresConsent(
            val intentSender: IntentSender,
            val mode: SourceDeletionCoordinator.DeleteConsentMode,
            val pending: List<ImportResult.Success>,
            val alreadyDeletedCount: Int
        ) : Result
    }

    /**
     * Android Photo Picker URIs can be mediated/read-only URIs. Where import metadata gives
     * us the original MediaStore volume + row id, reconstruct the canonical MediaStore URI
     * for the delete request so a successful vault move actually removes the Gallery/Files
     * source. Fall back to the picker URI when the provider does not expose canonical data.
     */
    private suspend fun resolveSourceTargets(
        successes: List<ImportResult.Success>
    ): List<SourceTarget> {
        val lease = sessionManager.acquireOperationKeyLease()
            ?: return successes.map { SourceTarget(it, it.uri) }

        return try {
            successes.map { success ->
                // Query the currently-selected public URI first. This is critical for
                // duplicate imports: the existing vault item's metadata may refer to an
                // older/different MediaStore row with the same bytes.
                val currentSourceCanonical = runCatching {
                    metadataReader.canonicalMediaStoreUri(success.uri)
                }.getOrNull()

                val storedCanonical = if (currentSourceCanonical == null && !success.alreadyExisted) {
                    val entity = database.mediaItemDao().getItemForVault(success.itemId, lease.vaultId)
                    entity?.let { media ->
                        val metadataBytes = runCatching {
                            Aead.decryptWithPrependedNonce(
                                lease.metaSubkey,
                                media.encryptedMetadata,
                                media.id.toByteArray(Charsets.UTF_8)
                            )
                        }.getOrNull()

                        metadataBytes?.let { bytes ->
                            try {
                                val metadata = PrivateMediaMetadata.deserialize(bytes)
                                val volume = metadata.sourceVolume
                                val sourceId = metadata.sourceMediaStoreId
                                if (!volume.isNullOrBlank() && sourceId != null && sourceId >= 0L) {
                                    val collection = if (media.mediaTypeCode == MediaType.VIDEO.code) {
                                        MediaStore.Video.Media.getContentUri(volume)
                                    } else {
                                        MediaStore.Images.Media.getContentUri(volume)
                                    }
                                    ContentUris.withAppendedId(collection, sourceId)
                                } else {
                                    null
                                }
                            } catch (_: Exception) {
                                null
                            } finally {
                                bytes.fill(0)
                            }
                        }
                    }
                } else {
                    null
                }

                SourceTarget(success, currentSourceCanonical ?: storedCanonical ?: success.uri)
            }
        } finally {
            lease.close()
        }
    }

    suspend fun begin(successes: List<ImportResult.Success>): Result = withContext(Dispatchers.IO) {
        if (successes.isEmpty()) return@withContext Result.Completed(Summary(0, 0, 0))
        val vaultId = sessionManager.currentVaultId
            ?: return@withContext Result.Completed(Summary(0, successes.size, successes.size))
        val targets = resolveSourceTargets(successes)
        val byUri = targets.associateBy { it.deleteUri }

        when (val outcome = sourceDeletionCoordinator.deleteSources(targets.map { it.deleteUri })) {
            is SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                val deleted = outcome.deletedUris.mapNotNull { byUri[it]?.result }
                mark(deleted, vaultId, SourceDisposition.DELETED, null)
                val retainedCount = successes.size - deleted.size
                Result.Completed(
                    Summary(
                        deletedCount = deleted.size,
                        retainedCount = retainedCount,
                        failedCount = retainedCount
                    )
                )
            }

            is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                val direct = outcome.deletedUris.mapNotNull { byUri[it]?.result }
                mark(direct, vaultId, SourceDisposition.DELETED, null)
                val pending = outcome.uris.mapNotNull { byUri[it]?.result }
                Result.RequiresConsent(
                    intentSender = outcome.intentSender,
                    mode = outcome.mode,
                    pending = pending,
                    alreadyDeletedCount = direct.size
                )
            }

            is SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                val direct = outcome.deletedUris.mapNotNull { byUri[it]?.result }
                val retained = outcome.uris.mapNotNull { byUri[it]?.result }
                mark(direct, vaultId, SourceDisposition.DELETED, null)
                mark(
                    retained,
                    vaultId,
                    SourceDisposition.DELETE_FAILED,
                    "SOURCE_DELETE_FAILED_VAULT_SAFE"
                )
                Result.Completed(
                    Summary(
                        deletedCount = direct.size,
                        retainedCount = retained.size,
                        failedCount = retained.size
                    )
                )
            }
        }
    }

    suspend fun completeConsent(
        pending: List<ImportResult.Success>,
        mode: SourceDeletionCoordinator.DeleteConsentMode,
        granted: Boolean
    ): Summary = withContext(Dispatchers.IO) {
        if (pending.isEmpty()) return@withContext Summary(0, 0, 0)
        val vaultId = sessionManager.currentVaultId
            ?: return@withContext Summary(0, pending.size, pending.size)

        if (!granted) {
            mark(pending, vaultId, SourceDisposition.RETAINED_BY_USER, null)
            return@withContext Summary(0, pending.size, 0)
        }

        val targets = resolveSourceTargets(pending)
        val byUri = targets.associateBy { it.deleteUri }
        val verified = sourceDeletionCoordinator.completeConsent(targets.map { it.deleteUri }, mode)
        val deleted = verified.deletedUris.mapNotNull { byUri[it]?.result }
        val retained = verified.retainedUris.mapNotNull { byUri[it]?.result }

        mark(deleted, vaultId, SourceDisposition.DELETED, null)
        mark(
            retained,
            vaultId,
            SourceDisposition.DELETE_FAILED,
            "SOURCE_DELETE_FAILED_VAULT_SAFE"
        )

        Summary(
            deletedCount = deleted.size,
            retainedCount = retained.size,
            failedCount = retained.size
        )
    }

    private suspend fun mark(
        items: List<ImportResult.Success>,
        vaultId: String,
        disposition: SourceDisposition,
        errorCode: String?
    ) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        for (item in items) {
            database.vaultJobDao().updateTerminalImportState(
                id = item.jobId,
                vaultId = vaultId,
                stateCode = JobState.COMPLETED.code,
                sourceDispositionCode = disposition.code,
                errorCode = errorCode,
                now = now
            )
        }
    }
}
