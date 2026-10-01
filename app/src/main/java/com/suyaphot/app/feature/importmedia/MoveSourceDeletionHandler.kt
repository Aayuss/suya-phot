package com.suyaphot.app.feature.importmedia

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.core.model.SourceDisposition
import com.suyaphot.app.domain.importmedia.ImportResult
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Completes MOVE imports after the encrypted vault copy is durably committed and verified.
 *
 * Android scoped storage may require a system deletion-consent sheet. The vault copy is never
 * removed if the user declines or Android retains the public original.
 */
@Composable
fun rememberMoveSourceDeletionHandler(
    container: AppContainer,
    onStatus: (String) -> Unit
): (List<ImportResult.Success>) -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnStatus by rememberUpdatedState(onStatus)
    val pending = remember { mutableStateListOf<ImportResult.Success>() }
    var consentBatch by remember { mutableStateOf<List<ImportResult.Success>>(emptyList()) }
    var consentMode by remember { mutableStateOf<SourceDeletionCoordinator.DeleteConsentMode?>(null) }
    var anyRetained by remember { mutableStateOf(false) }

    suspend fun mark(
        items: List<ImportResult.Success>,
        disposition: SourceDisposition,
        errorCode: String? = null
    ) = withContext(Dispatchers.IO) {
        val vaultId = container.sessionManager.currentVaultId ?: return@withContext
        val now = System.currentTimeMillis()
        items.forEach { item ->
            container.database.vaultJobDao().updateTerminalImportState(
                id = item.jobId,
                vaultId = vaultId,
                stateCode = JobState.COMPLETED.code,
                sourceDispositionCode = disposition.code,
                errorCode = errorCode,
                now = now
            )
        }
    }

    lateinit var processPending: () -> Unit

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        container.sessionManager.endSystemActivity()
        val batch = consentBatch
        val mode = consentMode
        consentBatch = emptyList()
        consentMode = null

        scope.launch {
            if (result.resultCode == Activity.RESULT_OK && mode != null) {
                val verified = withContext(Dispatchers.IO) {
                    container.sourceDeletionCoordinator.completeConsent(batch.map { it.uri }, mode)
                }
                val deleted = batch.filter { it.uri in verified.deletedUris }
                val retained = batch.filter { it.uri in verified.retainedUris }
                mark(deleted, SourceDisposition.DELETED)
                if (retained.isNotEmpty()) {
                    anyRetained = true
                    mark(retained, SourceDisposition.DELETE_FAILED, "SOURCE_DELETE_FAILED_VAULT_SAFE")
                }
            } else {
                anyRetained = anyRetained || batch.isNotEmpty()
                mark(batch, SourceDisposition.RETAINED_BY_USER)
            }

            val handledIds = batch.mapTo(HashSet()) { it.jobId }
            pending.removeAll { it.jobId in handledIds }
            processPending()
        }
    }

    processPending = process@{
        if (pending.isEmpty()) {
            currentOnStatus(
                if (anyRetained) {
                    "Added to Suya Phot. Some originals are still in Gallery because Android or you kept them."
                } else {
                    "Move complete. Originals were removed from the public Gallery."
                }
            )
            return@process
        }

        scope.launch {
            val current = pending.toList()
            when (val outcome = withContext(Dispatchers.IO) {
                container.sourceDeletionCoordinator.deleteSources(current.map { it.uri })
            }) {
                is SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                    val deleted = current.filter { it.uri in outcome.deletedUris }
                    mark(deleted, SourceDisposition.DELETED)
                    val ids = deleted.mapTo(HashSet()) { it.jobId }
                    pending.removeAll { it.jobId in ids }
                    processPending()
                }

                is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                    val directlyDeleted = current.filter { it.uri in outcome.deletedUris }
                    mark(directlyDeleted, SourceDisposition.DELETED)
                    val directIds = directlyDeleted.mapTo(HashSet()) { it.jobId }
                    pending.removeAll { it.jobId in directIds }

                    val requestedUris = outcome.uris.toHashSet()
                    consentBatch = pending.filter { it.uri in requestedUris }
                    consentMode = outcome.mode

                    if (consentBatch.isEmpty()) {
                        // Defensive: no matching import job for the system request.
                        anyRetained = true
                        processPending()
                    } else {
                        container.sessionManager.beginSystemActivity()
                        consentLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                    }
                }

                is SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                    val directlyDeleted = current.filter { it.uri in outcome.deletedUris }
                    val retained = current.filter { it.uri in outcome.uris }
                    mark(directlyDeleted, SourceDisposition.DELETED)
                    if (retained.isNotEmpty()) {
                        anyRetained = true
                        mark(retained, SourceDisposition.DELETE_FAILED, "SOURCE_DELETE_FAILED_VAULT_SAFE")
                    }
                    val handled = (directlyDeleted + retained).mapTo(HashSet()) { it.jobId }
                    pending.removeAll { it.jobId in handled }
                    processPending()
                }
            }
        }
    }

    return { successes ->
        pending.clear()
        pending.addAll(successes.filter { it.mode == com.suyaphot.app.core.model.ImportMode.MOVE })
        consentBatch = emptyList()
        consentMode = null
        anyRetained = false
        if (pending.isEmpty()) {
            currentOnStatus("Nothing new needed to be moved.")
        } else {
            processPending()
        }
    }
}
