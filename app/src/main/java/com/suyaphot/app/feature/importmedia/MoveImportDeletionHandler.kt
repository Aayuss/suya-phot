package com.suyaphot.app.feature.importmedia

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.SourceDisposition
import com.suyaphot.app.domain.importmedia.ImportResult
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberMoveImportDeletionHandler(
    container: AppContainer,
    vaultId: String,
    onStatus: (String) -> Unit
): (List<ImportResult.Success>) -> Unit {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<ImportResult.Success>>(emptyList()) }
    var pendingMode by remember { mutableStateOf<SourceDeletionCoordinator.DeleteConsentMode?>(null) }

    suspend fun mark(
        success: ImportResult.Success,
        disposition: SourceDisposition,
        errorCode: String? = null
    ) {
        container.database.vaultJobDao().updateTerminalImportState(
            id = success.jobId,
            vaultId = vaultId,
            stateCode = JobState.COMPLETED.code,
            sourceDispositionCode = disposition.code,
            errorCode = errorCode,
            now = System.currentTimeMillis()
        )
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        container.sessionManager.endSystemActivity()
        val current = pending
        val mode = pendingMode
        pending = emptyList()
        pendingMode = null

        scope.launch(Dispatchers.IO) {
            if (result.resultCode != Activity.RESULT_OK || mode == null) {
                current.forEach { mark(it, SourceDisposition.RETAINED_BY_USER) }
                withContext(Dispatchers.Main) {
                    onStatus(
                        "Vault copy is safe. Original" +
                            (if (current.size == 1) "" else "s") +
                            " kept in Gallery."
                    )
                }
                return@launch
            }

            val verification = container.sourceDeletionCoordinator.completeConsent(
                current.map { it.uri },
                mode
            )
            current.forEach { success ->
                if (success.uri in verification.deletedUris) {
                    mark(success, SourceDisposition.DELETED)
                } else {
                    mark(success, SourceDisposition.DELETE_FAILED, "SOURCE_DELETE_FAILED_VAULT_SAFE")
                }
            }

            withContext(Dispatchers.Main) {
                val retained = verification.retainedUris.size
                onStatus(
                    if (retained == 0) {
                        "Moved to Suya Phot. Originals removed from Gallery."
                    } else {
                        "Moved to Suya Phot, but Android kept " +
                            retained +
                            " original" +
                            (if (retained == 1) "" else "s") +
                            "."
                    }
                )
            }
        }
    }

    return remember(vaultId, consentLauncher) {
        { successes: List<ImportResult.Success> ->
            val moves = successes.filter { it.mode == ImportMode.MOVE }
            if (moves.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    when (val outcome = container.sourceDeletionCoordinator.deleteSources(moves.map { it.uri })) {
                        is SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                            moves.forEach { success ->
                                if (success.uri in outcome.deletedUris) {
                                    mark(success, SourceDisposition.DELETED)
                                } else {
                                    mark(success, SourceDisposition.DELETE_FAILED, "SOURCE_DELETE_FAILED_VAULT_SAFE")
                                }
                            }
                            withContext(Dispatchers.Main) {
                                onStatus("Moved to Suya Phot. Originals removed from Gallery.")
                            }
                        }

                        is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                            moves.filter { it.uri in outcome.deletedUris }
                                .forEach { mark(it, SourceDisposition.DELETED) }

                            val remaining = moves.filter { it.uri in outcome.uris }
                            withContext(Dispatchers.Main) {
                                pending = remaining
                                pendingMode = outcome.mode
                                onStatus("Confirm Android's delete request to finish moving originals.")
                                container.sessionManager.beginSystemActivity()
                                consentLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                            }
                        }

                        is SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                            moves.forEach { success ->
                                if (success.uri in outcome.deletedUris) {
                                    mark(success, SourceDisposition.DELETED)
                                } else {
                                    mark(success, SourceDisposition.DELETE_FAILED, "SOURCE_DELETE_FAILED_VAULT_SAFE")
                                }
                            }
                            withContext(Dispatchers.Main) {
                                onStatus("Vault copy is safe, but Android could not remove every original.")
                            }
                        }
                    }
                }
            }
        }
    }
}
