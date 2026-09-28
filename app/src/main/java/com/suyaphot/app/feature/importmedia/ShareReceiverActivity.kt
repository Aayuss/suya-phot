package com.suyaphot.app.feature.importmedia

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
        val out = LinkedHashSet<Uri>()

        if (intent.action == Intent.ACTION_SEND) {
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            streamUri?.let { out.add(it) }

            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { out.add(it) }
                }
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            streamUris?.let { out.addAll(it) }

            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { out.add(it) }
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

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        isDone = true
        statusText = if (result.resultCode == Activity.RESULT_OK) {
            "Originals deleted from Gallery. Vault secured."
        } else {
            "Imported safely. Originals kept in Gallery."
        }
    }

    LaunchedEffect(Unit) {
        scope.launch {
            statusText = "Moving ${uris.size} items to Suya Phot..."
            val results = container.importCoordinator.importBatch(
                uris = uris,
                folderId = null,
                onItemComplete = { current, total, _ ->
                    statusText = "Encrypting $current of $total items..."
                }
            )

            val successfulUris = results.filterIsInstance<com.suyaphot.app.domain.importmedia.ImportResult.Success>()
                .map { it.uri }

            if (successfulUris.isNotEmpty()) {
                statusText = "Requesting deletion of originals..."
                when (val outcome = container.sourceDeletionCoordinator.deleteSources(successfulUris)) {
                    is com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator.DeletionOutcome.CompletedDirectly -> {
                        isDone = true
                        statusText = "Successfully imported ${successfulUris.size} items."
                    }
                    is com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator.DeletionOutcome.RequiresUserConsent -> {
                        deleteRequestLauncher.launch(
                            IntentSenderRequest.Builder(outcome.intentSender).build()
                        )
                    }
                    is com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator.DeletionOutcome.Failed -> {
                        isDone = true
                        statusText = "Imported to vault. Originals remain in gallery."
                    }
                }
            } else {
                isDone = true
                statusText = "No new items imported."
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
