package com.suyaphot.app.domain.importmedia

import android.content.IntentSender
import com.suyaphot.app.core.database.SuyaDatabase
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.SourceDisposition
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
    private val sessionManager: SessionManager
) {
    data class Summary(
        val deletedCount: Int,
        val retainedCount: Int,
        val failedCount: Int
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

    suspend fun begin(successes: List<ImportResult.Success>): Result = withContext(Dispatchers.IO) {
        if (successes.isEmpty()) return@withContext Result.Completed(Summary(0, 0, 0))
        val vaultId = sessionManager.currentVaultId
            ?: return@withContext Result.Completed(Summary(0, successes.size, successes.size))

        when (val outcome = sourceDeletionCoordinator.deleteSources(successes.map { it.uri })) {
            is SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                val deleted = successes.filter { it.uri in outcome.deletedUris }
                mark(deleted, vaultId, SourceDisposition.DELETED, null)
                Result.Completed(Summary(deleted.size, 0, 0))
            }

            is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                val direct = successes.filter { it.uri in outcome.deletedUris }
                mark(direct, vaultId, SourceDisposition.DELETED, null)
                val pending = successes.filter { it.uri in outcome.uris }
                Result.RequiresConsent(
                    intentSender = outcome.intentSender,
                    mode = outcome.mode,
                    pending = pending,
                    alreadyDeletedCount = direct.size
                )
            }

            is SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                val direct = successes.filter { it.uri in outcome.deletedUris }
                val retained = successes.filter { it.uri in outcome.uris }
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

        val verified = sourceDeletionCoordinator.completeConsent(pending.map { it.uri }, mode)
        val deleted = pending.filter { it.uri in verified.deletedUris }
        val retained = pending.filter { it.uri in verified.retainedUris }

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
