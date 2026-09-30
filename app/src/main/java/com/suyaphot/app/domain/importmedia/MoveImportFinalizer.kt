package com.suyaphot.app.domain.importmedia

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.SourceDisposition
import com.suyaphot.app.domain.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finalizes MOVE imports only after encrypted vault copies are committed.
 */
class MoveImportFinalizer(
    private val database: SuyaDatabase,
    private val sourceDeletionCoordinator: SourceDeletionCoordinator,
    private val sessionManager: SessionManager
) {
    data class PendingMove(val jobId: String, val uri: Uri)

    sealed interface Result {
        data class Complete(val deletedCount: Int, val retainedCount: Int) : Result
        data class RequiresConsent(
            val intentSender: IntentSender,
            val mode: SourceDeletionCoordinator.DeleteConsentMode,
            val pending: List<PendingMove>,
            val directlyDeletedCount: Int
        ) : Result
    }

    suspend fun begin(successes: List<ImportResult.Success>): Result = withContext(Dispatchers.IO) {
        val vaultId = sessionManager.currentVaultId
            ?: return@withContext Result.Complete(0, successes.size)

        if (successes.isEmpty()) return@withContext Result.Complete(0, 0)

        when (val outcome = sourceDeletionCoordinator.deleteSources(successes.map { it.uri })) {
            is SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                markSuccesses(successes.filter { it.uri in outcome.deletedUris }, vaultId, SourceDisposition.DELETED, null)
                Result.Complete(outcome.deletedUris.size, 0)
            }
            is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                markSuccesses(successes.filter { it.uri in outcome.deletedUris }, vaultId, SourceDisposition.DELETED, null)
                Result.RequiresConsent(
                    intentSender = outcome.intentSender,
                    mode = outcome.mode,
                    pending = successes.filter { it.uri in outcome.uris }.map { PendingMove(it.jobId, it.uri) },
                    directlyDeletedCount = outcome.deletedUris.size
                )
            }
            is SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                markSuccesses(successes.filter { it.uri in outcome.deletedUris }, vaultId, SourceDisposition.DELETED, null)
                markSuccesses(
                    successes.filter { it.uri in outcome.uris },
                    vaultId,
                    SourceDisposition.DELETE_FAILED,
                    "SOURCE_DELETE_FAILED_VAULT_SAFE"
                )
                Result.Complete(outcome.deletedUris.size, outcome.uris.size)
            }
        }
    }

    suspend fun completeConsent(
        pending: List<PendingMove>,
        mode: SourceDeletionCoordinator.DeleteConsentMode,
        resultCode: Int
    ): Result.Complete = withContext(Dispatchers.IO) {
        val vaultId = sessionManager.currentVaultId
            ?: return@withContext Result.Complete(0, pending.size)

        if (resultCode != Activity.RESULT_OK) {
            markPending(pending, vaultId, SourceDisposition.RETAINED_BY_USER, null)
            return@withContext Result.Complete(0, pending.size)
        }

        val verified = sourceDeletionCoordinator.completeConsent(pending.map { it.uri }, mode)
        val deleted = pending.filter { it.uri in verified.deletedUris }
        val retained = pending.filter { it.uri in verified.retainedUris }

        markPending(deleted, vaultId, SourceDisposition.DELETED, null)
        markPending(retained, vaultId, SourceDisposition.DELETE_FAILED, "SOURCE_DELETE_FAILED_VAULT_SAFE")
        Result.Complete(deleted.size, retained.size)
    }

    private suspend fun markSuccesses(
        successes: List<ImportResult.Success>,
        vaultId: String,
        disposition: SourceDisposition,
        errorCode: String?
    ) {
        val now = System.currentTimeMillis()
        successes.forEach {
            database.vaultJobDao().updateTerminalImportState(
                id = it.jobId,
                vaultId = vaultId,
                stateCode = JobState.COMPLETED.code,
                sourceDispositionCode = disposition.code,
                errorCode = errorCode,
                now = now
            )
        }
    }

    private suspend fun markPending(
        pending: List<PendingMove>,
        vaultId: String,
        disposition: SourceDisposition,
        errorCode: String?
    ) {
        val now = System.currentTimeMillis()
        pending.forEach {
            database.vaultJobDao().updateTerminalImportState(
                id = it.jobId,
                vaultId = vaultId,
                stateCode = JobState.COMPLETED.code,
                sourceDispositionCode = disposition.code,
                errorCode = errorCode,
                now = now
            )
        }
    }
}
