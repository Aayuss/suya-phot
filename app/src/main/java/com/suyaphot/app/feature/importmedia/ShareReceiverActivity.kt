package com.suyaphot.app.feature.importmedia

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.app.SuyaApp
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.feature.lock.LockScreen
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaTextField
import com.suyaphot.app.ui.components.PatternLockPad
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.core.model.Folder
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import com.suyaphot.app.ui.theme.SuyaTheme
import com.suyaphot.app.core.model.ImportMode
import androidx.core.content.ContextCompat
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
                var destinationChosen by remember { mutableStateOf(false) }
                var destinationFolderId by remember { mutableStateOf<String?>(null) }

                if (!isUnlocked) {
                    LockScreen(
                        container = container,
                        onUnlocked = {
                            // Session unlocked, composable will recompose and proceed with import
                        }
                    )
                } else if (!destinationChosen) {
                    ImportDestinationPicker(container, (session as VaultSession.Unlocked).vaultId) { chosen ->
                        destinationFolderId = chosen
                        destinationChosen = true
                    }
                } else {
                    ImportProgressView(
                        uris = sharedUris,
                        folderId = destinationFolderId,
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
private fun ImportDestinationPicker(
    container: com.suyaphot.app.app.AppContainer,
    vaultId: String,
    onChosen: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val accessRevision by container.folderAccessManager.revision.collectAsState()
    var hiddenMode by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var pendingFolderId by remember { mutableStateOf<String?>(null) }
    var pendingLockId by remember { mutableStateOf<String?>(null) }
    var hiddenGate by remember { mutableStateOf(false) }
    var gateType by remember { mutableIntStateOf(0) }
    var gateInput by remember { mutableStateOf("") }
    var gateError by remember { mutableStateOf<String?>(null) }
    var patternErrorTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(vaultId, hiddenMode, accessRevision) {
        if (hiddenMode && !container.folderAccessManager.hasHiddenGrant(vaultId)) hiddenMode = false
        candidates = container.folderManager.getDestinationCandidates(hiddenMode)
    }

    fun chooseFolder(id: String) {
        scope.launch {
            if (container.folderAccessManager.canOpen(vaultId, id)) {
                onChosen(id)
                return@launch
            }
            val missing = container.folderAccessManager.missingLockIds(vaultId, id) ?: return@launch
            val lockId = missing.firstOrNull() ?: return@launch
            pendingFolderId = id
            pendingLockId = lockId
            gateType = container.database.folderLockDao().getForVault(vaultId, lockId)?.credentialTypeCode ?: 0
            gateInput = ""
            gateError = null
        }
    }

    fun submitGate(chars: CharArray) {
        scope.launch {
            val success = if (hiddenGate) {
                container.pinAuthenticator.verifyCurrentCredential(chars, gateType)
            } else {
                container.folderLockManager.unlock(pendingLockId ?: return@launch, chars, gateType)
            }
            chars.fill('\u0000')
            gateInput = ""
            if (!success) {
                gateError = "Incorrect credential"
                patternErrorTrigger++
                return@launch
            }
            gateError = null
            if (hiddenGate) {
                container.folderAccessManager.grantHidden(vaultId)
                hiddenMode = true
                hiddenGate = false
            } else {
                pendingLockId = null
                pendingFolderId?.let { chooseFolder(it) }
            }
        }
    }

    val byId = candidates.associateBy { it.id }
    fun pathFor(folder: Folder): String {
        val parts = ArrayList<String>()
        val seen = HashSet<String>()
        var cursor: Folder? = folder
        while (cursor != null && seen.add(cursor.id)) {
            parts.add(0, cursor.name)
            cursor = cursor.parentId?.let(byId::get)
        }
        return parts.joinToString(" / ")
    }

    Column(Modifier.fillMaxSize().background(SuyaColors.Background).padding(18.dp)) {
        Text("Move to Suya Phot", color = SuyaColors.White, fontSize = 20.sp)
        Text("Choose where to import before Android asks to delete the public originals.",
            color = SuyaColors.TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        SuyaButton("Root (All Photos)", onClick = { onChosen(null) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SuyaButton(if (hiddenMode) "Ordinary folders" else "Open Hidden folders",
            onClick = {
                if (hiddenMode) hiddenMode = false
                else scope.launch {
                    val vault = container.database.vaultDao().getVault(vaultId)
                    gateType = vault?.credentialTypeCode ?: 0
                    gateInput = ""
                    gateError = null
                    hiddenGate = true
                }
            }, variant = ButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(candidates, key = { it.id }) { folder ->
                SuyaButton(
                    text = pathFor(folder) + if (folder.lockId != null) "  • Locked" else "",
                    onClick = { chooseFolder(folder.id) },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (hiddenGate || pendingLockId != null) {
        SuyaDialog(
            onDismissRequest = { hiddenGate = false; pendingLockId = null; pendingFolderId = null; gateInput = "" },
            title = if (hiddenGate) "Open Hidden folders" else "Unlock folder",
            confirmText = if (gateType == 0) "Unlock" else null,
            onConfirm = if (gateType == 0) ({ submitGate(gateInput.toCharArray()) }) else null,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (gateType == 0) {
                        SuyaTextField(gateInput,
                            onValueChange = { gateInput = it.filter(Char::isDigit).take(12) },
                            label = if (hiddenGate) "Vault PIN" else "Folder PIN",
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    } else {
                        PatternLockPad(onPatternComplete = { raw ->
                            val chars = runCatching { PatternCredential.canonicalChars(raw) }.getOrNull()
                            if (chars == null) { gateError = "Connect at least four dots"; patternErrorTrigger++ }
                            else submitGate(chars)
                        }, errorTrigger = patternErrorTrigger, enabled = true, modifier = Modifier.fillMaxWidth())
                    }
                    gateError?.let { Text(it, color = SuyaColors.Negative) }
                }
            }
        )
    }
}

@Composable
private fun ImportProgressView(
    uris: List<Uri>,
    folderId: String?,
    container: com.suyaphot.app.app.AppContainer,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("Preparing import...") }
    var isDone by remember { mutableStateOf(false) }
    var pendingSuccesses by remember { mutableStateOf<List<com.suyaphot.app.domain.importmedia.ImportResult.Success>>(emptyList()) }
    var consentSuccesses by remember { mutableStateOf<List<com.suyaphot.app.domain.importmedia.ImportResult.Success>>(emptyList()) }
    var consentMode by remember { mutableStateOf<com.suyaphot.app.domain.importmedia.SourceDeletionCoordinator.DeleteConsentMode?>(null) }
    var deletionTrigger by remember { mutableIntStateOf(0) }
    var locationPermissionResolved by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) statusText = "Location permission denied; GPS/original bytes may be redacted. Importing safely..."
        locationPermissionResolved = true
    }
    LaunchedEffect(Unit) {
        if (!locationPermissionResolved) locationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

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
        scope.launch {
            val currentConsent = consentSuccesses
            if (result.resultCode == Activity.RESULT_OK && consentMode != null) {
                val verified = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    container.sourceDeletionCoordinator.completeConsent(currentConsent.map { it.uri }, consentMode!!)
                }
                markJobsTerminal(currentConsent.filter { it.uri in verified.deletedUris }, "SOURCE_DELETED")
                markJobsTerminal(currentConsent.filter { it.uri in verified.retainedUris }, "SOURCE_DELETE_FAILED_VAULT_SAFE")
                if (verified.retainedUris.isNotEmpty()) {
                    statusText = "Imported safely. Some originals remain in Gallery."
                }
            } else {
                markJobsTerminal(currentConsent, "ORIGINAL_RETAINED_BY_USER")
            }
            pendingSuccesses = pendingSuccesses.filterNot { pending ->
                currentConsent.any { it.jobId == pending.jobId }
            }
            consentSuccesses = emptyList()
            consentMode = null
            deletionTrigger++
        }
    }

    LaunchedEffect(locationPermissionResolved) {
        if (!locationPermissionResolved) return@LaunchedEffect
        scope.launch {
            statusText = "Moving ${uris.size} items to Suya Phot..."
            val results = container.importCoordinator.importBatch(
                uris = uris,
                folderId = folderId,
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
                consentMode = outcome.mode
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
