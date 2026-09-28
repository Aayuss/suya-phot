package com.suyaphot.app.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.R
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.core.util.SafeLog
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.PinDots
import com.suyaphot.app.ui.components.SecurePinPad
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.theme.SoraFontFamily
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class SetupStep {
    WELCOME,
    ENTER_PIN,
    CONFIRM_PIN,
    RECOVERY_KIT,
    CONFIRM_RECOVERY,
    COMPLETE
}

@Composable
fun SetupScreen(
    container: AppContainer,
    onSetupComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentStep by remember { mutableStateOf(SetupStep.WELCOME) }

    var initialPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinErrorMessage by remember { mutableStateOf<String?>(null) }
    var shakeTrigger by remember { mutableStateOf(0) }

    var generatedRecoveryCode by remember { mutableStateOf("") }
    var recoveryConfirmationInput by remember { mutableStateOf("") }
    var recoveryErrorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SuyaColors.Background)
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
                            text = "Encrypted private photo and video vault for your device. Local-first, zero-knowledge, hardware-backed protection.",
                            fontFamily = SoraFontFamily,
                            fontSize = 14.sp,
                            color = SuyaColors.TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(modifier = Modifier.height(40.dp))
                        SuyaButton(
                            text = "Create Vault",
                            onClick = { currentStep = SetupStep.ENTER_PIN },
                            modifier = Modifier.fillMaxWidth(0.8f)
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
                            text = "Choose a numeric PIN (6 digits recommended)",
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
                            text = "Your Recovery Kit",
                            fontFamily = SoraFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 22.sp,
                            color = SuyaColors.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Write down this high-entropy recovery code. If you ever forget your PIN, this is the ONLY way to recover your vault.",
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
                                Text(
                                    text = generatedRecoveryCode,
                                    fontFamily = SoraFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 20.sp,
                                    letterSpacing = 2.sp,
                                    color = SuyaColors.White
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        SuyaButton(
                            text = "I Have Saved This Code",
                            onClick = {
                                // Finalize vault creation
                                scope.launch {
                                    val masterKey = container.keyManager.generateMasterKey()
                                    val pinChars = initialPin.toCharArray()
                                    val pinEnvelope = container.keyManager.createPinEnvelope(masterKey, pinChars)
                                    val normRecovery = container.keyManager.normalizeRecoverySecret(generatedRecoveryCode)
                                    val recoveryEnvelope = container.keyManager.createRecoveryEnvelope(masterKey, normRecovery)

                                    val vaultId = UUID.randomUUID().toString()
                                    val vaultEntity = VaultEntity(
                                        id = vaultId,
                                        kindCode = VaultKind.REAL.code,
                                        createdAt = System.currentTimeMillis(),
                                        schemaVersion = 1,
                                        pinEnvelope = pinEnvelope.serialize(),
                                        recoveryEnvelope = recoveryEnvelope.serialize(),
                                        biometricEnvelope = null,
                                        biometricIv = null
                                    )

                                    withContext(Dispatchers.IO) {
                                        container.database.vaultDao().insert(vaultEntity)
                                    }

                                    // Unlock active session
                                    val authResult = container.pinAuthenticator.authenticateWithPin(pinChars)
                                    if (authResult is com.suyaphot.app.domain.auth.AuthResult.Success) {
                                        onSetupComplete()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        )
                    }
                }

                SetupStep.CONFIRM_RECOVERY, SetupStep.COMPLETE -> {}
            }
        }
    }
}
