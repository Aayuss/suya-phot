package com.suyaphot.app.domain.importmedia

import com.suyaphot.app.core.crypto.Aead
import com.suyaphot.app.core.crypto.VaultCrypto
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.database.entity.VaultJobEntity
import com.suyaphot.app.core.model.JobState
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
    private val fileStore: VaultFileStore,
    private val vaultCrypto: VaultCrypto
) {

    suspend fun reconcileActiveJobs(session: VaultSession.Unlocked) = withContext(Dispatchers.IO) {
        val activeJobs = database.vaultJobDao().getActiveJobs()
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
                    val payload = tryDecryptPayload(session, job)
                    if (payload != null) {
                        val mediaItem = database.mediaItemDao().getItem(payload.itemId)
                        val finalFile = fileStore.getMediaFile(session.vaultId, payload.itemId)

                        if (mediaItem != null) {
                            database.vaultJobDao().updateState(
                                id = job.id,
                                stateCode = JobState.AWAITING_SOURCE_DELETE.code,
                                now = now
                            )
                        } else {
                            if (finalFile.exists()) {
                                val valid = runCatching {
                                    vaultCrypto.verifyAndHash(finalFile, session.mediaSubkey, payload.itemId)
                                }.isSuccess
                                if (!valid) finalFile.delete()
                            }
                            val partialFile = fileStore.getPartialFile(session.vaultId, job.id)
                            if (partialFile.exists()) partialFile.delete()

                            database.vaultJobDao().updateState(
                                id = job.id,
                                stateCode = JobState.FAILED.code,
                                now = now,
                                errorCode = "Interrupted before database insertion"
                            )
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
                    SafeLog.d("ImportRecoveryManager", "Job ${job.id} awaiting source deletion confirmation")
                }
            }
        }
    }

    private fun tryDecryptPayload(session: VaultSession.Unlocked, job: VaultJobEntity): ImportJobPayload? {
        return try {
            val decrypted = Aead.decryptWithPrependedNonce(
                keyBytes = session.metaSubkey,
                payload = job.encryptedPayload,
                aad = "job:${job.id}:v1".toByteArray(Charsets.UTF_8)
            )
            ImportJobPayload.deserialize(decrypted)
        } catch (e: Exception) {
            SafeLog.w("ImportRecoveryManager", "Failed to decrypt job payload: ${job.id}", e)
            null
        }
    }
}
