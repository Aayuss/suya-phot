package com.suyaphot.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Exact color palette from the Ember visual system specification.
 */
object SuyaColors {
    val Background = Color(0xFF131314)
    val Surface = Color(0xFF222222)
    val Accent = Color(0xFFE55F11)
    val White = Color(0xFFFFFFFF)

    val Text = Color(0xFFFFFFFF)
    val TextMuted = Color(0x80FFFFFF)      // rgba(255, 255, 255, 0.50)
    val TextFaint = Color(0x59FFFFFF)      // rgba(255, 255, 255, 0.35)
    val TextSoft = Color(0xFFDDDDDD)

    val Line = Color(0x14FFFFFF)           // rgba(255, 255, 255, 0.08)
    val Fill05 = Color(0x0DFFFFFF)         // rgba(255, 255, 255, 0.05)
    val Fill06 = Color(0x0FFFFFFF)         // rgba(255, 255, 255, 0.06)
    val Fill07 = Color(0x12FFFFFF)         // rgba(255, 255, 255, 0.07)
    val Fill20 = Color(0x33FFFFFF)         // rgba(255, 255, 255, 0.20)

    val Positive = Color(0xFF3FD05E)
    val Negative = Color(0xFFFF6B5A)

    val ToggleOff = Color(0xFF3A3A3A)
    val StrokeMid = Color(0xFF555555)
    val StrokeLow = Color(0xFF444444)

    // Olive top-left subtle glow screen background from spec
    val ScreenBackgroundBrush = Brush.radialGradient(
        colors = listOf(Color(0xFF272B22), Color(0xFF131314)),
        center = androidx.compose.ui.geometry.Offset(100f, 0f),
        radius = 800f
    )
}
