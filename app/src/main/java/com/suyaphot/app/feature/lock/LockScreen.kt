package com.suyaphot.app.feature.lock

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.suyaphot.app.R
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.domain.auth.AuthResult
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.PinDots
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

    val lockoutTimestamp by container.preferences.lockoutUntilTimestamp.collectAsState(initial = 0L)
    var lockoutSecondsLeft by remember { mutableIntStateOf(0) }

    var showForgotPinDialog by remember { mutableStateOf(false) }
    var recoveryCodeInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var recoveryError by remember { mutableStateOf<String?>(null) }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
            .padding(18.dp)
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
                    text = "Enter your PIN to unlock",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            PinDots(
                pinLength = 6,
                enteredCount = enteredPin.length,
                shakeTrigger = shakeTrigger
            )

            Spacer(modifier = Modifier.weight(1f))

            SecurePinPad(
                onDigitClick = { digit ->
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
                                        if (container.preferences.intruderSelfieEnabled.first() && result.attempts >= triggerThreshold) {
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
                },
                onBackspaceClick = {
                    if (enteredPin.isNotEmpty()) {
                        enteredPin = enteredPin.dropLast(1)
                        errorMessage = null
                    }
                },
                showBiometric = false // Biometric prompt is initiated cleanly or via settings
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = { showForgotPinDialog = true }
            ) {
                Text(
                    text = "Forgot PIN?",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showForgotPinDialog) {
        SuyaDialog(
            onDismissRequest = {
                showForgotPinDialog = false
                recoveryError = null
            },
            title = "Recovery Kit",
            confirmText = "Reset PIN",
            onConfirm = {
                if (recoveryCodeInput.isEmpty() || newPinInput.length < 6) {
                    recoveryError = "Enter your recovery code and a new 6-digit PIN"
                    return@SuyaDialog
                }
                scope.launch {
                    val success = container.pinAuthenticator.recoverWithCode(
                        recoveryCodeInput = recoveryCodeInput,
                        newPinChars = newPinInput.toCharArray()
                    )
                    if (success) {
                        showForgotPinDialog = false
                        onUnlocked()
                    } else {
                        recoveryError = "Invalid recovery code"
                    }
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter your 16-character recovery code to reset your vault PIN.",
                    fontFamily = SoraFontFamily,
                    fontSize = 13.sp,
                    color = SuyaColors.TextMuted
                )
                SuyaTextField(
                    value = recoveryCodeInput,
                    onValueChange = { recoveryCodeInput = it },
                    placeholder = "7K9P-4X2B-W8MN-3C5R",
                    label = "Recovery Code"
                )
                SuyaTextField(
                    value = newPinInput,
                    onValueChange = { if (it.length <= 6) newPinInput = it },
                    placeholder = "Enter new 6-digit PIN",
                    label = "New PIN"
                )
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
