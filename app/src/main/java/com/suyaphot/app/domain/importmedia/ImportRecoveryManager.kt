package com.suyaphot.app.domain.importmedia

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.JobType
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.core.util.VaultFileStore
import com.suyaphot.app.domain.auth.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reconciles non-terminal import jobs and orphaned files upon startup and unlock.
 */
class ImportRecoveryManager(
    private val database: SuyaDatabase,
    private val fileStore: VaultFileStore
) {

    suspend fun reconcileActiveJobs(session: VaultSession.Unlocked) = withContext(Dispatchers.IO) {
        val metaKey = session.metaSubkey.copyOf()
        try {
        val activeJobs = database.vaultJobDao().getActiveJobsForType(JobType.IMPORT.code)
        val now = System.currentTimeMillis()

        for (job in activeJobs) {
            if (job.vaultId != session.vaultId) continue

            when (job.stateCode) {
                JobState.QUEUED.code, JobState.READING_SOURCE.code, JobState.ENCRYPTING.code -> {
                    // Source was untouched; clean up partial file if any and mark failed
                    val partialFile = fileStore.getPartialFile(session.vaultId, job.id)
                    if (partialFile.exists()) partialFile.delete()
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
                        } else {
                            val partialFile = fileStore.getPartialFile(session.vaultId, job.id)
                            // Without the encrypted metadata row there is no safe way to finalize
                            // this ciphertext. The source has not been deleted yet, so remove the
                            // orphan and leave an active retry marker if filesystem cleanup fails.
                            val finalRemoved = !finalFile.exists() || finalFile.delete()
                            val partialRemoved = !partialFile.exists() || partialFile.delete()
                            if (finalRemoved && partialRemoved) {
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
                    // Vault copy is valid, awaiting confirmation of source deletion
                    SafeLog.d("ImportRecoveryManager", "Import awaits source deletion confirmation")
                }
            }
        }
        } finally {
            metaKey.fill(0)
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
