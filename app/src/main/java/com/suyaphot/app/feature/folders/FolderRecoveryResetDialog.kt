package com.suyaphot.app.feature.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suyaphot.app.app.AppContainer
import com.suyaphot.app.domain.auth.PatternCredential
import com.suyaphot.app.ui.components.ButtonVariant
import com.suyaphot.app.ui.components.PatternLockPad
import com.suyaphot.app.ui.components.SuyaButton
import com.suyaphot.app.ui.components.SuyaDialog
import com.suyaphot.app.ui.components.SuyaTextField
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.launch

@Composable
fun FolderRecoveryResetDialog(
    container: AppContainer,
    folderId: String,
    folderName: String = "Protected folder",
    initialTypeCode: Int = 0,
    onDismissRequest: () -> Unit,
    onResetSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var recoveryCode by remember { mutableStateOf("") }
    var revealRecoveryCode by remember { mutableStateOf(false) }
    var targetType by remember { mutableIntStateOf(initialTypeCode) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var firstPattern by remember { mutableStateOf<IntArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorTrigger by remember { mutableIntStateOf(0) }

    fun clearState() {
        recoveryCode = ""
        revealRecoveryCode = false
        newPin = ""
        confirmPin = ""
        firstPattern = null
        error = null
    }

    fun submit(newCredential: CharArray) {
        if (recoveryCode.isBlank()) {
            newCredential.fill('\u0000')
            error = "Enter your 26-character Recovery Code"
            errorTrigger++
            return
        }
        scope.launch {
            val codeToSubmit = recoveryCode.trim()
            val success = container.folderLockManager.resetWithRecovery(
                folderId = folderId,
                recoveryCode = codeToSubmit,
                replacement = newCredential,
                replacementType = targetType
            )
            newCredential.fill('\u0000')
            if (success) {
                clearState()
                onResetSuccess()
            } else {
                error = "Recovery failed. Check your Recovery Code."
                errorTrigger++
            }
        }
    }

    SuyaDialog(
        onDismissRequest = {
            clearState()
            onDismissRequest()
        },
        title = "Reset Folder Lock",
        confirmText = if (targetType == 0) "Reset Lock" else null,
        onConfirm = if (targetType == 0) ({
            if (newPin.length < 4) {
                error = "PIN must be at least 4 digits"
                errorTrigger++
            } else if (newPin != confirmPin) {
                error = "PINs do not match"
                errorTrigger++
            } else {
                submit(newPin.toCharArray())
            }
        }) else null,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This protected folder was restored from backup. Enter your vault Recovery Kit code to set a new PIN or Pattern for \"$folderName\".",
                    color = SuyaColors.TextMuted,
                    fontSize = 12.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SuyaTextField(
                        value = recoveryCode,
                        onValueChange = { recoveryCode = it.uppercase().take(32) },
                        label = "Vault Recovery Code",
                        placeholder = "XXXX-XXXX-XXXX-...",
                        visualTransformation = if (revealRecoveryCode) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { revealRecoveryCode = !revealRecoveryCode }) {
                        Icon(
                            imageVector = if (revealRecoveryCode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (revealRecoveryCode) "Hide Recovery Code" else "Reveal Recovery Code",
                            tint = SuyaColors.TextMuted
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuyaButton(
                        text = "New PIN",
                        onClick = { targetType = 0; firstPattern = null },
                        variant = if (targetType == 0) ButtonVariant.Primary else ButtonVariant.Secondary
                    )
                    SuyaButton(
                        text = "New Pattern",
                        onClick = { targetType = 1; newPin = ""; confirmPin = "" },
                        variant = if (targetType == 1) ButtonVariant.Primary else ButtonVariant.Secondary
                    )
                }
                if (targetType == 0) {
                    SuyaTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter(Char::isDigit).take(12) },
                        label = "New folder PIN",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    SuyaTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter(Char::isDigit).take(12) },
                        label = "Confirm new PIN",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                } else {
                    Text(
                        if (firstPattern == null) "Draw new pattern" else "Draw it again to confirm",
                        color = SuyaColors.TextMuted,
                        fontSize = 12.sp
                    )
                    PatternLockPad(
                        onPatternComplete = { raw ->
                            val normalized = runCatching { PatternCredential.normalize(raw) }.getOrNull()
                            if (normalized == null) {
                                error = "Connect at least four dots"
                                errorTrigger++
                            } else if (firstPattern == null) {
                                firstPattern = normalized
                                error = null
                            } else if (!normalized.contentEquals(firstPattern)) {
                                firstPattern = null
                                error = "Patterns do not match"
                                errorTrigger++
                            } else {
                                submit(PatternCredential.canonicalChars(normalized))
                            }
                        },
                        errorTrigger = errorTrigger,
                        enabled = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let { Text(it, color = SuyaColors.Negative, fontSize = 12.sp) }
            }
        }
    )
}
