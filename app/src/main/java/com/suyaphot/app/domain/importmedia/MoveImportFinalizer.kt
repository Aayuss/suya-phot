package com.suyaphot.app.domain.importmedia

import android.content.IntentSender
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.SourceDisposition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finalizes MOVE imports only after the encrypted vault copy is committed and verified.
 * Public originals are deleted through [SourceDeletionCoordinator]; if Android refuses,
 * the vault copy remains safe and the job records that the original is still public.
 */
class MoveImportFinalizer(
    private val database: SuyaDatabase,
    private val sourceDeletionCoordinator: SourceDeletionCoordinator
) {

    data class Summary(
        val deleted: Int,
        val retained: Int
    )

    sealed interface BeginResult {
        data class Complete(val summary: Summary) : BeginResult

        data class RequiresConsent(
            val intentSender: IntentSender,
            val mode: SourceDeletionCoordinator.DeleteConsentMode,
            val pending: List<ImportResult.Success>,
            val alreadyDeleted: Int
        ) : BeginResult
    }

    suspend fun begin(
        vaultId: String,
        successes: List<ImportResult.Success>
    ): BeginResult = withContext(Dispatchers.IO) {
        if (successes.isEmpty()) return@withContext BeginResult.Complete(Summary(0, 0))

        when (val outcome = sourceDeletionCoordinator.deleteSources(successes.map { it.uri })) {
            is SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                val deleted = successes.filter { it.uri in outcome.deletedUris }
                mark(vaultId, deleted, SourceDisposition.DELETED, null)
                BeginResult.Complete(
                    Summary(
                        deleted = deleted.size,
                        retained = successes.size - deleted.size
                    )
                )
            }

            is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                val directlyDeleted = successes.filter { it.uri in outcome.deletedUris }
                mark(vaultId, directlyDeleted, SourceDisposition.DELETED, null)

                val pending = successes.filter { it.uri in outcome.uris }
                BeginResult.RequiresConsent(
                    intentSender = outcome.intentSender,
                    mode = outcome.mode,
                    pending = pending,
                    alreadyDeleted = directlyDeleted.size
                )
            }

            is SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                val directlyDeleted = successes.filter { it.uri in outcome.deletedUris }
                val retained = successes.filter { it.uri in outcome.uris }
                mark(vaultId, directlyDeleted, SourceDisposition.DELETED, null)
                mark(
                    vaultId,
                    retained,
                    SourceDisposition.DELETE_FAILED,
                    "SOURCE_DELETE_FAILED_VAULT_SAFE"
                )
                BeginResult.Complete(
                    Summary(
                        deleted = directlyDeleted.size,
                        retained = retained.size
                    )
                )
            }
        }
    }

    suspend fun completeConsent(
        vaultId: String,
        pending: List<ImportResult.Success>,
        mode: SourceDeletionCoordinator.DeleteConsentMode,
        approved: Boolean
    ): Summary = withContext(Dispatchers.IO) {
        if (pending.isEmpty()) return@withContext Summary(0, 0)

        if (!approved) {
            mark(vaultId, pending, SourceDisposition.RETAINED_BY_USER, null)
            return@withContext Summary(0, pending.size)
        }

        val verification = sourceDeletionCoordinator.completeConsent(
            pending.map { it.uri },
            mode
        )
        val deleted = pending.filter { it.uri in verification.deletedUris }
        val retained = pending.filter { it.uri in verification.retainedUris }

        mark(vaultId, deleted, SourceDisposition.DELETED, null)
        mark(
            vaultId,
            retained,
            SourceDisposition.DELETE_FAILED,
            "SOURCE_DELETE_FAILED_VAULT_SAFE"
        )

        Summary(deleted.size, retained.size)
    }

    private suspend fun mark(
        vaultId: String,
        results: List<ImportResult.Success>,
        disposition: SourceDisposition,
        errorCode: String?
    ) {
        if (results.isEmpty()) return
        val now = System.currentTimeMillis()
        for (result in results) {
            database.vaultJobDao().updateTerminalImportState(
                id = result.jobId,
                vaultId = vaultId,
                stateCode = JobState.COMPLETED.code,
                sourceDispositionCode = disposition.code,
                errorCode = errorCode,
                now = now
            )
        }
    }
}
