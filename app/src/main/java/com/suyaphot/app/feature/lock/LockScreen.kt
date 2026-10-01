package com.suyaphot.app.feature.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.suyaphot.app.R
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.AuthResult
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.PinDots
import com.suyaphot.app.ui.components.PatternLockPad
import com.suyaphot.app.ui.components.SecurePinPad
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaTextField
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun LockScreen(
    container: AppContainer,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var enteredPin by remember { mutableStateOf("") }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showPatternInput by remember { mutableStateOf(false) }

    val lockoutTimestamp by container.preferences.lockoutUntilTimestamp.collectAsState(initial = 0L)
    var lockoutSecondsLeft by remember { mutableIntStateOf(0) }

    var showForgotPinDialog by remember { mutableStateOf(false) }
    var recoveryCodeInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var confirmNewPinInput by remember { mutableStateOf("") }
    var recoveryCredentialType by remember { mutableIntStateOf(0) }
    var firstRecoveryPattern by remember { mutableStateOf<IntArray?>(null) }
    var recoveryPatternErrorTrigger by remember { mutableIntStateOf(0) }
    var recoveryError by remember { mutableStateOf<String?>(null) }

    // Check if biometric is enrolled for real vault
    val realVault by container.database.vaultDao()
        .observeVaultByKind(VaultKind.REAL.code)
        .collectAsState(initial = null)
    val secondaryVault by container.database.vaultDao()
        .observeVaultByKind(VaultKind.SECONDARY.code)
        .collectAsState(initial = null)
    val patternAvailable = realVault?.credentialTypeCode == 1 || secondaryVault?.credentialTypeCode == 1
    val pinAvailable = realVault?.credentialTypeCode == 0 || secondaryVault?.credentialTypeCode == 0
    LaunchedEffect(realVault?.credentialTypeCode, secondaryVault?.credentialTypeCode) {
        showPatternInput = realVault?.credentialTypeCode == 1
    }
    val realVaultWithBiometric = realVault?.takeIf {
        it.biometricEnvelope != null && it.biometricIv != null
    }

    val isBiometricEnrolled = realVaultWithBiometric != null

    // Lockout countdown timer
    LaunchedEffect(lockoutTimestamp) {
        while (true) {
            val now = System.currentTimeMillis()
            if (now < lockoutTimestamp) {
                lockoutSecondsLeft = ((lockoutTimestamp - now) / 1000).toInt().coerceAtLeast(1)
            } else {
                lockoutSecondsLeft = 0
            }
            delay(1000L)
        }
    }

    fun launchBiometricPrompt() {
        val vault = realVaultWithBiometric ?: return
        val iv = vault.biometricIv ?: return
        val activity = context as? FragmentActivity ?: return

        try {
            val decryptCipher = container.keyManager.createBiometricDecryptCipher(vault.id, iv)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Suya Phot")
                .setSubtitle("Use your biometric to unlock your secure vault")
                .setNegativeButtonText(if (showPatternInput) "Use Pattern" else "Use PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()

            val biometricPrompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authCipher = result.cryptoObject?.cipher ?: return
                        scope.launch {
                            val authResult = container.pinAuthenticator.authenticateWithBiometric(authCipher)
                            if (authResult is AuthResult.Success) {
                                onUnlocked()
                            } else {
                                errorMessage = "Biometric authentication failed"
                            }
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            errorMessage = errString.toString()
                        }
                    }

                    override fun onAuthenticationFailed() {
                        errorMessage = "Biometric not recognized"
                    }
                }
            )

            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(decryptCipher))
        } catch (e: Exception) {
            errorMessage = "Biometric unlock unavailable"
        }
    }

    // Launch biometric on screen entry if enabled and available
    LaunchedEffect(isBiometricEnrolled) {
        if (isBiometricEnrolled && lockoutSecondsLeft <= 0) {
            val autoPrompt = container.preferences.biometricOnLaunch.first()
            if (autoPrompt) {
                launchBiometricPrompt()
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun handleDigitInput(digit: Char) {
        if (lockoutSecondsLeft <= 0 && enteredPin.length < 6) {
            enteredPin += digit
            errorMessage = null
            if (enteredPin.length == 6) {
                scope.launch {
                    val pinChars = enteredPin.toCharArray()
                    val result = container.pinAuthenticator.authenticateWithPin(pinChars)
                    when (result) {
                        is AuthResult.Success -> {
                            onUnlocked()
                        }
                        is AuthResult.IncorrectPin -> {
                            enteredPin = ""
                            shakeTrigger++
                            errorMessage = "Incorrect PIN"

                            // Trigger intruder selfie check
                            val triggerThreshold = container.preferences.intruderTriggerCount.first()
                            if (container.preferences.intruderSelfieEnabled.first() && result.attempts == triggerThreshold) {
                                container.intruderCaptureManager.captureIntruderPhoto(
                                    lifecycleOwner = lifecycleOwner,
                                    failureReason = "Failed PIN attempt #${result.attempts}"
                                )
                            }
                        }
                        is AuthResult.LockedOut -> {
                            enteredPin = ""
                            shakeTrigger++
                            errorMessage = "Too many failed attempts"
                        }
                        is AuthResult.Error -> {
                            enteredPin = ""
                            errorMessage = result.message
                        }
                    }
                }
            }
        }
    }

    fun handleBackspace() {
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
            errorMessage = null
        }
    }

    val mainActivity = context as? com.suyaphot.app.app.MainActivity
    androidx.compose.runtime.DisposableEffect(mainActivity, enteredPin, lockoutSecondsLeft) {
        mainActivity?.onHardwareKeyEventListener = { keyEvent ->
            if (keyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                val unicode = keyEvent.unicodeChar
                if (unicode in '0'.code..'9'.code) {
                    handleDigitInput(unicode.toChar())
                    true
                } else if (keyEvent.keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                    handleBackspace()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        onDispose {
            mainActivity?.onHardwareKeyEventListener = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(18.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val char = keyEvent.utf16CodePoint.toChar()
                    if (char in '0'..'9') {
                        handleDigitInput(char)
                        true
                    } else if (keyEvent.key == Key.Backspace) {
                        handleBackspace()
                        true
                    } else false
                } else false
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(36.dp))

            // Logo and brand header
            Image(
                painter = painterResource(id = R.drawable.ic_suya_logo),
                contentDescription = "Suya Phot",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Suya Phot",
                fontFamily = SoraFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp,
                color = SuyaColors.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (lockoutSecondsLeft > 0) {
                Text(
                    text = "Try again in $lockoutSecondsLeft seconds",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.Negative
                )
            } else if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.Negative
                )
            } else {
                Text(
                    text = if (showPatternInput) "Draw your pattern to unlock" else "Enter your PIN to unlock",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (!showPatternInput) PinDots(
                pinLength = 6,
                enteredCount = enteredPin.length,
                shakeTrigger = shakeTrigger
            )

            Spacer(modifier = Modifier.weight(1f))

            if (showPatternInput) {
                PatternLockPad(
                    onPatternComplete = { nodes ->
                        scope.launch {
                            when (val result = container.pinAuthenticator.authenticateWithPattern(nodes)) {
                                is AuthResult.Success -> onUnlocked()
                                is AuthResult.IncorrectPin -> {
                                    shakeTrigger++
                                    errorMessage = "Incorrect pattern"
                                    val threshold = container.preferences.intruderTriggerCount.first()
                                    if (container.preferences.intruderSelfieEnabled.first() && result.attempts == threshold) {
                                        container.intruderCaptureManager.captureIntruderPhoto(
                                            lifecycleOwner = lifecycleOwner,
                                            failureReason = "Failed pattern attempt #${result.attempts}"
                                        )
                                    }
                                }
                                is AuthResult.LockedOut -> { shakeTrigger++; errorMessage = "Too many failed attempts" }
                                is AuthResult.Error -> { shakeTrigger++; errorMessage = result.message }
                            }
                        }
                    },
                    errorTrigger = shakeTrigger,
                    enabled = lockoutSecondsLeft <= 0,
                    modifier = Modifier.fillMaxWidth(0.85f)
                )
            } else SecurePinPad(
                onDigitClick = ::handleDigitInput,
                onBackspaceClick = ::handleBackspace,
                showBiometric = isBiometricEnrolled,
                onBiometricClick = {
                    if (lockoutSecondsLeft <= 0) {
                        launchBiometricPrompt()
                    }
                }
            )

            if (patternAvailable && pinAvailable) {
                TextButton(onClick = { showPatternInput = !showPatternInput; enteredPin = ""; errorMessage = null }) {
                    Text(if (showPatternInput) "Use PIN instead" else "Use Pattern instead", color = SuyaColors.Accent)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = { showForgotPinDialog = true }
            ) {
                Text(
                    text = "Forgot vault credential?",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    fun submitRecoveryCredential(chars: CharArray, typeCode: Int) {
        if (recoveryCodeInput.trim().isEmpty()) {
            chars.fill('\u0000')
            recoveryError = "Please enter your recovery code"
            return
        }
        scope.launch {
            val success = container.pinAuthenticator.recoverWithCode(
                recoveryCodeInput = recoveryCodeInput.trim(),
                newPinChars = chars,
                newTypeCode = typeCode
            )
            if (success) {
                showForgotPinDialog = false
                onUnlocked()
            } else recoveryError = "Invalid recovery code or credential"
        }
    }

    if (showForgotPinDialog) {
        SuyaDialog(
            onDismissRequest = {
                showForgotPinDialog = false
                recoveryCodeInput = ""
                newPinInput = ""
                confirmNewPinInput = ""
                firstRecoveryPattern = null
                recoveryError = null
            },
            title = "Recovery Kit",
            confirmText = if (recoveryCredentialType == 0) "Reset PIN" else null,
            onConfirm = if (recoveryCredentialType == 0) ({
                val digits = newPinInput.filter(Char::isDigit)
                if (digits.length != 6) {
                    recoveryError = "New PIN must be exactly 6 digits"
                } else if (newPinInput != confirmNewPinInput) {
                    recoveryError = "PIN confirmation does not match"
                } else submitRecoveryCredential(digits.toCharArray(), 0)
            }) else null
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter your 26-character recovery code and choose a new vault credential.",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuyaButton("New PIN", onClick = { recoveryCredentialType = 0; firstRecoveryPattern = null }, variant = ButtonVariant.Secondary)
                    SuyaButton("New Pattern", onClick = { recoveryCredentialType = 1; firstRecoveryPattern = null }, variant = ButtonVariant.Secondary)
                }
                SuyaTextField(
                    value = recoveryCodeInput,
                    onValueChange = { recoveryCodeInput = it.uppercase() },
                    placeholder = "XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XX",
                    label = "Recovery Code"
                )
                if (recoveryCredentialType == 0) {
                    SuyaTextField(
                        value = newPinInput,
                        onValueChange = { input ->
                            val digits = input.filter(Char::isDigit)
                            if (digits.length <= 6) newPinInput = digits
                        },
                        placeholder = "Enter new 6-digit PIN",
                        label = "New PIN",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    SuyaTextField(
                        value = confirmNewPinInput,
                        onValueChange = { input ->
                            val digits = input.filter(Char::isDigit)
                            if (digits.length <= 6) confirmNewPinInput = digits
                        },
                        placeholder = "Confirm new 6-digit PIN",
                        label = "Confirm PIN",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                } else {
                    Text(if (firstRecoveryPattern == null) "Draw a new pattern" else "Draw it again to confirm", color = SuyaColors.TextMuted)
                    PatternLockPad(
                        onPatternComplete = { raw ->
                            val normalized = runCatching { PatternCredential.normalize(raw) }.getOrNull()
                            if (normalized == null) { recoveryError = "Connect at least four dots"; recoveryPatternErrorTrigger++ }
                            else if (firstRecoveryPattern == null) { firstRecoveryPattern = normalized; recoveryError = null }
                            else if (!normalized.contentEquals(firstRecoveryPattern)) {
                                firstRecoveryPattern = null
                                recoveryError = "Patterns do not match"
                                recoveryPatternErrorTrigger++
                            } else submitRecoveryCredential(PatternCredential.canonicalChars(normalized), 1)
                        },
                        errorTrigger = recoveryPatternErrorTrigger,
                        enabled = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (recoveryError != null) {
                    Text(
                        text = recoveryError!!,
                        fontFamily = SoraFontFamily,
                        fontSize = 12.sp,
                        color = SuyaColors.Negative
                    )
                }
            }
        }
    }
}
