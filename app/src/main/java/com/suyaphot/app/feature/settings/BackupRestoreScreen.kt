package com.suyaphot.app.feature.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.backup.BackupError
import com.suyaphot.app.domain.backup.BackupException
import com.suyaphot.app.domain.backup.BackupSummary
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.PinDots
import com.suyaphot.app.ui.components.PatternLockPad
import com.suyaphot.app.ui.components.SecurePinPad
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaTextField
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BackupRestoreMode {
    SETUP_RESTORE_ONLY,
    UNLOCKED_VAULT
}

@Composable
fun BackupRestoreScreen(
    container: AppContainer,
    onBack: () -> Unit,
    mode: BackupRestoreMode = BackupRestoreMode.UNLOCKED_VAULT,
    onNavigateToFolders: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionState by container.sessionManager.sessionState.collectAsState()
    val isRealVault = (sessionState as? VaultSession.Unlocked)?.kind == VaultKind.REAL

    var isExporting by remember { mutableStateOf(false) }
    var exportProgressText by remember { mutableStateOf("") }
    var exportProgressFraction by remember { mutableFloatStateOf(0f) }

    var showExportSecretDialog by remember { mutableStateOf(false) }
    var exportRecoveryCode by remember { mutableStateOf("") }
    var exportCodeRevealed by remember { mutableStateOf(false) }
    var pendingExportCode by remember { mutableStateOf<String?>(null) }
    var showFolderPrepDialog by remember { mutableStateOf(false) }
    var unreadyFolderCount by remember { mutableIntStateOf(0) }

    var isRestoring by remember { mutableStateOf(false) }
    var restoreProgressText by remember { mutableStateOf("") }
    var restoreProgressFraction by remember { mutableFloatStateOf(0f) }

    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var showRestoreSecretDialog by remember { mutableStateOf(false) }
    var restoreRecoveryCode by remember { mutableStateOf("") }
    var restoreCodeRevealed by remember { mutableStateOf(false) }
    var restoreSummary by remember { mutableStateOf<BackupSummary?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }

    // New credential setup state for restore
    var credentialStep by remember { mutableStateOf(false) }
    var newCredentialType by remember { mutableIntStateOf(0) } // 0 = PIN, 1 = Pattern
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinStage by remember { mutableIntStateOf(0) } // 0 = Enter, 1 = Confirm
    var patternFirst by remember { mutableStateOf<CharArray?>(null) }
    var patternErrorTrigger by remember { mutableIntStateOf(0) }
    var credentialError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            pendingExportCode = null
            exportRecoveryCode = ""
            restoreRecoveryCode = ""
            newPin = ""
            confirmPin = ""
            patternFirst?.fill('\u0000')
            patternFirst = null
        }
    }

    fun startExport(uri: Uri, code: String) {
        isExporting = true
        exportProgressFraction = 0f
        exportProgressText = "Preparing archive..."
        scope.launch(Dispatchers.IO) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                    ?: throw BackupException(BackupError.DESTINATION_UNAVAILABLE)
                pfd.use { fd ->
                    val outputStream = FileOutputStream(fd.fileDescriptor)
                    outputStream.use { os ->
                        container.vaultBackupExporter.exportVault(
                            outputStream = os,
                            recoveryCodeInput = code,
                            onProgress = { bytesWritten, totalEstimated, itemsWritten, totalItems ->
                                scope.launch(Dispatchers.Main) {
                                    val fraction = if (totalEstimated > 0) (bytesWritten.toFloat() / totalEstimated).coerceIn(0f, 1f) else 0f
                                    exportProgressFraction = fraction
                                    exportProgressText = "Exported $itemsWritten of $totalItems items (${bytesWritten / (1024 * 1024)} MB)"
                                }
                            }
                        )
                        os.flush()
                        try {
                            fd.fileDescriptor.sync()
                        } catch (_: Exception) {}
                    }
                }

                // Post-write verification of the written SAF file
                withContext(Dispatchers.Main) {
                    exportProgressText = "Verifying written archive..."
                }
                val verifyInputStream = context.contentResolver.openInputStream(uri)
                    ?: throw BackupException(BackupError.SOURCE_UNAVAILABLE, "Could not reopen exported archive for verification")
                val verifiedSummary = verifyInputStream.use { ins ->
                    container.backupVerifier.verifyFullArchive(ins, code)
                }

                withContext(Dispatchers.Main) {
                    isExporting = false
                    showExportSecretDialog = false
                    exportRecoveryCode = ""
                    Toast.makeText(context, "Exported ${verifiedSummary.mediaCount} items successfully and verified archive.", Toast.LENGTH_LONG).show()
                }
            } catch (e: BackupException) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                withContext(Dispatchers.Main) {
                    isExporting = false
                    if (e.error == BackupError.FOLDER_LOCK_RECOVERY_NOT_READY) {
                        val session = sessionState as? VaultSession.Unlocked
                        if (session != null) {
                            val locks = container.database.folderLockDao().getAllForVault(session.vaultId)
                            unreadyFolderCount = locks.count { it.recoveryEnvelope == null }
                        }
                        showFolderPrepDialog = true
                    } else {
                        Toast.makeText(context, e.error.userFriendlyMessage(), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                withContext(Dispatchers.Main) {
                    isExporting = false
                    Toast.makeText(context, BackupError.VERIFICATION_FAILED.userFriendlyMessage(), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun inspectBackup(code: String) {
        val uri = restoreUri ?: return
        restoreError = null
        scope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw BackupException(BackupError.SOURCE_UNAVAILABLE)
                val summary = inputStream.use { ins ->
                    if (mode == BackupRestoreMode.UNLOCKED_VAULT) {
                        container.backupVerifier.verifyFullArchive(ins, code)
                    } else {
                        container.backupVerifier.inspectManifest(ins, code)
                    }
                }
                withContext(Dispatchers.Main) {
                    restoreSummary = summary
                    if (mode == BackupRestoreMode.SETUP_RESTORE_ONLY) {
                        credentialStep = true
                        newPin = ""
                        confirmPin = ""
                        pinStage = 0
                        patternFirst?.fill('\u0000')
                        patternFirst = null
                        credentialError = null
                    } else {
                        credentialStep = false
                    }
                }
            } catch (e: BackupException) {
                withContext(Dispatchers.Main) {
                    restoreError = e.error.userFriendlyMessage()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    restoreError = BackupError.INVALID_ARCHIVE.userFriendlyMessage()
                }
            }
        }
    }

    fun startRestore(finalCredential: CharArray) {
        val uri = restoreUri ?: run { finalCredential.fill('\u0000'); return }
        isRestoring = true
        showRestoreSecretDialog = false
        restoreProgressFraction = 0f
        restoreProgressText = "Beginning restore..."
        scope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw BackupException(BackupError.SOURCE_UNAVAILABLE)
                inputStream.use { ins ->
                    val result = container.vaultBackupImporter.restoreVault(
                        inputStream = ins,
                        recoveryCodeInput = restoreRecoveryCode,
                        newCredential = finalCredential,
                        newCredentialType = newCredentialType,
                        onProgress = { bytesRead, totalEstimated, itemsRead, totalItems ->
                            scope.launch(Dispatchers.Main) {
                                val fraction = if (totalEstimated > 0) (bytesRead.toFloat() / totalEstimated).coerceIn(0f, 1f) else 0f
                                restoreProgressFraction = fraction
                                restoreProgressText = "Restored $itemsRead of $totalItems items..."
                            }
                        }
                    )
                    withContext(Dispatchers.Main) {
                        isRestoring = false
                        restoreRecoveryCode = ""
                        Toast.makeText(context, "Restored ${result.mediaCount} items into vault!", Toast.LENGTH_LONG).show()
                        onBack()
                    }
                }
            } catch (e: BackupException) {
                withContext(Dispatchers.Main) {
                    isRestoring = false
                    Toast.makeText(context, e.error.userFriendlyMessage(), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isRestoring = false
                    Toast.makeText(context, BackupError.INVALID_ARCHIVE.userFriendlyMessage(), Toast.LENGTH_LONG).show()
                }
            } finally {
                finalCredential.fill('\u0000')
            }
        }
    }

    // SAF Document Launchers
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        container.sessionManager.endSystemActivity()
        val code = pendingExportCode
        pendingExportCode = null
        if (uri != null) {
            if (!code.isNullOrBlank()) {
                startExport(uri, code)
            }
        }
    }

    val restoreDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        container.sessionManager.endSystemActivity()
        if (uri != null) {
            restoreUri = uri
            restoreRecoveryCode = ""
            restoreCodeRevealed = false
            restoreSummary = null
            restoreError = null
            credentialStep = false
            showRestoreSecretDialog = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SuyaTopBar(
                title = "Backup & Restore",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onBack
            )

            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Info Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SuyaColors.Surface,
                    border = BorderStroke(1.dp, SuyaColors.Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = SuyaColors.Accent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Zero-Cloud Local Backup", fontFamily = SoraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SuyaColors.White)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Backups are encrypted using your 26-character Recovery Code and saved directly to your device storage or external drive.",
                                fontFamily = SoraFontFamily,
                                fontSize = 13.sp,
                                color = SuyaColors.TextMuted
                            )
                        }
                    }
                }

                // Export Card (Only shown if primary real vault and mode is UNLOCKED_VAULT)
                if (mode == BackupRestoreMode.UNLOCKED_VAULT && isRealVault) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SuyaColors.Fill06,
                        border = BorderStroke(1.dp, SuyaColors.Line),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = SuyaColors.Accent)
                                Spacer(Modifier.width(8.dp))
                                Text("Export Backup", fontFamily = SoraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = SuyaColors.White)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Package all photos, videos, albums, and folder locks into an encrypted .suyavault file.",
                                fontFamily = SoraFontFamily,
                                fontSize = 13.sp,
                                color = SuyaColors.TextMuted
                            )
                            Spacer(Modifier.height(14.dp))
                            if (isExporting) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LinearProgressIndicator(
                                        progress = { exportProgressFraction },
                                        modifier = Modifier.fillMaxWidth().height(6.dp),
                                        color = SuyaColors.Accent,
                                        trackColor = SuyaColors.Line
                                    )
                                    Text(
                                        text = exportProgressText,
                                        fontFamily = SoraFontFamily,
                                        fontSize = 12.sp,
                                        color = SuyaColors.TextMuted
                                    )
                                }
                            } else {
                                SuyaButton(
                                    text = "Export .suyavault",
                                    onClick = {
                                        exportRecoveryCode = ""
                                        exportCodeRevealed = false
                                        showExportSecretDialog = true
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("backup_export_button")
                                )
                            }
                        }
                    }
                }

                // Restore Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SuyaColors.Fill06,
                    border = BorderStroke(1.dp, SuyaColors.Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = if (mode == BackupRestoreMode.SETUP_RESTORE_ONLY) SuyaColors.Accent else SuyaColors.TextMuted
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (mode == BackupRestoreMode.SETUP_RESTORE_ONLY) "Restore Vault" else "Restore a Vault",
                                fontFamily = SoraFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = SuyaColors.White
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        if (mode == BackupRestoreMode.SETUP_RESTORE_ONLY) {
                            Text(
                                "Restore an existing .suyavault backup using the Recovery Code that protected it. You will choose a new PIN or Pattern for this device.",
                                fontFamily = SoraFontFamily,
                                fontSize = 13.sp,
                                color = SuyaColors.TextMuted
                            )
                            Spacer(Modifier.height(14.dp))
                            SuyaButton(
                                text = "Open Backup File",
                                onClick = {
                                    container.sessionManager.beginSystemActivity()
                                    restoreDocumentLauncher.launch(arrayOf("*/*"))
                                },
                                variant = ButtonVariant.Secondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("backup_open_file_button")
                            )
                        } else {
                            Text(
                                "For safety, restoring is only available before a primary vault exists. Export your current vault first, then restore this backup on a fresh installation or another device.",
                                fontFamily = SoraFontFamily,
                                fontSize = 13.sp,
                                color = SuyaColors.TextMuted,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(14.dp))
                            SuyaButton(
                                text = "Inspect Backup (Read-Only)",
                                onClick = {
                                    container.sessionManager.beginSystemActivity()
                                    restoreDocumentLauncher.launch(arrayOf("*/*"))
                                },
                                variant = ButtonVariant.Secondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("backup_inspect_file_button")
                            )
                        }
                    }
                }
            }
        }

        // Folder Lock Recovery Not Ready Dialog
        if (showFolderPrepDialog) {
            SuyaDialog(
                onDismissRequest = { showFolderPrepDialog = false },
                title = "Protected folders need preparation",
                confirmText = if (onNavigateToFolders != null) "Go to Folders" else "OK",
                onConfirm = {
                    showFolderPrepDialog = false
                    onNavigateToFolders?.invoke()
                },
                dismissText = if (onNavigateToFolders != null) "Cancel" else "Dismiss",
                content = {
                    Text(
                        text = "${if (unreadyFolderCount > 0) "$unreadyFolderCount protected folder(s) were" else "Protected folders were"} created with an older version of Suya Phot.\n\nUnlock each protected folder once so Suya Phot can create portable recovery information before making this backup.",
                        fontFamily = SoraFontFamily,
                        fontSize = 13.sp,
                        color = SuyaColors.TextMuted,
                        lineHeight = 18.sp
                    )
                }
            )
        }

        // Export Prompt Dialog
        if (showExportSecretDialog) {
            SuyaDialog(
                onDismissRequest = {
                    showExportSecretDialog = false
                    exportRecoveryCode = ""
                },
                title = "Confirm Recovery Code",
                confirmText = "Begin Export",
                onConfirm = {
                    if (container.keyManager.isValidRecoverySecret(exportRecoveryCode)) {
                        val codeToExport = exportRecoveryCode
                        showExportSecretDialog = false
                        exportRecoveryCode = ""
                        pendingExportCode = codeToExport
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                        container.sessionManager.beginSystemActivity()
                        exportDocumentLauncher.launch("suya_phot_backup_$timestamp.suyavault")
                    } else {
                        Toast.makeText(context, "Recovery code must be exactly 26 Base32 characters", Toast.LENGTH_SHORT).show()
                    }
                },
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Enter your 26-character Recovery Code to prove ownership and wrap the backup key.",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.TextMuted
                        )
                        SuyaTextField(
                            value = exportRecoveryCode,
                            onValueChange = { exportRecoveryCode = it.uppercase() },
                            label = "Recovery Code",
                            placeholder = "e.g. 7K9P-4X2B-W8MN-...",
                            visualTransformation = if (exportCodeRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { exportCodeRevealed = !exportCodeRevealed }) {
                                    Icon(
                                        imageVector = if (exportCodeRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (exportCodeRevealed) "Hide Code" else "Reveal Code",
                                        tint = SuyaColors.TextMuted
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            )
        }

        // Restore Flow Dialog
        if (showRestoreSecretDialog) {
            val isInspectMode = mode == BackupRestoreMode.UNLOCKED_VAULT && restoreSummary != null
            SuyaDialog(
                onDismissRequest = {
                    showRestoreSecretDialog = false
                    restoreRecoveryCode = ""
                    newPin = ""
                    confirmPin = ""
                    patternFirst?.fill('\u0000')
                    patternFirst = null
                    restoreSummary = null
                    credentialStep = false
                },
                title = if (isInspectMode) "Backup Inspection" else if (!credentialStep) "Verify Backup" else "Set Device Credential",
                confirmText = if (isInspectMode) "Done" else if (credentialStep && (newCredentialType == 0 && pinStage == 1 && confirmPin.length == 6)) "Restore Now" else if (!credentialStep) "Verify" else null,
                onConfirm = if (isInspectMode) ({
                    showRestoreSecretDialog = false
                    restoreRecoveryCode = ""
                    restoreSummary = null
                }) else if (!credentialStep) ({
                    inspectBackup(restoreRecoveryCode)
                }) else if (credentialStep && newCredentialType == 0 && pinStage == 1 && confirmPin.length == 6) ({
                    if (newPin == confirmPin) {
                        startRestore(newPin.toCharArray())
                    } else {
                        credentialError = "PINs do not match"
                    }
                }) else null,
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isInspectMode) {
                            val sum = restoreSummary!!
                            Text(
                                "Archive integrity verified",
                                fontFamily = SoraFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = SuyaColors.Positive
                            )
                            Text(
                                "The encrypted manifest and archived file bodies are complete and match their authenticated checksums.\n\nFinal media and metadata cryptographic verification is performed before restore is committed.",
                                fontFamily = SoraFontFamily,
                                fontSize = 12.sp,
                                color = SuyaColors.TextMuted,
                                lineHeight = 16.sp
                            )
                            Text(
                                "Contains ${sum.mediaCount} media items and ${sum.folderCount} folders (${sum.totalPlaintextSize / (1024 * 1024)} MB).",
                                fontFamily = SoraFontFamily,
                                fontSize = 13.sp,
                                color = SuyaColors.White
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "For safety, restoring is only available before a primary vault exists. Export your current vault first, then restore this backup on a fresh installation or another device.",
                                fontFamily = SoraFontFamily,
                                fontSize = 12.sp,
                                color = SuyaColors.TextMuted,
                                lineHeight = 16.sp
                            )
                        } else if (!credentialStep) {
                            Text(
                                "Enter the 26-character Recovery Code that protects this backup archive.",
                                fontFamily = SoraFontFamily,
                                fontSize = 13.sp,
                                color = SuyaColors.TextMuted
                            )
                            SuyaTextField(
                                value = restoreRecoveryCode,
                                onValueChange = { restoreRecoveryCode = it.uppercase() },
                                label = "Recovery Code",
                                placeholder = "XXXX-XXXX-XXXX-...",
                                visualTransformation = if (restoreCodeRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { restoreCodeRevealed = !restoreCodeRevealed }) {
                                        Icon(
                                            imageVector = if (restoreCodeRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (restoreCodeRevealed) "Hide Code" else "Reveal Code",
                                            tint = SuyaColors.TextMuted
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            restoreError?.let {
                                Text(it, color = SuyaColors.Negative, fontSize = 13.sp, fontFamily = SoraFontFamily)
                            }
                        } else {
                            restoreSummary?.let { sum ->
                                Text(
                                    "Backup header verified. Full media verification runs before restore is committed.",
                                    fontFamily = SoraFontFamily,
                                    fontSize = 13.sp,
                                    color = SuyaColors.Positive,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Found: ${sum.mediaCount} media items, ${sum.folderCount} folders (${sum.totalPlaintextSize / (1024 * 1024)} MB).",
                                    fontFamily = SoraFontFamily,
                                    fontSize = 13.sp,
                                    color = SuyaColors.White,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                "Choose a lock credential to unlock this vault on this phone:",
                                fontFamily = SoraFontFamily,
                                fontSize = 13.sp,
                                color = SuyaColors.TextMuted
                            )

                            // Choose PIN or Pattern
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = newCredentialType == 0,
                                        onClick = { newCredentialType = 0; newPin = ""; confirmPin = ""; pinStage = 0 },
                                        colors = RadioButtonDefaults.colors(selectedColor = SuyaColors.Accent)
                                    )
                                    Text("6-digit PIN", fontFamily = SoraFontFamily, fontSize = 13.sp, color = SuyaColors.White)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = newCredentialType == 1,
                                        onClick = { newCredentialType = 1; patternFirst?.fill('\u0000'); patternFirst = null },
                                        colors = RadioButtonDefaults.colors(selectedColor = SuyaColors.Accent)
                                    )
                                    Text("Pattern", fontFamily = SoraFontFamily, fontSize = 13.sp, color = SuyaColors.White)
                                }
                            }

                            credentialError?.let {
                                Text(it, color = SuyaColors.Negative, fontSize = 12.sp, fontFamily = SoraFontFamily)
                            }

                            if (newCredentialType == 0) {
                                // PIN entry
                                Text(
                                    if (pinStage == 0) "Enter 6-digit PIN" else "Confirm 6-digit PIN",
                                    fontFamily = SoraFontFamily,
                                    fontSize = 14.sp,
                                    color = SuyaColors.Accent,
                                    fontWeight = FontWeight.SemiBold
                                )
                                PinDots(pinLength = 6, enteredCount = if (pinStage == 0) newPin.length else confirmPin.length)
                                SecurePinPad(
                                    onDigitClick = { d ->
                                        if (pinStage == 0 && newPin.length < 6) {
                                            newPin += d
                                            if (newPin.length == 6) pinStage = 1
                                        } else if (pinStage == 1 && confirmPin.length < 6) {
                                            confirmPin += d
                                        }
                                    },
                                    onBackspaceClick = {
                                        if (pinStage == 1 && confirmPin.isNotEmpty()) {
                                            confirmPin = confirmPin.dropLast(1)
                                        } else if (pinStage == 1 && confirmPin.isEmpty()) {
                                            pinStage = 0
                                            newPin = ""
                                        } else if (pinStage == 0 && newPin.isNotEmpty()) {
                                            newPin = newPin.dropLast(1)
                                        }
                                    }
                                )
                            } else {
                                // Pattern entry
                                Text(
                                    if (patternFirst == null) "Draw a pattern (at least 4 dots)" else "Confirm pattern",
                                    fontFamily = SoraFontFamily,
                                    fontSize = 14.sp,
                                    color = SuyaColors.Accent,
                                    fontWeight = FontWeight.SemiBold
                                )
                                PatternLockPad(
                                    onPatternComplete = { patternInts ->
                                        if (patternInts.size < 4) {
                                            credentialError = "Pattern must connect at least 4 dots"
                                            patternErrorTrigger++
                                            return@PatternLockPad
                                        }
                                        val chars = PatternCredential.canonicalChars(patternInts)
                                        if (patternFirst == null) {
                                            credentialError = null
                                            patternFirst = chars
                                        } else {
                                            if (chars.contentEquals(patternFirst)) {
                                                startRestore(chars)
                                            } else {
                                                credentialError = "Patterns do not match. Try again."
                                                patternErrorTrigger++
                                                patternFirst?.fill('\u0000')
                                                patternFirst = null
                                            }
                                        }
                                    },
                                    errorTrigger = patternErrorTrigger,
                                    modifier = Modifier.size(240.dp).align(Alignment.CenterHorizontally)
                                )
                            }
                        }
                    }
                }
            )
        }

        // Progress Overlay
        if (isExporting || isRestoring) {
            Surface(
                color = SuyaColors.Background.copy(alpha = 0.92f),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = SuyaColors.Accent, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = if (isExporting) "Exporting Backup" else "Restoring Vault",
                        fontFamily = SoraFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = SuyaColors.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isExporting) exportProgressText else restoreProgressText,
                        fontFamily = SoraFontFamily,
                        fontSize = 13.sp,
                        color = SuyaColors.TextMuted
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { if (isExporting) exportProgressFraction else restoreProgressFraction },
                        color = SuyaColors.Accent,
                        trackColor = SuyaColors.Line,
                        modifier = Modifier.fillMaxWidth(0.8f)
                    )
                }
            }
        }
    }
}
