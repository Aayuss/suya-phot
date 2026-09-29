package com.suyaphot.app.feature.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.domain.auth.PatternCredential
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupRestoreScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isExporting by remember { mutableStateOf(false) }
    var exportProgressText by remember { mutableStateOf("") }
    var exportProgressFraction by remember { mutableFloatStateOf(0f) }

    var showExportSecretDialog by remember { mutableStateOf(false) }
    var exportRecoveryCode by remember { mutableStateOf("") }
    var exportTargetUri by remember { mutableStateOf<Uri?>(null) }

    var isRestoring by remember { mutableStateOf(false) }
    var restoreProgressText by remember { mutableStateOf("") }
    var restoreProgressFraction by remember { mutableFloatStateOf(0f) }

    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var showRestoreSecretDialog by remember { mutableStateOf(false) }
    var restoreRecoveryCode by remember { mutableStateOf("") }
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

    // SAF Document Launchers
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            exportTargetUri = uri
            showExportSecretDialog = true
        }
    }

    val restoreDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            restoreUri = uri
            restoreRecoveryCode = ""
            restoreSummary = null
            restoreError = null
            credentialStep = false
            showRestoreSecretDialog = true
        }
    }

    fun startExport(code: String) {
        val uri = exportTargetUri ?: return
        isExporting = true
        exportProgressFraction = 0f
        exportProgressText = "Preparing archive..."
        scope.launch(Dispatchers.IO) {
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open destination file")
                outputStream.use { os ->
                    val result = container.vaultBackupExporter.exportVault(
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
                    withContext(Dispatchers.Main) {
                        isExporting = false
                        Toast.makeText(context, "Exported ${result.mediaCount} items successfully", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isExporting = false
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
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
                    ?: throw IllegalStateException("Could not open backup file")
                val summary = inputStream.use { ins ->
                    container.backupVerifier.verifyAndInspect(ins, code)
                }
                withContext(Dispatchers.Main) {
                    restoreSummary = summary
                    credentialStep = true
                    newPin = ""
                    confirmPin = ""
                    pinStage = 0
                    patternFirst = null
                    credentialError = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    restoreError = e.message ?: "Failed to verify backup"
                }
            }
        }
    }

    fun startRestore(finalCredential: CharArray) {
        val uri = restoreUri ?: return
        isRestoring = true
        showRestoreSecretDialog = false
        restoreProgressFraction = 0f
        restoreProgressText = "Beginning restore..."
        scope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open backup file")
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
                        Toast.makeText(context, "Restored ${result.mediaCount} items into vault!", Toast.LENGTH_LONG).show()
                        onBack()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isRestoring = false
                    Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
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
                    color = SuyaColors.Fill06,
                    border = BorderStroke(1.dp, SuyaColors.Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = SuyaColors.Accent)
                            Spacer(Modifier.width(8.dp))
                            Text("Portable Encrypted Vault", fontFamily = SoraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SuyaColors.White)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Export your vault into a standalone .suyavault archive. Backups are encrypted end-to-end and can be restored on any Android device using your 26-character Recovery Code without relying on device-specific Keystore keys.",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.TextMuted,
                            lineHeight = 18.sp
                        )
                    }
                }

                // Export Card
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
                        SuyaButton(
                            text = "Export .suyavault",
                            onClick = {
                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportDocumentLauncher.launch("suya_phot_backup_$timestamp.suyavault")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
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
                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = SuyaColors.Accent)
                            Spacer(Modifier.width(8.dp))
                            Text("Restore Vault", fontFamily = SoraFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = SuyaColors.White)
                        }
                        Spacer(Modifier.height(6.dp))
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
                                restoreDocumentLauncher.launch(arrayOf("*/*"))
                            },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Export Prompt Dialog
        if (showExportSecretDialog) {
            SuyaDialog(
                onDismissRequest = { showExportSecretDialog = false },
                title = "Confirm Recovery Code",
                confirmText = "Begin Export",
                onConfirm = {
                    if (exportRecoveryCode.replace("-", "").length == 26) {
                        showExportSecretDialog = false
                        startExport(exportRecoveryCode)
                    } else {
                        Toast.makeText(context, "Recovery code must be exactly 26 characters", Toast.LENGTH_SHORT).show()
                    }
                },
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Enter your 26-character Recovery Code to wrap the backup key. This code will be required to unlock this backup on any other phone.",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.TextMuted
                        )
                        SuyaTextField(
                            value = exportRecoveryCode,
                            onValueChange = { exportRecoveryCode = it.uppercase() },
                            label = "Recovery Code",
                            placeholder = "e.g. 7K9P-4X2B-W8MN-..."
                        )
                    }
                }
            )
        }

        // Restore Flow Dialog
        if (showRestoreSecretDialog) {
            SuyaDialog(
                onDismissRequest = { showRestoreSecretDialog = false },
                title = if (!credentialStep) "Verify Backup" else "Set Device Credential",
                confirmText = if (credentialStep && (newCredentialType == 0 && pinStage == 1 && confirmPin.length == 6)) "Restore Now" else if (!credentialStep) "Verify" else null,
                onConfirm = if (!credentialStep) ({
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
                        if (!credentialStep) {
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
                                placeholder = "XXXX-XXXX-XXXX-..."
                            )
                            restoreError?.let {
                                Text(it, color = SuyaColors.Negative, fontSize = 13.sp, fontFamily = SoraFontFamily)
                            }
                        } else {
                            restoreSummary?.let { sum ->
                                Text(
                                    "Found: ${sum.mediaCount} media items, ${sum.folderCount} folders (${sum.totalPlaintextSize / (1024 * 1024)} MB).",
                                    fontFamily = SoraFontFamily,
                                    fontSize = 13.sp,
                                    color = SuyaColors.Positive,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                "Choose the credential type for this device:",
                                fontFamily = SoraFontFamily,
                                fontSize = 13.sp,
                                color = SuyaColors.White
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = newCredentialType == 0,
                                    onClick = { newCredentialType = 0; pinStage = 0; newPin = ""; confirmPin = ""; credentialError = null },
                                    colors = RadioButtonDefaults.colors(selectedColor = SuyaColors.Accent)
                                )
                                Text("6-Digit PIN", fontFamily = SoraFontFamily, color = SuyaColors.White, fontSize = 14.sp)
                                Spacer(Modifier.width(16.dp))
                                RadioButton(
                                    selected = newCredentialType == 1,
                                    onClick = { newCredentialType = 1; patternFirst = null; credentialError = null },
                                    colors = RadioButtonDefaults.colors(selectedColor = SuyaColors.Accent)
                                )
                                Text("Pattern", fontFamily = SoraFontFamily, color = SuyaColors.White, fontSize = 14.sp)
                            }

                            if (newCredentialType == 0) {
                                Text(
                                    if (pinStage == 0) "Enter new 6-digit PIN:" else "Confirm 6-digit PIN:",
                                    fontFamily = SoraFontFamily,
                                    fontSize = 14.sp,
                                    color = SuyaColors.White
                                )
                                Spacer(Modifier.height(8.dp))
                                PinDots(
                                    pinLength = 6,
                                    enteredCount = if (pinStage == 0) newPin.length else confirmPin.length
                                )
                                Spacer(Modifier.height(8.dp))
                                SecurePinPad(
                                    onDigitClick = { digit ->
                                        if (pinStage == 0) {
                                            if (newPin.length < 6) {
                                                newPin += digit
                                                if (newPin.length == 6) {
                                                    pinStage = 1
                                                    credentialError = null
                                                }
                                            }
                                        } else {
                                            if (confirmPin.length < 6) {
                                                confirmPin += digit
                                                if (confirmPin.length == 6) {
                                                    if (newPin == confirmPin) {
                                                        startRestore(confirmPin.toCharArray())
                                                    } else {
                                                        credentialError = "PINs do not match. Try again."
                                                        pinStage = 0
                                                        newPin = ""
                                                        confirmPin = ""
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onBackspaceClick = {
                                        if (pinStage == 0) {
                                            if (newPin.isNotEmpty()) newPin = newPin.dropLast(1)
                                        } else {
                                            if (confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1)
                                        }
                                    }
                                )
                            } else {
                                Text(
                                    if (patternFirst == null) "Draw new unlock pattern (connect >= 4 dots):" else "Confirm pattern:",
                                    fontFamily = SoraFontFamily,
                                    fontSize = 13.sp,
                                    color = SuyaColors.White
                                )
                                PatternLockPad(
                                    onPatternComplete = { nodes ->
                                        val chars = runCatching { PatternCredential.canonicalChars(nodes) }.getOrNull()
                                        if (chars == null) {
                                            credentialError = "Connect at least 4 dots"
                                            patternErrorTrigger++
                                        } else if (patternFirst == null) {
                                            patternFirst = chars
                                            credentialError = null
                                        } else {
                                            if (chars.contentEquals(patternFirst!!)) {
                                                startRestore(chars)
                                            } else {
                                                credentialError = "Patterns did not match. Draw pattern again."
                                                patternFirst = null
                                                patternErrorTrigger++
                                            }
                                        }
                                    },
                                    errorTrigger = patternErrorTrigger,
                                    enabled = true,
                                    modifier = Modifier.size(240.dp).align(Alignment.CenterHorizontally)
                                )
                            }

                            credentialError?.let {
                                Text(it, color = SuyaColors.Negative, fontSize = 13.sp, fontFamily = SoraFontFamily)
                            }
                        }
                    }
                }
            )
        }

        // Active Progress Overlay (Export or Restore)
        if (isExporting || isRestoring) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(SuyaColors.Background.copy(alpha = 0.85f))
                    .padding(24.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SuyaColors.Surface,
                    border = BorderStroke(1.dp, SuyaColors.Line),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator(
                            color = SuyaColors.Accent,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = if (isExporting) "Exporting Vault..." else "Restoring Vault...",
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            color = SuyaColors.White
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (isExporting) exportProgressText else restoreProgressText,
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { if (isExporting) exportProgressFraction else restoreProgressFraction },
                            color = SuyaColors.Accent,
                            trackColor = SuyaColors.Fill06,
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                    }
                }
            }
        }
    }
}
