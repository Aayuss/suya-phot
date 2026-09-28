package com.suyaphot.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.suyaphot.app.ui.theme.SuyaColors
import com.suyaphot.app.ui.theme.SuyaStyles

enum class ButtonVariant {
    Primary,
    Secondary,
    White,
    Outline,
    Ghost
}

@Composable
fun SuyaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        label = "btn_scale"
    )

    val shape = RoundedCornerShape(30.dp)

    when (variant) {
        ButtonVariant.Primary -> {
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SuyaColors.Accent,
                    contentColor = SuyaColors.White,
                    disabledContainerColor = SuyaColors.Accent.copy(alpha = 0.4f),
                    disabledContentColor = SuyaColors.White.copy(alpha = 0.4f)
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                interactionSource = interactionSource,
                modifier = modifier.scale(scale)
            ) {
                Text(text = text, style = SuyaStyles.ButtonText)
            }
        }
        ButtonVariant.Secondary -> {
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SuyaColors.Surface,
                    contentColor = SuyaColors.White,
                    disabledContainerColor = SuyaColors.Surface.copy(alpha = 0.4f)
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                interactionSource = interactionSource,
                modifier = modifier.scale(scale)
            ) {
                Text(text = text, style = SuyaStyles.ButtonText)
            }
        }
        ButtonVariant.White -> {
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SuyaColors.White,
                    contentColor = SuyaColors.Background
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                interactionSource = interactionSource,
                modifier = modifier.scale(scale)
            ) {
                Text(text = text, style = SuyaStyles.ButtonText.copy(color = SuyaColors.Background))
            }
        }
        ButtonVariant.Outline -> {
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                border = BorderStroke(1.dp, SuyaColors.StrokeMid),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SuyaColors.White
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                interactionSource = interactionSource,
                modifier = modifier.scale(scale)
            ) {
                Text(text = text, style = SuyaStyles.ButtonText)
            }
        }
        ButtonVariant.Ghost -> {
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = SuyaColors.Accent
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                interactionSource = interactionSource,
                modifier = modifier.scale(scale)
            ) {
                Text(text = text, style = SuyaStyles.ButtonText.copy(color = SuyaColors.Accent))
            }
        }
    }
}

@Composable
fun SuyaIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    size: Int = 42
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        label = "icon_btn_scale"
    )

    Surface(
        shape = CircleShape,
        color = if (active) SuyaColors.Accent else SuyaColors.Fill07,
        modifier = modifier
            .size(size.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) SuyaColors.White else SuyaColors.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
