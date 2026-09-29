package com.suyaphot.app.feature.importmedia

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.model.ImportMode
import com.suyaphot.app.core.model.JobState
import com.suyaphot.app.domain.importmedia.ImportResult
import com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ShareStage {
    data object DestinationSelection : ShareStage
    data object ContextualLocationPrompt : ShareStage
    data class Importing(val current: Int, val total: Int, val status: String) : ShareStage
    data class DeleteConsent(val intentSender: IntentSender, val mode: SourceDeletionCoordinator.DeleteConsentMode) : ShareStage
    data class Completed(val summaryText: String) : ShareStage
}

class ShareImportViewModel : ViewModel() {

    private val _stage = MutableStateFlow<ShareStage>(ShareStage.DestinationSelection)
    val stage: StateFlow<ShareStage> = _stage.asStateFlow()

    private var uris: List<Uri> = emptyList()
    private var targetFolderId: String? = null
    private var container: AppContainer? = null

    private var pendingSuccesses = mutableListOf<ImportResult.Success>()
    private var consentSuccesses = mutableListOf<ImportResult.Success>()
    private var activeConsentMode: SourceDeletionCoordinator.DeleteConsentMode? = null

    private var isInitialized = false

    fun init(sharedUris: List<Uri>, appContainer: AppContainer) {
        if (isInitialized) return
        isInitialized = true
        uris = sharedUris
        container = appContainer
    }

    fun hasMediaStoreUris(): Boolean {
        return uris.any { it.authority == "media" || it.toString().contains("content://media/") }
    }

    fun selectDestination(folderId: String?) {
        targetFolderId = folderId
        val needsLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaStoreUris()
        if (needsLocation) {
            _stage.value = ShareStage.ContextualLocationPrompt
        } else {
            proceedWithImport()
        }
    }

    fun onLocationPromptDismissed() {
        proceedWithImport()
    }

    fun proceedWithImport() {
        val app = container ?: return
        if (uris.isEmpty()) {
            _stage.value = ShareStage.Completed("No items to import.")
            return
        }

        _stage.value = ShareStage.Importing(0, uris.size, "Preparing import...")
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                app.importCoordinator.importBatch(
                    uris = uris,
                    folderId = targetFolderId,
                    mode = ImportMode.MOVE,
                    onItemComplete = { current, total, _ ->
                        _stage.value = ShareStage.Importing(current, total, "Encrypting $current of $total items...")
                    }
                )
            }

            val successes = results.filterIsInstance<ImportResult.Success>()
            pendingSuccesses.clear()
            pendingSuccesses.addAll(successes)

            if (pendingSuccesses.isEmpty()) {
                _stage.value = ShareStage.Completed("No new items were imported.")
            } else {
                processSourceDeletions()
            }
        }
    }

    private var anyOriginalsRetained = false

    private suspend fun markJobsTerminal(
        results: List<ImportResult.Success>,
        disposition: String? = null
    ) = withContext(Dispatchers.IO) {
        val app = container ?: return@withContext
        val vaultId = app.sessionManager.currentVaultId ?: return@withContext
        val now = System.currentTimeMillis()
        val dispCode = when (disposition) {
            "SOURCE_DELETED" -> com.suyaphot.app.core.model.SourceDisposition.DELETED.code
            "SOURCE_DELETE_FAILED_VAULT_SAFE" -> com.suyaphot.app.core.model.SourceDisposition.DELETE_FAILED.code
            "ORIGINAL_RETAINED_BY_USER" -> com.suyaphot.app.core.model.SourceDisposition.RETAINED_BY_USER.code
            else -> com.suyaphot.app.core.model.SourceDisposition.NOT_APPLICABLE.code
        }
        val safeErrorCode = if (dispCode == com.suyaphot.app.core.model.SourceDisposition.DELETE_FAILED.code) {
            "SOURCE_DELETE_FAILED_VAULT_SAFE"
        } else {
            null
        }
        for (res in results) {
            app.database.vaultJobDao().updateTerminalImportState(
                id = res.jobId,
                vaultId = vaultId,
                stateCode = JobState.COMPLETED.code,
                sourceDispositionCode = dispCode,
                errorCode = safeErrorCode,
                now = now
            )
        }
    }

    private fun processSourceDeletions() {
        val app = container ?: return
        if (pendingSuccesses.isEmpty()) {
            if (anyOriginalsRetained) {
                _stage.value = ShareStage.Completed("Imported safely. Some public originals were retained by Android.")
            } else {
                _stage.value = ShareStage.Completed("Move complete. Vault encrypted and protected.")
            }
            return
        }

        viewModelScope.launch {
            val current = pendingSuccesses.toList()
            when (val outcome = withContext(Dispatchers.IO) { app.sourceDeletionCoordinator.deleteSources(current.map { it.uri }) }) {
                is SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                    val completed = current.filter { it.uri in outcome.deletedUris }
                    markJobsTerminal(completed, "SOURCE_DELETED")
                    pendingSuccesses.removeAll { it.uri in outcome.deletedUris }
                    processSourceDeletions()
                }
                is SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                    val directlyDeleted = current.filter { it.uri in outcome.deletedUris }
                    markJobsTerminal(directlyDeleted, "SOURCE_DELETED")
                    pendingSuccesses.removeAll { it.uri in outcome.deletedUris }

                    consentSuccesses.clear()
                    consentSuccesses.addAll(pendingSuccesses.filter { it.uri in outcome.uris })
                    activeConsentMode = outcome.mode
                    _stage.value = ShareStage.DeleteConsent(outcome.intentSender, outcome.mode)
                }
                is SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                    val directlyDeleted = current.filter { it.uri in outcome.deletedUris }
                    markJobsTerminal(directlyDeleted, "SOURCE_DELETED")
                    val retained = current.filter { it.uri in outcome.uris }
                    if (retained.isNotEmpty()) anyOriginalsRetained = true
                    markJobsTerminal(retained, "SOURCE_DELETE_FAILED_VAULT_SAFE")
                    pendingSuccesses.removeAll { it.uri in outcome.deletedUris }
                    pendingSuccesses.removeAll { it.uri in outcome.uris }
                    processSourceDeletions()
                }
            }
        }
    }

    fun onConsentResult(resultCode: Int) {
        val app = container ?: return
        val currentConsent = consentSuccesses.toList()
        val mode = activeConsentMode

        viewModelScope.launch {
            if (resultCode == Activity.RESULT_OK && mode != null) {
                val verified = withContext(Dispatchers.IO) {
                    app.sourceDeletionCoordinator.completeConsent(currentConsent.map { it.uri }, mode)
                }
                markJobsTerminal(currentConsent.filter { it.uri in verified.deletedUris }, "SOURCE_DELETED")
                val retained = currentConsent.filter { it.uri in verified.retainedUris }
                if (retained.isNotEmpty()) {
                    anyOriginalsRetained = true
                    markJobsTerminal(retained, "SOURCE_DELETE_FAILED_VAULT_SAFE")
                }
            } else {
                anyOriginalsRetained = true
                markJobsTerminal(currentConsent, "ORIGINAL_RETAINED_BY_USER")
            }

            pendingSuccesses.removeAll { pending -> currentConsent.any { it.jobId == pending.jobId } }
            consentSuccesses.clear()
            activeConsentMode = null
            processSourceDeletions()
        }
    }
}
