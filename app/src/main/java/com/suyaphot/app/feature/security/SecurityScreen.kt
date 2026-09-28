package com.suyaphot.app.feature.security

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.core.database.entity.VaultEntity
import com.suyaphot.app.core.model.VaultKind
import com.suyaphot.app.domain.auth.VaultSession
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
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
    val scope = rememberCoroutineScope()
    val session = container.sessionManager.sessionState.collectAsState().value
    val isSecondary = (session as? VaultSession.Unlocked)?.kind == VaultKind.SECONDARY

    val screenshotProtection by container.preferences.screenshotProtection.collectAsState(initial = true)
    val intruderEnabled by container.preferences.intruderSelfieEnabled.collectAsState(initial = false)
    val intruderThreshold by container.preferences.intruderTriggerCount.collectAsState(initial = 3)
    val shizukuEnabled by container.preferences.shizukuEnabled.collectAsState(initial = false)

    var showSecondaryPinDialog by remember { mutableStateOf(false) }
    var secondaryPinInput by remember { mutableStateOf("") }
    var secondaryPinError by remember { mutableStateOf<String?>(null) }

    var isRunningIntegrityCheck by remember { mutableStateOf(false) }
    var integrityStatusMessage by remember { mutableStateOf<String?>(null) }

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
                    subtitle = "AES-256-GCM hardware-backed streaming encryption",
                    icon = Icons.Default.Lock,
                    statusText = "Active",
                    statusPositive = true
                )

                SecurityCard(
                    title = "Recovery Kit",
                    subtitle = "128-bit emergency recovery key envelope",
                    icon = Icons.Default.Key,
                    statusText = "Configured",
                    statusPositive = true
                )

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
                    subtitle = "Silently captures front-camera photo after $intruderThreshold failed PIN attempts",
                    icon = Icons.Default.CameraAlt,
                    checked = intruderEnabled,
                    onCheckedChange = { scope.launch { container.preferences.setIntruderSelfieEnabled(it) } }
                )

                if (intruderEnabled) {
                    SuyaButton(
                        text = "View Intruder Logs",
                        onClick = onViewIntruderLogs,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Shizuku Enhancement Toggle
                SecurityToggleRow(
                    title = "Shizuku File Operations",
                    subtitle = "Optional privileged deletion without individual prompts",
                    icon = Icons.Default.Shield,
                    checked = shizukuEnabled,
                    onCheckedChange = { scope.launch { container.preferences.setShizukuEnabled(it) } }
                )

                // Secondary Access PIN (Decoy vault) - only shown when in real vault
                if (!isSecondary) {
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
                        text = "Set Secondary Access PIN",
                        onClick = { showSecondaryPinDialog = true },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

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
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Auditing AES-GCM tags and SHA-256 checksums...",
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
                            scope.launch(Dispatchers.IO) {
                                val s = session as? VaultSession.Unlocked
                                if (s != null) {
                                    val all = container.database.mediaItemDao().getAllForIntegrityCheck(s.vaultId)
                                    var passed = 0
                                    var failed = 0
                                    for (item in all) {
                                        val file = container.vaultFileStore.getMediaFile(s.vaultId, item.id)
                                        try {
                                            val verify = container.vaultCrypto.verifyAndHash(file, s.mediaSubkey, item.id)
                                            if (verify.plaintextSize == item.plaintextSize) passed++ else failed++
                                        } catch (e: Exception) {
                                            failed++
                                        }
                                    }
                                    integrityStatusMessage = "Audit finished: $passed verified, $failed corrupted"
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
                            color = SuyaColors.Positive
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }

    // Secondary PIN Dialog
    if (showSecondaryPinDialog) {
        SuyaDialog(
            onDismissRequest = {
                showSecondaryPinDialog = false
                secondaryPinInput = ""
                secondaryPinError = null
            },
            title = "Secondary Access PIN",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Entering this PIN on the lock screen will seamlessly open your secondary vault.",
                        fontFamily = SoraFontFamily,
                        fontSize = 13.sp,
                        color = SuyaColors.TextMuted
                    )
                    SuyaTextField(
                        value = secondaryPinInput,
                        onValueChange = { if (it.length <= 6) secondaryPinInput = it },
                        placeholder = "Enter 6-digit secondary PIN",
                        label = "Secondary PIN"
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
                if (secondaryPinInput.length < 6) {
                    secondaryPinError = "Secondary PIN must be 6 digits"
                    return@SuyaDialog
                }
                scope.launch {
                    val secMasterKey = container.keyManager.generateMasterKey()
                    val pinEnvelope = container.keyManager.createPinEnvelope(secMasterKey, secondaryPinInput.toCharArray())
                    val dummyRecovery = container.keyManager.generateRecoverySecret()
                    val normRecovery = container.keyManager.normalizeRecoverySecret(dummyRecovery)
                    val recoveryEnvelope = container.keyManager.createRecoveryEnvelope(secMasterKey, normRecovery)

                    val secVaultId = UUID.randomUUID().toString()
                    val secVaultEntity = VaultEntity(
                        id = secVaultId,
                        kindCode = VaultKind.SECONDARY.code,
                        createdAt = System.currentTimeMillis(),
                        schemaVersion = 1,
                        pinEnvelope = pinEnvelope.serialize(),
                        recoveryEnvelope = recoveryEnvelope.serialize(),
                        biometricEnvelope = null,
                        biometricIv = null
                    )

                    withContext(Dispatchers.IO) {
                        container.database.vaultDao().insert(secVaultEntity)
                    }
                    showSecondaryPinDialog = false
                    secondaryPinInput = ""
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
        shape = RoundedCornerShape(20.dp),
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
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = SuyaColors.Accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontFamily = SoraFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = SuyaColors.White)
                Text(text = subtitle, fontFamily = SoraFontFamily, fontSize = 12.sp, color = SuyaColors.TextMuted)
            }
            Text(
                text = statusText,
                fontFamily = SoraFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = if (statusPositive) SuyaColors.Positive else SuyaColors.Negative
            )
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
        shape = RoundedCornerShape(20.dp),
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
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = SuyaColors.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontFamily = SoraFontFamily, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = SuyaColors.White)
                Text(text = subtitle, fontFamily = SoraFontFamily, fontSize = 12.sp, color = SuyaColors.TextMuted)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = SuyaColors.White,
                    checkedTrackColor = SuyaColors.Accent,
                    uncheckedThumbColor = SuyaColors.White,
                    uncheckedTrackColor = SuyaColors.ToggleOff
                )
            )
        }
    }
}
