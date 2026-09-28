package com.suyaphot.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = SuyaColors.Accent,
    onPrimary = SuyaColors.White,
    secondary = SuyaColors.Surface,
    onSecondary = SuyaColors.White,
    background = SuyaColors.Background,
    onBackground = SuyaColors.Text,
    surface = SuyaColors.Surface,
    onSurface = SuyaColors.Text,
    surfaceVariant = SuyaColors.Fill07,
    onSurfaceVariant = SuyaColors.TextMuted,
    outline = SuyaColors.Line
)

val SuyaShapes = Shapes(
    small = RoundedCornerShape(10.dp),      // Chips
    medium = RoundedCornerShape(16.dp),     // Fields, tabs
    large = RoundedCornerShape(20.dp),      // Cards, dialogs, sheets
    extraLarge = RoundedCornerShape(30.dp)  // Buttons, pill nav
)

@Composable
fun SuyaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = SuyaTypography,
        shapes = SuyaShapes,
        content = content
    )
}
