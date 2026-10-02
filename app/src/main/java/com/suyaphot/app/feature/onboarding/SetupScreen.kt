package com.suyaphot.app.feature.onboarding

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.suyaphot.app.R
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.PinDots
import com.suyaphot.app.ui.components.PatternLockPad
import com.suyaphot.app.ui.components.SecurePinPad
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaTextField
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import com.suyaphot.app.feature.settings.BackupRestoreScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class SetupStep {
    WELCOME,
    CHOOSE_CREDENTIAL,
    ENTER_PIN,
    CONFIRM_PIN,
    ENTER_PATTERN,
    CONFIRM_PATTERN,
    RECOVERY_KIT,
    CONFIRM_RECOVERY,
    COMPLETE
}

@Composable
fun SetupScreen(
    container: AppContainer,
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentStep by rememberSaveable { mutableStateOf(SetupStep.WELCOME) }

    var initialPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var initialPattern by remember { mutableStateOf(intArrayOf()) }
    var credentialTypeCode by remember { mutableIntStateOf(0) }
    var pinErrorMessage by remember { mutableStateOf<String?>(null) }
    var shakeTrigger by remember { mutableIntStateOf(0) }

    var generatedRecoveryCode by remember { mutableStateOf("") }
    var group1Input by remember { mutableStateOf("") }
    var group2Input by remember { mutableStateOf("") }
    var recoveryErrorMessage by remember { mutableStateOf<String?>(null) }

    var createdVaultId by rememberSaveable { mutableStateOf<String?>(null) }
    var showRestoreScreen by remember { mutableStateOf(false) }

    val clipboardManager = LocalClipboardManager.current
    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        val content = """
                            ========================================
                            SUYA PHOT - VAULT RECOVERY KIT
                            ========================================

                            Recovery Code:
                            $generatedRecoveryCode

                            IMPORTANT:
                            Keep this code safe and confidential.
                            If you forget your PIN or pattern, this is
                            the ONLY way to recover your vault photos.
                            ========================================
                        """.trimIndent()
                        os.write(content.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Recovery kit saved successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save recovery kit: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val activity = context as? Activity
    DisposableEffect(currentStep, activity) {
        if (currentStep == SetupStep.RECOVERY_KIT) {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            initialPin = ""
            confirmPin = ""
            initialPattern.fill(-1)
            generatedRecoveryCode = ""
            group1Input = ""
            group2Input = ""
        }
    }

    val mainActivity = context as? com.suyaphot.app.app.MainActivity
    DisposableEffect(mainActivity, currentStep, initialPin, confirmPin) {
        if (currentStep == SetupStep.ENTER_PIN || currentStep == SetupStep.CONFIRM_PIN) {
            mainActivity?.onHardwareKeyEventListener = { keyEvent ->
                if (keyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    val unicode = keyEvent.unicodeChar
                    if (unicode in '0'.code..'9'.code) {
                        val digit = unicode.toChar().toString()
                        if (currentStep == SetupStep.ENTER_PIN) {
                            if (initialPin.length < 6) {
                                initialPin += digit
                                if (initialPin.length == 6) {
                                    currentStep = SetupStep.CONFIRM_PIN
                                }
                            }
                        } else if (currentStep == SetupStep.CONFIRM_PIN) {
                            if (confirmPin.length < 6) {
                                confirmPin += digit
                                if (confirmPin.length == 6) {
                                    if (confirmPin == initialPin) {
                                        generatedRecoveryCode = container.keyManager.generateRecoverySecret()
                                        currentStep = SetupStep.RECOVERY_KIT
                                    } else {
                                        pinErrorMessage = "PINs do not match. Try again."
                                        shakeTrigger++
                                        confirmPin = ""
                                    }
                                }
                            }
                        }
                        true
                    } else if (keyEvent.keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                        if (currentStep == SetupStep.ENTER_PIN) {
                            if (initialPin.isNotEmpty()) {
                                initialPin = initialPin.dropLast(1)
                            }
                        } else if (currentStep == SetupStep.CONFIRM_PIN) {
                            if (confirmPin.isNotEmpty()) {
                                confirmPin = confirmPin.dropLast(1)
                            }
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        } else {
            mainActivity?.onHardwareKeyEventListener = null
        }
        onDispose {
            mainActivity?.onHardwareKeyEventListener = null
        }
    }

    if (showRestoreScreen) {
        BackupRestoreScreen(
            container = container,
            mode = com.suyaphot.app.feature.settings.BackupRestoreMode.SETUP_RESTORE_ONLY,
            onBack = {
                showRestoreScreen = false
                scope.launch {
                    val vaults = withContext(Dispatchers.IO) { container.database.vaultDao().getAllVaults() }
                    if (vaults.isNotEmpty()) {
                        onSetupComplete()
                    }
                }
            }
        )
        return
    }

    // Check biometric support
    val canEnrollBiometrics = remember {
        val bm = BiometricManager.from(context)
        bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(18.dp)
    ) {
        AnimatedContent(targetState = currentStep, label = "setup_step") { step ->
            when (step) {
                SetupStep.WELCOME -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_suya_logo),
                            contentDescription = "Suya Phot Logo",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(28.dp))
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Suya Phot",
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = SuyaColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "A private, encrypted photo and video gallery on your device.",
                            fontFamily = SoraFontFamily,
                            fontSize = 14.sp,
                            color = SuyaColors.TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(modifier = Modifier.height(40.dp))
                        SuyaButton(
                            text = "Create Vault",
                            onClick = { currentStep = SetupStep.CHOOSE_CREDENTIAL },
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .testTag("welcome_create_vault_btn")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SuyaButton(
                            text = "Restore from Backup",
                            onClick = { showRestoreScreen = true },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .testTag("welcome_restore_backup_btn")
                        )
                    }
                }

                SetupStep.CHOOSE_CREDENTIAL -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("credential_choice_screen")
                    ) {
                        Text("Choose your vault lock", fontFamily = SoraFontFamily, fontSize = 22.sp, color = SuyaColors.White)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("A PIN is easier to use with accessibility services. A pattern needs at least four dots.", fontFamily = SoraFontFamily, fontSize = 13.sp, color = SuyaColors.TextMuted, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(28.dp))
                        SuyaButton(
                            "Use 6-digit PIN",
                            onClick = { credentialTypeCode = 0; currentStep = SetupStep.ENTER_PIN },
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .testTag("credential_pin_option")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SuyaButton(
                            "Use Pattern",
                            onClick = { credentialTypeCode = 1; currentStep = SetupStep.ENTER_PATTERN },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .testTag("credential_pattern_option")
                        )
                    }
                }

                SetupStep.ENTER_PIN -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Spacer(modifier = Modifier.height(40.dp))
                        Text(
                            text = "Create Vault PIN",
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 22.sp,
                            color = SuyaColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Choose a numeric PIN (6 digits)",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.TextMuted
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        PinDots(
                            pinLength = 6,
                            enteredCount = initialPin.length,
                            shakeTrigger = shakeTrigger
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        SecurePinPad(
                            onDigitClick = { digit ->
                                if (initialPin.length < 6) {
                                    initialPin += digit
                                    if (initialPin.length == 6) {
                                        currentStep = SetupStep.CONFIRM_PIN
                                    }
                                }
                            },
                            onBackspaceClick = {
                                if (initialPin.isNotEmpty()) {
                                    initialPin = initialPin.dropLast(1)
                                }
                            }
                        )
                    }
                }

                SetupStep.CONFIRM_PIN -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Spacer(modifier = Modifier.height(40.dp))
                        Text(
                            text = "Confirm Vault PIN",
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 22.sp,
                            color = SuyaColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = pinErrorMessage ?: "Re-enter your PIN to verify",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = if (pinErrorMessage != null) SuyaColors.Negative else SuyaColors.TextMuted
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        PinDots(
                            pinLength = 6,
                            enteredCount = confirmPin.length,
                            shakeTrigger = shakeTrigger
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        SecurePinPad(
                            onDigitClick = { digit ->
                                if (confirmPin.length < 6) {
                                    confirmPin += digit
                                    if (confirmPin.length == 6) {
                                        if (confirmPin == initialPin) {
                                            generatedRecoveryCode = container.keyManager.generateRecoverySecret()
                                            currentStep = SetupStep.RECOVERY_KIT
                                        } else {
                                            pinErrorMessage = "PINs do not match. Try again."
                                            shakeTrigger++
                                            confirmPin = ""
                                        }
                                    }
                                }
                            },
                            onBackspaceClick = {
                                if (confirmPin.isNotEmpty()) {
                                    confirmPin = confirmPin.dropLast(1)
                                }
                            }
                        )
                    }
                }

                SetupStep.ENTER_PATTERN, SetupStep.CONFIRM_PATTERN -> {
                    val confirming = step == SetupStep.CONFIRM_PATTERN
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(40.dp))
                        Text(
                            if (confirming) "Confirm Vault Pattern" else "Create Vault Pattern",
                            fontFamily = SoraFontFamily, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = SuyaColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            pinErrorMessage ?: if (confirming) "Draw the same pattern again" else "Connect at least four dots; six or more is stronger",
                            fontFamily = SoraFontFamily, fontSize = 13.sp,
                            color = if (pinErrorMessage != null) SuyaColors.Negative else SuyaColors.TextMuted
                        )
                        Spacer(modifier = Modifier.height(36.dp))
                        PatternLockPad(
                            onPatternComplete = { raw ->
                                val pattern = runCatching { PatternCredential.normalize(raw) }.getOrNull()
                                if (pattern == null) {
                                    pinErrorMessage = "Connect at least four dots"
                                    shakeTrigger++
                                } else if (!confirming) {
                                    initialPattern = pattern
                                    pinErrorMessage = null
                                    currentStep = SetupStep.CONFIRM_PATTERN
                                } else if (pattern.contentEquals(initialPattern)) {
                                    generatedRecoveryCode = container.keyManager.generateRecoverySecret()
                                    currentStep = SetupStep.RECOVERY_KIT
                                } else {
                                    pinErrorMessage = "Patterns do not match. Try again."
                                    shakeTrigger++
                                }
                            },
                            errorTrigger = shakeTrigger,
                            enabled = true,
                            modifier = Modifier.fillMaxWidth(0.85f)
                        )
                    }
                }

                SetupStep.RECOVERY_KIT -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Security",
                            tint = SuyaColors.Accent,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Your Recovery Code",
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 22.sp,
                            color = SuyaColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Write down this 128-bit recovery code. If you ever forget your PIN, this is the ONLY way to recover your vault.",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.TextMuted,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = SuyaColors.Fill06,
                            border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(20.dp)
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = generatedRecoveryCode,
                                        fontFamily = SoraFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp,
                                        letterSpacing = 1.sp,
                                        color = SuyaColors.White,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(generatedRecoveryCode))
                                    Toast.makeText(context, "Recovery code copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, SuyaColors.StrokeMid),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = SuyaColors.White),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = SuyaColors.Accent)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy", fontFamily = SoraFontFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }

                            OutlinedButton(
                                onClick = {
                                    createDocLauncher.launch("suya-phot-recovery-kit.txt")
                                },
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, SuyaColors.StrokeMid),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = SuyaColors.White),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", modifier = Modifier.size(16.dp), tint = SuyaColors.Accent)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download", fontFamily = SoraFontFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }

                            OutlinedButton(
                                onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "Suya Phot Vault Recovery Kit")
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "Suya Phot Vault Recovery Kit\n\nRecovery Code: $generatedRecoveryCode\n\nKeep this code safe and confidential."
                                        )
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Save Recovery Code"))
                                },
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, SuyaColors.StrokeMid),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = SuyaColors.White),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp), tint = SuyaColors.Accent)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share", fontFamily = SoraFontFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Tip: You can copy, download, share, or screenshot this code to store it safely.",
                            fontFamily = SoraFontFamily,
                            fontSize = 12.sp,
                            color = SuyaColors.TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        Spacer(modifier = Modifier.height(28.dp))
                        SuyaButton(
                            text = "I Have Saved This Code",
                            onClick = {
                                currentStep = SetupStep.CONFIRM_RECOVERY
                            },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        )
                    }
                }

                SetupStep.CONFIRM_RECOVERY -> {
                    val groups = generatedRecoveryCode.split("-")
                    val expectedGroup2 = groups.getOrNull(1) ?: ""
                    val expectedGroup5 = groups.getOrNull(4) ?: ""

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = "Confirm Recovery Code",
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 22.sp,
                            color = SuyaColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "To ensure you wrote down your code accurately, please enter Group 2 and Group 5 below.",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.TextMuted,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        SuyaTextField(
                            value = group1Input,
                            onValueChange = { if (it.length <= 4) group1Input = it.uppercase() },
                            placeholder = "4 characters",
                            label = "Group 2"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SuyaTextField(
                            value = group2Input,
                            onValueChange = { if (it.length <= 4) group2Input = it.uppercase() },
                            placeholder = "4 characters",
                            label = "Group 5"
                        )

                        if (recoveryErrorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = recoveryErrorMessage!!,
                                fontFamily = SoraFontFamily,
                                fontSize = 12.sp,
                                color = SuyaColors.Negative
                            )
                        }

                        Spacer(modifier = Modifier.height(28.dp))
                        SuyaButton(
                            text = "Verify & Create Vault",
                            onClick = {
                                if (group1Input != expectedGroup2 || group2Input != expectedGroup5) {
                                    recoveryErrorMessage = "Entered groups do not match. Check your notes."
                                    return@SuyaButton
                                }

                                scope.launch {
                                    val masterKey = container.keyManager.generateMasterKey()
                                    val pinChars = if (credentialTypeCode == 1) PatternCredential.canonicalChars(initialPattern) else initialPin.toCharArray()
                                    try {
                                        val pinEnvelope = container.keyManager.createPinEnvelope(masterKey, pinChars)
                                        val normRecovery = container.keyManager.normalizeRecoverySecret(generatedRecoveryCode)
                                        val recoveryEnvelope = container.keyManager.createRecoveryEnvelope(masterKey, normRecovery)

                                        val vaultId = UUID.randomUUID().toString()
                                        createdVaultId = vaultId
                                        val vaultEntity = VaultEntity(
                                            id = vaultId,
                                            kindCode = VaultKind.REAL.code,
                                            createdAt = System.currentTimeMillis(),
                                            schemaVersion = 1,
                                            pinEnvelope = pinEnvelope.serialize(),
                                            recoveryEnvelope = recoveryEnvelope.serialize(),
                                            biometricEnvelope = null,
                                            biometricIv = null,
                                            credentialTypeCode = credentialTypeCode
                                        )

                                        withContext(Dispatchers.IO) {
                                            container.database.vaultDao().insert(vaultEntity)
                                        }

                                        // authenticateWithPin takes ownership of and clears this CharArray.
                                        if (credentialTypeCode == 1) {
                                            container.pinAuthenticator.authenticateWithPattern(initialPattern)
                                        } else {
                                            container.pinAuthenticator.authenticateWithPin(pinChars)
                                        }
                                        currentStep = SetupStep.COMPLETE
                                    } finally {
                                        masterKey.fill(0)
                                        pinChars.fill('\u0000')
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        )
                    }
                }

                SetupStep.COMPLETE -> {
                    val activity = context as? FragmentActivity

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = SuyaColors.Positive,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Vault Secured",
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 24.sp,
                            color = SuyaColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Your cryptographic vault is now initialized and ready to protect your media.",
                            fontFamily = SoraFontFamily,
                            fontSize = 14.sp,
                            color = SuyaColors.TextMuted,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))

                        if (canEnrollBiometrics && activity != null && createdVaultId != null) {
                            SuyaButton(
                                text = "Enable Fingerprint Unlock",
                                onClick = {
                                    val vaultId = createdVaultId!!
                                    try {
                                        val encryptCipher = container.keyManager.createBiometricEncryptCipher(vaultId)
                                        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                            .setTitle("Enable Biometric Unlock")
                                            .setSubtitle("Confirm your fingerprint to enable biometric unlock")
                                            .setNegativeButtonText("Skip")
                                            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                                            .build()

                                        val biometricPrompt = BiometricPrompt(
                                            activity,
                                            ContextCompat.getMainExecutor(activity),
                                            object : BiometricPrompt.AuthenticationCallback() {
                                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                                    val authCipher = result.cryptoObject?.cipher ?: return
                                                    val session = container.sessionManager.sessionState.value
                                                    if (session is com.suyaphot.app.domain.auth.VaultSession.Unlocked) {
                                                        session.masterKeyHandle.useBytes { masterKey ->
                                                            val envelope = authCipher.doFinal(masterKey)
                                                            val iv = authCipher.iv
                                                            scope.launch(Dispatchers.IO) {
                                                                container.database.vaultDao().updateBiometricEnvelope(vaultId, envelope, iv)
                                                                withContext(Dispatchers.Main) {
                                                                    onSetupComplete()
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        onSetupComplete()
                                                    }
                                                }

                                                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                                    onSetupComplete()
                                                }
                                            }
                                        )

                                        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(encryptCipher))
                                    } catch (e: Exception) {
                                        onSetupComplete()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SuyaButton(
                                text = "Skip for Now",
                                onClick = onSetupComplete,
                                variant = ButtonVariant.Ghost,
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )
                        } else {
                            SuyaButton(
                                text = "Enter Vault",
                                onClick = onSetupComplete,
                                modifier = Modifier.fillMaxWidth(0.9f)
                            )
                        }
                    }
                }
            }
        }
    }
}
