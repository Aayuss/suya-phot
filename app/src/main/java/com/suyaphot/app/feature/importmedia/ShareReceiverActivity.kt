package com.suyaphot.app.feature.importmedia

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.app.SuyaApp
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.feature.lock.LockScreen
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import com.suyaphot.app.ui.theme.SuyaTheme
import com.suyaphot.app.core.model.ImportMode
import kotlinx.coroutines.launch

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val app = application as SuyaApp
        val container = app.container

        val sharedUris = extractUrisFromIntent(intent)
        if (sharedUris.isEmpty()) {
            finish()
            return
        }

        setContent {
            SuyaTheme {
                val session by container.sessionManager.sessionState.collectAsState()
                val isUnlocked = session is VaultSession.Unlocked

                if (!isUnlocked) {
                    LockScreen(
                        container = container,
                        onUnlocked = {
                            // Session unlocked, composable will recompose and proceed with import
                        }
                    )
                } else {
                    ImportProgressView(
                        uris = sharedUris,
                        container = container,
                        onFinish = { finish() }
                    )
                }
            }
        }
    }

    private fun extractUrisFromIntent(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        val out = LinkedHashSet<Uri>()

        fun addIfValid(uri: Uri?) {
            if (uri?.scheme != "content" || out.size >= 500) return
            val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: return
            if (mime.startsWith("image/") || mime.startsWith("video/")) out.add(uri)
        }

        if (intent.action == Intent.ACTION_SEND) {
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            addIfValid(streamUri)

            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    addIfValid(clip.getItemAt(i).uri)
                }
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            streamUris?.forEach(::addIfValid)

            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    addIfValid(clip.getItemAt(i).uri)
                }
            }
        }
        return out.toList()
    }
}

@Composable
private fun ImportProgressView(
    uris: List<Uri>,
    container: com.suyaphot.app.app.AppContainer,
    onFinish: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("Preparing import...") }
    var isDone by remember { mutableStateOf(false) }
    var pendingSuccesses by remember { mutableStateOf<List<com.suyaphot.app.domain.importmedia.ImportResult.Success>>(emptyList()) }
    var consentSuccesses by remember { mutableStateOf<List<com.suyaphot.app.domain.importmedia.ImportResult.Success>>(emptyList()) }
    var deletionTrigger by remember { mutableIntStateOf(0) }

    fun markJobsTerminal(
        results: List<com.suyaphot.app.domain.importmedia.ImportResult.Success>,
        disposition: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            for (res in results) {
                container.database.vaultJobDao().updateState(
                    res.jobId,
                    com.suyaphot.app.core.model.JobState.COMPLETED.code,
                    now,
                    disposition
                )
            }
        }
    }

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            markJobsTerminal(consentSuccesses, "SOURCE_DELETE_APPROVED")
        } else {
            markJobsTerminal(consentSuccesses, "ORIGINAL_RETAINED_BY_USER")
        }
        pendingSuccesses = pendingSuccesses.filterNot { pending ->
            consentSuccesses.any { it.jobId == pending.jobId }
        }
        consentSuccesses = emptyList()
        deletionTrigger++
    }

    LaunchedEffect(Unit) {
        scope.launch {
            statusText = "Moving ${uris.size} items to Suya Phot..."
            val results = container.importCoordinator.importBatch(
                uris = uris,
                folderId = null,
                mode = ImportMode.MOVE,
                onItemComplete = { current, total, _ ->
                    scope.launch { statusText = "Encrypting $current of $total items..." }
                }
            )

            val successfulResults = results.filterIsInstance<com.suyaphot.app.domain.importmedia.ImportResult.Success>()
            val successfulUris = successfulResults.map { it.uri }
            pendingSuccesses = successfulResults

            if (successfulUris.isNotEmpty()) {
                deletionTrigger++
            } else {
                isDone = true
                statusText = "No new items imported."
            }
        }
    }

    LaunchedEffect(deletionTrigger) {
        if (deletionTrigger == 0 || pendingSuccesses.isEmpty()) {
            if (deletionTrigger > 0) {
                isDone = true
                statusText = "Move complete. Any originals Android retained are shown accurately above."
            }
            return@LaunchedEffect
        }
        statusText = "Requesting deletion of originals..."
        val current = pendingSuccesses
        when (val outcome = container.sourceDeletionCoordinator.deleteSources(current.map { it.uri })) {
            is com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                val completed = current.filter { it.uri in outcome.deletedUris }
                markJobsTerminal(completed, "SOURCE_DELETED")
                pendingSuccesses = current.filterNot { it.uri in outcome.deletedUris }
                deletionTrigger++
            }
            is com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                val directlyDeleted = current.filter { it.uri in outcome.deletedUris }
                markJobsTerminal(directlyDeleted, "SOURCE_DELETED")
                pendingSuccesses = current.filterNot { it.uri in outcome.deletedUris }
                consentSuccesses = pendingSuccesses.filter { it.uri in outcome.uris }
                deleteRequestLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
            }
            is com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                val directlyDeleted = current.filter { it.uri in outcome.deletedUris }
                markJobsTerminal(directlyDeleted, "SOURCE_DELETED")
                val retained = current.filter { it.uri in outcome.uris }
                markJobsTerminal(retained, "SOURCE_DELETE_FAILED_VAULT_SAFE")
                pendingSuccesses = emptyList()
                isDone = true
                statusText = "Imported safely. Some originals remain in Gallery."
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
            .padding(24.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SuyaColors.Surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
            modifier = Modifier.padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                if (!isDone) {
                    CircularProgressIndicator(
                        color = SuyaColors.Accent,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                }
                Text(
                    text = statusText,
                    fontFamily = SoraFontFamily,
                    fontSize = 15.sp,
                    color = SuyaColors.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                if (isDone) {
                    Spacer(modifier = Modifier.height(20.dp))
                    SuyaButton(
                        text = "Done",
                        onClick = onFinish
                    )
                }
            }
        }
    }
}
