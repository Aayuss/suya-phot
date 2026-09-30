package com.suyaphot.app.feature.security

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.media.DerivativeCryptoVerifier
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.domain.auth.ChangeSecondaryPinResult
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.domain.backup.VaultBackupSemanticVerifier
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.PatternLockPad
import com.suyaphot.app.ui.components.SuyaTextField
import com.suyaphot.app.ui.components.SuyaTopBar
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun SecurityScreen(
    container: AppContainer,
    onViewIntruderLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.sessionManager.sessionState.collectAsState().value
    val isSecondary = (session as? VaultSession.Unlocked)?.kind == VaultKind.SECONDARY

    val screenshotProtection by container.preferences.screenshotProtection.collectAsState(initial = true)
    val intruderEnabled by container.preferences.intruderSelfieEnabled.collectAsState(initial = false)
    val intruderThreshold by container.preferences.intruderTriggerCount.collectAsState(initial = 3)

    var showSecondaryPinDialog by remember { mutableStateOf(false) }
    var currentSecondaryPinInput by remember { mutableStateOf("") }
    var newSecondaryPinInput by remember { mutableStateOf("") }
    var confirmSecondaryPinInput by remember { mutableStateOf("") }
    var secondaryPinError by remember { mutableStateOf<String?>(null) }
    var showIntruderPermissionDialog by remember { mutableStateOf(false) }
    var showChangeCredentialDialog by remember { mutableStateOf(false) }
    var targetCredentialType by remember { mutableIntStateOf(0) }
    var currentCredentialInput by remember { mutableStateOf("") }
    var currentPatternCredential by remember { mutableStateOf<CharArray?>(null) }
    var newCredentialInput by remember { mutableStateOf("") }
    var confirmCredentialInput by remember { mutableStateOf("") }
    var firstNewPattern by remember { mutableStateOf<IntArray?>(null) }
    var changeCredentialError by remember { mutableStateOf<String?>(null) }
    var changePatternErrorTrigger by remember { mutableIntStateOf(0) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch { container.preferences.setIntruderSelfieEnabled(granted) }
    }

    var isRunningIntegrityCheck by remember { mutableStateOf(false) }
    var integrityStatusMessage by remember { mutableStateOf<String?>(null) }
    var integrityHasFailures by remember { mutableStateOf(false) }

    val realVault by container.database.vaultDao()
        .observeVaultByKind(VaultKind.REAL.code)
        .collectAsState(initial = null)

    val secondaryVault by container.database.vaultDao()
        .observeVaultByKind(VaultKind.SECONDARY.code)
        .collectAsState(initial = null)

    val isBiometricEnrolled = realVault?.biometricEnvelope != null && realVault?.biometricIv != null
    val canEnrollBiometrics = remember {
        val bm = BiometricManager.from(context)
        bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun submitChangedCredential(newChars: CharArray) {
        val oldType = realVault?.credentialTypeCode ?: 0
        val oldChars = if (oldType == 1) currentPatternCredential?.copyOf()
            else currentCredentialInput.toCharArray()
        if (oldChars == null || oldChars.isEmpty()) {
            newChars.fill('\u0000')
            changeCredentialError = "Enter your current credential first"
            return
        }
        scope.launch {
            val changed = container.pinAuthenticator.changeCurrentCredential(oldChars, oldType, newChars, targetCredentialType)
            if (changed) {
                currentPatternCredential?.fill('\u0000')
                currentPatternCredential = null
                currentCredentialInput = ""
                newCredentialInput = ""
                confirmCredentialInput = ""
                firstNewPattern = null
                showChangeCredentialDialog = false
            } else {
                changeCredentialError = "Current credential was incorrect or update failed"
                changePatternErrorTrigger++
            }
        }
    }

    if (isSecondary) {
        // Dedicated safe screen for secondary / decoy vault mode (Section 28)
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SuyaColors.Background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                SuyaTopBar(title = "Security")

                Column(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecurityCard(
                        title = "Vault Encryption",
                        subtitle = "AES-256-GCM authenticated media encryption",
                        icon = Icons.Default.Lock,
                        statusText = "Active",
                        statusPositive = true
                    )

                    SecurityToggleRow(
                        title = "Screenshot Protection",
                        subtitle = "Blocks screenshots and hides preview in Android Recents",
                        icon = Icons.Default.VisibilityOff,
                        checked = screenshotProtection,
                        onCheckedChange = { scope.launch { container.preferences.setScreenshotProtection(it) } }
                    )
                }
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SuyaTopBar(title = "Security")

            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Section 1: Security Diagnostics
                SecurityCard(
                    title = "Vault Encryption",
                    subtitle = "AES-256-GCM authenticated media encryption",
                    icon = Icons.Default.Lock,
                    statusText = "Active",
                    statusPositive = true
                )

                SecurityCard(
                    title = "Recovery Kit",
                    subtitle = "128-bit emergency recovery key envelope",
                    icon = Icons.Default.Key,
                    statusText = if (realVault?.recoveryEnvelope != null) "Configured" else "None",
                    statusPositive = realVault?.recoveryEnvelope != null
                )

                SuyaButton(
                    text = if (realVault?.credentialTypeCode == 1) "Change Pattern" else "Change PIN",
                    onClick = {
                        targetCredentialType = realVault?.credentialTypeCode ?: 0
                        currentCredentialInput = ""
                        currentPatternCredential?.fill('\u0000')
                        currentPatternCredential = null
                        newCredentialInput = ""
                        confirmCredentialInput = ""
                        firstNewPattern = null
                        changeCredentialError = null
                        showChangeCredentialDialog = true
                    },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )

                SuyaButton(
                    text = if (realVault?.credentialTypeCode == 1) "Switch to PIN" else "Switch to Pattern",
                    onClick = {
                        targetCredentialType = 1 - (realVault?.credentialTypeCode ?: 0)
                        currentCredentialInput = ""
                        currentPatternCredential?.fill('\u0000')
                        currentPatternCredential = null
                        newCredentialInput = ""
                        confirmCredentialInput = ""
                        firstNewPattern = null
                        changeCredentialError = null
                        showChangeCredentialDialog = true
                    },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )

                // Biometric Unlock
                if (canEnrollBiometrics) {
                    SecurityToggleRow(
                        title = "Fingerprint Unlock",
                        subtitle = "Biometric unwrap via Android Keystore; hardware protection depends on the device",
                        icon = Icons.Default.Fingerprint,
                        checked = isBiometricEnrolled,
                        onCheckedChange = { enable ->
                            val activity = context as? FragmentActivity ?: return@SecurityToggleRow
                            val real = realVault ?: return@SecurityToggleRow
                            if (enable) {
                                try {
                                    val encryptCipher = container.keyManager.createBiometricEncryptCipher(real.id)
                                    val promptInfo = BiometricPrompt.PromptInfo.Builder()
                                        .setTitle("Enable Biometric Unlock")
                                        .setSubtitle("Confirm fingerprint to link biometric key to vault")
                                        .setNegativeButtonText("Cancel")
                                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                                        .build()

                                    val biometricPrompt = BiometricPrompt(
                                        activity,
                                        ContextCompat.getMainExecutor(activity),
                                        object : BiometricPrompt.AuthenticationCallback() {
                                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                                val authCipher = result.cryptoObject?.cipher ?: return
                                                val currentSession = container.sessionManager.sessionState.value
                                                if (currentSession is VaultSession.Unlocked) {
                                                    currentSession.masterKeyHandle.useBytes { masterKey ->
                                                        val envelope = authCipher.doFinal(masterKey)
                                                        val iv = authCipher.iv
                                                        scope.launch(Dispatchers.IO) {
                                                            container.database.vaultDao().updateBiometricEnvelope(real.id, envelope, iv)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(encryptCipher))
                                } catch (ignored: Exception) {}
                            } else {
                                scope.launch(Dispatchers.IO) {
                                    container.keyManager.deleteBiometricKey(real.id)
                                    container.database.vaultDao().updateBiometricEnvelope(real.id, null, null)
                                }
                            }
                        }
                    )
                }

                // Screenshot & Recents Protection Toggle
                SecurityToggleRow(
                    title = "Screenshot Protection",
                    subtitle = "Blocks screenshots and hides preview in Android Recents",
                    icon = Icons.Default.VisibilityOff,
                    checked = screenshotProtection,
                    onCheckedChange = { scope.launch { container.preferences.setScreenshotProtection(it) } }
                )

                // Intruder Selfie
                SecurityToggleRow(
                    title = "Intruder Selfie",
                    subtitle = "Captures a front-camera photo after $intruderThreshold failed vault credential attempts",
                    icon = Icons.Default.CameraAlt,
                    checked = intruderEnabled,
                    onCheckedChange = { enable ->
                        if (enable) showIntruderPermissionDialog = true
                        else scope.launch { container.preferences.setIntruderSelfieEnabled(false) }
                    }
                )

                if (intruderEnabled) {
                    SuyaButton(
                        text = "View Intruder Logs",
                        onClick = onViewIntruderLogs,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Secondary Access PIN (Decoy vault)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Secondary Access",
                    fontFamily = SoraFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = SuyaColors.White
                )
                Text(
                    text = "Configure a secondary PIN that opens an isolated, independent vault with separate folders and media.",
                    fontFamily = SoraFontFamily,
                    fontSize = 12.sp,
                    color = SuyaColors.TextMuted
                )

                SuyaButton(
                    text = if (secondaryVault != null) "Change Secondary PIN" else "Set Secondary Access PIN",
                    onClick = { showSecondaryPinDialog = true },
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )

                // Vault Integrity Check
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Vault Integrity",
                    fontFamily = SoraFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = SuyaColors.White
                )

                if (isRunningIntegrityCheck) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = SuyaColors.Accent,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Auditing cryptographic integrity...",
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = SuyaColors.TextMuted
                        )
                    }
                } else {
                    SuyaButton(
                        text = "Run Vault Integrity Audit",
                        onClick = {
                            isRunningIntegrityCheck = true
                            scope.launch {
                                val lease = container.sessionManager.acquireOperationKeyLease()
                                if (lease != null) {
                                    val (passed, failed) = try {
                                        withContext(Dispatchers.IO) {
                                            val media = container.database.mediaItemDao()
                                                .getAllForIntegrityCheck(lease.vaultId)
                                            val folders = container.database.folderDao()
                                                .getFoldersForVaultOnce(lease.vaultId)
                                            var passedCount = 0
                                            var failedCount = 0

                                            for (item in media) {
                                                var itemValid = true
                                                try {
                                                    val file = container.vaultFileStore
                                                        .getMediaFile(lease.vaultId, item.id)
                                                    check(file.exists()) { "Missing encrypted media" }

                                                    val verify = container.vaultCrypto
                                                        .verifyAndHash(file, lease.mediaSubkey, item.id)
                                                    val hash = verify.sha256
                                                        .joinToString("") { "%02x".format(it) }
                                                    check(
                                                        verify.plaintextSize == item.plaintextSize &&
                                                            hash.equals(item.sha256Hex, true)
                                                    ) { "Media hash/size mismatch" }

                                                    VaultBackupSemanticVerifier.verifyMediaMetadata(
                                                        item.id,
                                                        item.encryptedMetadata,
                                                        lease.metaSubkey
                                                    )

                                                    if (item.encryptedThumbRelativePath != null) {
                                                        val thumb = container.vaultFileStore
                                                            .getThumbFile(lease.vaultId, item.id)
                                                        check(
                                                            thumb.exists() &&
                                                                DerivativeCryptoVerifier.verifyThumbnailCiphertext(
                                                                    thumb,
                                                                    lease.thumbSubkey,
                                                                    item.id
                                                                )
                                                        ) { "Thumbnail integrity failure" }
                                                    }

                                                    if (item.encryptedPreviewRelativePath != null) {
                                                        val preview = container.vaultFileStore
                                                            .getPreviewFile(lease.vaultId, item.id)
                                                        check(
                                                            preview.exists() &&
                                                                DerivativeCryptoVerifier.verifyPreviewCiphertext(
                                                                    preview,
                                                                    lease.thumbSubkey,
                                                                    item.id
                                                                )
                                                        ) { "Preview integrity failure" }
                                                    }
                                                } catch (_: Exception) {
                                                    itemValid = false
                                                }

                                                if (itemValid) passedCount++ else failedCount++
                                            }

                                            // Folder names are encrypted metadata too. A corrupt name can make
                                            // navigation and backup restore fail even when every media file is sound.
                                            for (folder in folders) {
                                                try {
                                                    VaultBackupSemanticVerifier.verifyFolderName(
                                                        folder.id,
                                                        folder.encryptedName,
                                                        lease.metaSubkey
                                                    )
                                                } catch (_: Exception) {
                                                    failedCount++
                                                }
                                            }

                                            passedCount to failedCount
                                        }
                                    } finally {
                                        lease.close()
                                    }

                                    integrityHasFailures = failed > 0
                                    integrityStatusMessage =
                                        if (failed == 0) {
                                            "Audit finished: $passed media verified, no integrity issues found"
                                        } else {
                                            "Audit finished: $passed media verified, $failed integrity issue(s) found"
                                        }
                                }
                                isRunningIntegrityCheck = false
                            }
                        },
                        variant = ButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth()
                    )
                    integrityStatusMessage?.let {
                        Text(
                            text = it,
                            fontFamily = SoraFontFamily,
                            fontSize = 13.sp,
                            color = if (integrityHasFailures) SuyaColors.Negative else SuyaColors.Positive
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }

    if (showChangeCredentialDialog && !isSecondary) {
        val oldType = realVault?.credentialTypeCode ?: 0
        SuyaDialog(
            onDismissRequest = {
                showChangeCredentialDialog = false
                currentPatternCredential?.fill('\u0000')
                currentPatternCredential = null
                currentCredentialInput = ""
                newCredentialInput = ""
                confirmCredentialInput = ""
                firstNewPattern = null
            },
            title = if (targetCredentialType == oldType) {
                if (oldType == 0) "Change PIN" else "Change Pattern"
            } else if (targetCredentialType == 0) "Switch to PIN" else "Switch to Pattern",
            confirmText = if (targetCredentialType == 0) "Save PIN" else null,
            onConfirm = if (targetCredentialType == 0) ({
                if (newCredentialInput.length != 6 || newCredentialInput != confirmCredentialInput) {
                    changeCredentialError = "Enter and confirm a new 6-digit PIN"
                } else submitChangedCredential(newCredentialInput.toCharArray())
            }) else null,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (oldType == 0) {
                        SuyaTextField(
                            currentCredentialInput,
                            onValueChange = { currentCredentialInput = it.filter(Char::isDigit).take(6); changeCredentialError = null },
                            label = "Current PIN",
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                    } else {
                        Text(if (currentPatternCredential == null) "Draw your current pattern" else "Current pattern captured", color = SuyaColors.TextMuted, fontSize = 13.sp)
                        if (currentPatternCredential == null) {
                            PatternLockPad(
                                onPatternComplete = { raw ->
                                    currentPatternCredential = runCatching { PatternCredential.canonicalChars(raw) }.getOrNull()
                                    if (currentPatternCredential == null) { changeCredentialError = "Connect at least four dots"; changePatternErrorTrigger++ }
                                    else changeCredentialError = null
                                },
                                errorTrigger = changePatternErrorTrigger,
                                enabled = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (targetCredentialType == 1) {
                        Text(if (firstNewPattern == null) "Draw a new pattern" else "Draw the new pattern again", color = SuyaColors.TextMuted, fontSize = 13.sp)
                        PatternLockPad(
                            onPatternComplete = { raw ->
                                val normalized = runCatching { PatternCredential.normalize(raw) }.getOrNull()
                                if (normalized == null) { changeCredentialError = "Connect at least four dots"; changePatternErrorTrigger++ }
                                else if (firstNewPattern == null) { firstNewPattern = normalized; changeCredentialError = null }
                                else if (!normalized.contentEquals(firstNewPattern)) { firstNewPattern = null; changeCredentialError = "Patterns do not match"; changePatternErrorTrigger++ }
                                else submitChangedCredential(PatternCredential.canonicalChars(normalized))
                            },
                            errorTrigger = changePatternErrorTrigger,
                            enabled = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        SuyaTextField(newCredentialInput, onValueChange = { newCredentialInput = it.filter(Char::isDigit).take(6) }, label = "New PIN", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        SuyaTextField(confirmCredentialInput, onValueChange = { confirmCredentialInput = it.filter(Char::isDigit).take(6) }, label = "Confirm PIN", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    }
                    changeCredentialError?.let { Text(it, color = SuyaColors.Negative, fontSize = 12.sp) }
                }
            }
        )
    }

    // Intruder permission dialog
    if (showIntruderPermissionDialog) {
        SuyaDialog(
            onDismissRequest = { showIntruderPermissionDialog = false },
            title = "Intruder photo",
            content = {
                Text(
                    "If enabled, Suya Phot can use the front camera after the configured number of failed PIN attempts. Android may show its normal camera privacy indicator when a photo is captured. Photos stay encrypted on this device.",
                    color = SuyaColors.TextMuted,
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp
                )
            },
            confirmText = "Enable",
            onConfirm = {
                showIntruderPermissionDialog = false
                if (container.intruderCaptureManager.hasCameraPermission()) {
                    scope.launch { container.preferences.setIntruderSelfieEnabled(true) }
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        )
    }

    // Secondary PIN Dialog
    if (showSecondaryPinDialog) {
        SuyaDialog(
            onDismissRequest = {
                showSecondaryPinDialog = false
                currentSecondaryPinInput = ""
                newSecondaryPinInput = ""
                confirmSecondaryPinInput = ""
                secondaryPinError = null
            },
            title = if (secondaryVault != null) "Change Secondary PIN" else "Secondary Access PIN",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Entering this PIN on the lock screen will seamlessly open your secondary vault.",
                        fontFamily = SoraFontFamily,
                        fontSize = 13.sp,
                        color = SuyaColors.TextMuted
                    )
                    if (secondaryVault != null) {
                        SuyaTextField(
                            value = currentSecondaryPinInput,
                            onValueChange = {
                                val digits = it.filter(Char::isDigit)
                                if (digits.length <= 6) currentSecondaryPinInput = digits
                            },
                            placeholder = "Current 6-digit PIN",
                            label = "Current secondary PIN",
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                    }
                    SuyaTextField(
                        value = newSecondaryPinInput,
                        onValueChange = {
                            val digits = it.filter(Char::isDigit)
                            if (digits.length <= 6) newSecondaryPinInput = digits
                        },
                        placeholder = "New 6-digit PIN",
                        label = if (secondaryVault != null) "New secondary PIN" else "Secondary PIN",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    SuyaTextField(
                        value = confirmSecondaryPinInput,
                        onValueChange = {
                            val digits = it.filter(Char::isDigit)
                            if (digits.length <= 6) confirmSecondaryPinInput = digits
                        },
                        placeholder = "Confirm 6-digit PIN",
                        label = "Confirm secondary PIN",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    if (secondaryPinError != null) {
                        Text(
                            text = secondaryPinError!!,
                            fontFamily = SoraFontFamily,
                            fontSize = 12.sp,
                            color = SuyaColors.Negative
                        )
                    }
                }
            },
            confirmText = "Save Secondary PIN",
            onConfirm = {
                if (newSecondaryPinInput.length != 6) {
                    secondaryPinError = "Secondary PIN must be 6 digits"
                    return@SuyaDialog
                }
                if (newSecondaryPinInput != confirmSecondaryPinInput) {
                    secondaryPinError = "Secondary PINs do not match"
                    return@SuyaDialog
                }
                if (secondaryVault != null && currentSecondaryPinInput.length != 6) {
                    secondaryPinError = "Enter the current secondary PIN"
                    return@SuyaDialog
                }

                scope.launch {
                    val newPinChars = newSecondaryPinInput.toCharArray()
                    val matchesReal = container.pinAuthenticator.isSameAsRealPin(newPinChars)
                    if (matchesReal) {
                        secondaryPinError = "Secondary PIN must be different from your main PIN"
                        newPinChars.fill('\u0000')
                        return@launch
                    }

                    val existing = secondaryVault
                    if (existing != null) {
                        when (container.pinAuthenticator.changeSecondaryPin(
                            currentSecondaryPinInput.toCharArray(),
                            newPinChars
                        )) {
                            ChangeSecondaryPinResult.Success -> Unit
                            ChangeSecondaryPinResult.IncorrectCurrentPin -> {
                                secondaryPinError = "Current secondary PIN is incorrect"
                                return@launch
                            }
                            ChangeSecondaryPinResult.SameAsRealPin -> {
                                secondaryPinError = "Secondary PIN must be different from your main PIN"
                                return@launch
                            }
                            ChangeSecondaryPinResult.InvalidNewPin -> {
                                secondaryPinError = "Secondary PIN must be 6 digits"
                                return@launch
                            }
                            is ChangeSecondaryPinResult.Error -> {
                                secondaryPinError = "Could not update the secondary PIN"
                                return@launch
                            }
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            // Create new secondary vault (nullable recovery envelope, Section 32)
                            val secMasterKey = container.keyManager.generateMasterKey()
                            try {
                                val pinEnvelope = container.keyManager.createPinEnvelope(secMasterKey, newPinChars)
                                val secVaultEntity = VaultEntity(
                                    id = UUID.randomUUID().toString(),
                                    kindCode = VaultKind.SECONDARY.code,
                                    createdAt = System.currentTimeMillis(),
                                    schemaVersion = 1,
                                    pinEnvelope = pinEnvelope.serialize(),
                                    recoveryEnvelope = null,
                                    biometricEnvelope = null,
                                    biometricIv = null
                                )
                                container.database.vaultDao().insert(secVaultEntity)
                            } finally {
                                secMasterKey.fill(0)
                                newPinChars.fill('\u0000')
                            }
                        }
                    }

                    showSecondaryPinDialog = false
                    currentSecondaryPinInput = ""
                    newSecondaryPinInput = ""
                    confirmSecondaryPinInput = ""
                }
            }
        )
    }
}

@Composable
private fun SecurityCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    statusText: String,
    statusPositive: Boolean
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SuyaColors.Fill06,
        border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SuyaColors.Fill07,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = SuyaColors.White, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = SoraFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = SuyaColors.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontFamily = SoraFontFamily,
                    fontSize = 12.sp,
                    color = SuyaColors.TextMuted
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (statusPositive) SuyaColors.Positive.copy(alpha = 0.15f) else SuyaColors.Fill07
            ) {
                Text(
                    text = statusText,
                    fontFamily = SoraFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (statusPositive) SuyaColors.Positive else SuyaColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun SecurityToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SuyaColors.Fill06,
        border = androidx.compose.foundation.BorderStroke(1.dp, SuyaColors.Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SuyaColors.Fill07,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = SuyaColors.White, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = SoraFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = SuyaColors.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontFamily = SoraFontFamily,
                    fontSize = 12.sp,
                    color = SuyaColors.TextMuted
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = SuyaColors.White,
                    checkedTrackColor = SuyaColors.Accent,
                    uncheckedThumbColor = SuyaColors.TextMuted,
                    uncheckedTrackColor = SuyaColors.Fill07,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}
