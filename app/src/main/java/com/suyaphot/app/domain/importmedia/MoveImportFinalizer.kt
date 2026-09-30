package com.suyaphot.app.domain.importmedia

import android.app.Activity
import android.content.IntentSender
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.SourceDisposition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finalizes MOVE imports only after the encrypted vault copy is durable.
 *
 * This class deliberately separates:
 * 1. vault import/verification
 * 2. Android public-source deletion
 *
 * so failure/cancellation can never remove the only known-good copy.
 */
class MoveImportFinalizer(
    private val database: SuyaDatabase,
    private val sourceDeletionCoordinator: SourceDeletionCoordinator
) {
    sealed interface Step {
        data class Completed(
            val deletedCount: Int,
            val retainedCount: Int,
            val failedCount: Int
        ) : Step

        data class RequiresConsent(
            val intentSender: IntentSender,
            val mode: SourceDeletionCoordinator.DeleteConsentMode,
            val consentBatch: List<ImportResult.Success>,
            val remaining: List<ImportResult.Success>,
            val deletedCount: Int,
            val retainedCount: Int,
            val failedCount: Int
        ) : Step
    }

    suspend fun begin(
        vaultId: String,
        successes: List<ImportResult.Success>
    ): Step = process(
        vaultId = vaultId,
        pending = successes,
        deletedCount = 0,
        retainedCount = 0,
        failedCount = 0
    )

    suspend fun afterConsent(
        vaultId: String,
        step: Step.RequiresConsent,
        resultCode: Int
    ): Step = withContext(Dispatchers.IO) {
        var deleted = step.deletedCount
        var retained = step.retainedCount
        var failed = step.failedCount

        if (resultCode == Activity.RESULT_OK) {
            val verified = sourceDeletionCoordinator.completeConsent(
                step.consentBatch.map { it.uri },
                step.mode
            )

            val deletedResults = step.consentBatch.filter { it.uri in verified.deletedUris }
            val retainedResults = step.consentBatch.filter { it.uri in verified.retainedUris }

            markTerminal(
                vaultId,
                deletedResults,
                SourceDisposition.DELETED,
                errorCode = null
            )
            markTerminal(
                vaultId,
                retainedResults,
                SourceDisposition.DELETE_FAILED,
                errorCode = "SOURCE_DELETE_FAILED_VAULT_SAFE"
            )

            deleted += deletedResults.size
            failed += retainedResults.size
        } else {
            markTerminal(
                vaultId,
                step.consentBatch,
                SourceDisposition.RETAINED_BY_USER,
                errorCode = null
            )
            retained += step.consentBatch.size
        }

        process(
            vaultId = vaultId,
            pending = step.remaining,
            deletedCount = deleted,
            retainedCount = retained,
            failedCount = failed
        )
    }

    private suspend fun process(
        vaultId: String,
        pending: List<ImportResult.Success>,
        deletedCount: Int,
        retainedCount: Int,
        failedCount: Int
    ): Step = withContext(Dispatchers.IO) {
        if (pending.isEmpty()) {
            return@withContext Step.Completed(
                deletedCount = deletedCount,
                retainedCount = retainedCount,
                failedCount = failedCount
            )
        }

        when (
            val outcome = sourceDeletionCoordinator.deleteSources(
                pending.map { it.uri }
            )
        ) {
            is SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                val deletedResults = pending.filter { it.uri in outcome.deletedUris }
                markTerminal(
                    vaultId,
                    deletedResults,
                    SourceDisposition.DELETED,
                    errorCode = null
                )

                val unresolved = pending.filterNot { it.uri in outcome.deletedUris }
                if (unresolved.isNotEmpty()) {
                    markTerminal(
                        vaultId,
                        unresolved,
                        SourceDisposition.DELETE_FAILED,
                        errorCode = "SOURCE_DELETE_FAILED_VAULT_SAFE"
                    )
                }

                Step.Completed(
                    deletedCount = deletedCount + deletedResults.size,
                    retainedCount = retainedCount,
                    failedCount = failedCount + unresolved.size
                )
            }

            is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                val directlyDeleted = pending.filter { it.uri in outcome.deletedUris }
                markTerminal(
                    vaultId,
                    directlyDeleted,
                    SourceDisposition.DELETED,
                    errorCode = null
                )

                val consentBatch = pending.filter { it.uri in outcome.uris }
                val remaining = pending.filterNot { candidate ->
                    candidate.uri in outcome.deletedUris ||
                        candidate.uri in outcome.uris
                }

                Step.RequiresConsent(
                    intentSender = outcome.intentSender,
                    mode = outcome.mode,
                    consentBatch = consentBatch,
                    remaining = remaining,
                    deletedCount = deletedCount + directlyDeleted.size,
                    retainedCount = retainedCount,
                    failedCount = failedCount
                )
            }

            is SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                val directlyDeleted = pending.filter { it.uri in outcome.deletedUris }
                val failedResults = pending.filter { it.uri in outcome.uris }

                markTerminal(
                    vaultId,
                    directlyDeleted,
                    SourceDisposition.DELETED,
                    errorCode = null
                )
                markTerminal(
                    vaultId,
                    failedResults,
                    SourceDisposition.DELETE_FAILED,
                    errorCode = "SOURCE_DELETE_FAILED_VAULT_SAFE"
                )

                val unresolved = pending.filterNot { candidate ->
                    candidate.uri in outcome.deletedUris ||
                        candidate.uri in outcome.uris
                }
                if (unresolved.isNotEmpty()) {
                    markTerminal(
                        vaultId,
                        unresolved,
                        SourceDisposition.DELETE_FAILED,
                        errorCode = "SOURCE_DELETE_FAILED_VAULT_SAFE"
                    )
                }

                Step.Completed(
                    deletedCount = deletedCount + directlyDeleted.size,
                    retainedCount = retainedCount,
                    failedCount = failedCount + failedResults.size + unresolved.size
                )
            }
        }
    }

    private suspend fun markTerminal(
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
