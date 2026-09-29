package com.suyaphot.app.domain.importmedia

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.MediaItemEntity
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.model.PrivateMediaMetadata
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.JobType
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction

/**
 * Reconciles non-terminal import jobs and orphaned files upon startup and unlock.
 */
class ImportRecoveryManager(
    private val database: SuyaDatabase,
    private val fileStore: VaultFileStore,
    private val vaultCrypto: VaultCrypto
) {

    suspend fun reconcileActiveJobs(session: VaultSession.Unlocked) = withContext(Dispatchers.IO) {
        val metaKey = session.metaSubkey.copyOf()
        val mediaKey = session.mediaSubkey.copyOf()
        try {
        val activeJobs = database.vaultJobDao().getActiveJobsForType(JobType.IMPORT.code)
        val now = System.currentTimeMillis()
        database.vaultJobDao().purgeResolvedCompletedJobs(session.vaultId, now - 7 * 86_400_000L)

        for (job in activeJobs) {
            if (job.vaultId != session.vaultId) continue

            when (job.stateCode) {
                JobState.QUEUED.code, JobState.READING_SOURCE.code, JobState.ENCRYPTING.code -> {
                    // Source was untouched; clean up partial file if any and mark failed
                    val partialFile = fileStore.getPartialFile(session.vaultId, job.id)
                    if (partialFile.exists()) partialFile.delete()
                    val payload = tryDecryptPayload(metaKey, job)
                    if (payload != null) {
                        val thumb = fileStore.getThumbFile(session.vaultId, payload.itemId)
                        if (thumb.exists()) thumb.delete()
                        val preview = fileStore.getPreviewFile(session.vaultId, payload.itemId)
                        if (preview.exists()) preview.delete()
                    }
                    database.vaultJobDao().updateState(
                        id = job.id,
                        stateCode = JobState.FAILED.code,
                        now = now,
                        errorCode = "Interrupted prior to ciphertext finalization"
                    )
                }

                JobState.DURABILITY_SYNC.code, JobState.VERIFYING.code -> {
                    val payload = tryDecryptPayload(metaKey, job)
                    if (payload != null) {
                        val mediaItem = database.mediaItemDao().getItemForVault(payload.itemId, session.vaultId)
                        val finalFile = fileStore.getMediaFile(session.vaultId, payload.itemId)

                        if (mediaItem != null) {
                            val recoveredState = if (payload.mode == ImportMode.COPY) {
                                JobState.COMPLETED
                            } else {
                                JobState.AWAITING_SOURCE_DELETE
                            }
                            database.vaultJobDao().updateState(
                                id = job.id,
                                stateCode = recoveredState.code,
                                now = now
                            )
                        } else if (payload.stagedMedia != null && finalFile.exists()) {
                            val staged = payload.stagedMedia
                            val target = payload.targetFolderId?.let { database.folderDao().getFolderForVault(it, session.vaultId) }
                            val targetValid = payload.targetFolderId == null || target != null
                            val stagedValid = runCatching {
                                require(targetValid && !(payload.targetFolderId == null && staged.concealed))
                                require(staged.sha256Hex.matches(Regex("[0-9a-fA-F]{64}")))
                                val metadata = Aead.decryptWithPrependedNonce(metaKey, staged.encryptedMetadata, payload.itemId.toByteArray())
                                try { PrivateMediaMetadata.deserialize(metadata) } finally { metadata.fill(0) }
                                val verified = vaultCrypto.verifyAndHash(finalFile, mediaKey, payload.itemId)
                                val hash = verified.sha256.joinToString("") { "%02x".format(it) }
                                require(hash.equals(staged.sha256Hex, ignoreCase = true) && verified.plaintextSize == staged.plaintextSize)
                                true
                            }.getOrDefault(false)
                            if (stagedValid) {
                                val recoveredState = if (payload.mode == ImportMode.COPY) JobState.COMPLETED else JobState.AWAITING_SOURCE_DELETE
                                database.withTransaction {
                                    val currentTarget = payload.targetFolderId?.let {
                                        database.folderDao().getFolderForVault(it, session.vaultId)
                                    }
                                    check(payload.targetFolderId == null || currentTarget != null) {
                                        "Recovery target folder no longer available"
                                    }
                                    database.mediaItemDao().insert(
                                        MediaItemEntity(
                                            id = payload.itemId, vaultId = session.vaultId,
                                            folderId = payload.targetFolderId, mediaTypeCode = staged.mediaTypeCode,
                                            encryptedMetadata = staged.encryptedMetadata,
                                            encryptedFileRelativePath = finalFile.name,
                                            encryptedThumbRelativePath = staged.thumbnailFileName?.takeIf {
                                                it == fileStore.getThumbFile(session.vaultId, payload.itemId).name && fileStore.getThumbFile(session.vaultId, payload.itemId).exists()
                                            },
                                            plaintextSize = staged.plaintextSize, cipherSize = finalFile.length(),
                                            sha256Hex = staged.sha256Hex, importedAt = staged.importedAt,
                                            updatedAt = now, favorite = false, deletedAt = null,
                                            previousFolderId = null, dateTakenMs = staged.dateTakenMs,
                                            encryptedPreviewRelativePath = fileStore.getPreviewFile(session.vaultId, payload.itemId)
                                                .takeIf { it.exists() }?.name,
                                            concealed = currentTarget?.let { it.effectiveHidden || it.effectiveProtected } ?: false
                                        )
                                    )
                                    database.vaultJobDao().updateState(job.id, recoveredState.code, now)
                                }
                            } else {
                                database.vaultJobDao().updateState(job.id, JobState.VERIFYING.code, now, "STAGED_REVERIFY_FAILED")
                            }
                        } else {
                            val partialFile = fileStore.getPartialFile(session.vaultId, job.id)
                            val thumbFile = fileStore.getThumbFile(session.vaultId, payload.itemId)
                            val previewFile = fileStore.getPreviewFile(session.vaultId, payload.itemId)
                            // Without the encrypted metadata row there is no safe way to finalize
                            // this ciphertext. The source has not been deleted yet, so remove the
                            // orphan and leave an active retry marker if filesystem cleanup fails.
                            val finalRemoved = !finalFile.exists() || finalFile.delete()
                            val partialRemoved = !partialFile.exists() || partialFile.delete()
                            val thumbRemoved = !thumbFile.exists() || thumbFile.delete()
                            val previewRemoved = !previewFile.exists() || previewFile.delete()
                            if (finalRemoved && partialRemoved && thumbRemoved && previewRemoved) {
                                database.vaultJobDao().updateState(
                                    id = job.id,
                                    stateCode = JobState.FAILED.code,
                                    now = now,
                                    errorCode = "INTERRUPTED_BEFORE_DATABASE_INSERT"
                                )
                            } else {
                                database.vaultJobDao().updateState(
                                    id = job.id,
                                    stateCode = JobState.VERIFYING.code,
                                    now = now,
                                    errorCode = "ORPHAN_CIPHERTEXT_CLEANUP_PENDING"
                                )
                            }
                        }
                    } else {
                        database.vaultJobDao().updateState(
                            id = job.id,
                            stateCode = JobState.FAILED.code,
                            now = now,
                            errorCode = "Could not decrypt job payload"
                        )
                    }
                }

                JobState.AWAITING_SOURCE_DELETE.code -> {
                    // Vault copy is valid, but source deletion was interrupted by app termination.
                    // The original item still remains in Gallery.
                    SafeLog.i("ImportRecoveryManager", "Job ${job.id} interrupted during source deletion. Marking RETAINED_AFTER_INTERRUPTION.")
                    database.vaultJobDao().updateTerminalImportState(
                        id = job.id,
                        vaultId = session.vaultId,
                        stateCode = JobState.COMPLETED.code,
                        sourceDispositionCode = com.suyaphot.app.core.model.SourceDisposition.RETAINED_AFTER_INTERRUPTION.code,
                        errorCode = null,
                        now = now
                    )
                }
            }
        }
        } finally {
            metaKey.fill(0)
            mediaKey.fill(0)
        }
    }

    private fun tryDecryptPayload(metaKey: ByteArray, job: VaultJobEntity): ImportJobPayload? {
        return try {
            val decrypted = Aead.decryptWithPrependedNonce(
                keyBytes = metaKey,
                payload = job.encryptedPayload,
                aad = "job:${job.id}:v1".toByteArray(Charsets.UTF_8)
            )
            try {
                ImportJobPayload.deserialize(decrypted)
            } finally {
                decrypted.fill(0)
            }
        } catch (e: Exception) {
            SafeLog.w("ImportRecoveryManager", "Failed to decrypt import recovery payload")
            null
        }
    }
}
