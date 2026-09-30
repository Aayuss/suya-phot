package com.suyaphot.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.suyaphot.app.ui.theme.SuyaColors
import com.suyaphot.app.ui.theme.SuyaStyles

@Composable
fun SecurePinPad(
    onDigitClick: (Char) -> Unit,
    onBackspaceClick: () -> Unit,
    modifier: Modifier = Modifier,
    onBiometricClick: (() -> Unit)? = null,
    showBiometric: Boolean = false
) {
    val haptic = LocalHapticFeedback.current

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = SuyaColors.Fill05,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            val rows = listOf(
                listOf('1', '2', '3'),
                listOf('4', '5', '6'),
                listOf('7', '8', '9')
            )

            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for (digit in row) {
                        PinKey(
                            label = digit.toString(),
                            contentDescription = "Digit $digit",
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDigitClick(digit)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Bottom row: Biometric / 0 / Backspace
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bottom left: Biometric or empty
                if (showBiometric && onBiometricClick != null) {
                    ActionKey(
                        contentDescription = "Unlock with biometric",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBiometricClick()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Fingerprint",
                            tint = SuyaColors.Accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    Box(modifier = Modifier.weight(1f))
                }

                // Center: Digit 0
                PinKey(
                    label = "0",
                    contentDescription = "Digit 0",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDigitClick('0')
                    },
                    modifier = Modifier.weight(1f)
                )

                // Bottom right: Backspace
                ActionKey(
                    contentDescription = "Backspace",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBackspaceClick()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        tint = SuyaColors.TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PinKey(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val textColor by animateColorAsState(
        targetValue = if (isPressed) SuyaColors.Accent else SuyaColors.White,
        label = "key_color"
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        label = "key_scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(58.dp)
            .scale(scale)
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Text(
            text = label,
            style = SuyaStyles.KeypadDigit.copy(color = textColor)
        )
    }
}

@Composable
private fun ActionKey(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        label = "action_scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(58.dp)
            .scale(scale)
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        content()
    }
}
