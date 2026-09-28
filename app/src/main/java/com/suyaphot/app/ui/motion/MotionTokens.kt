package com.suyaphot.app.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * Centralized motion tokens from the design specification.
 */
object MotionTokens {
    const val DurationPress = 100
    const val DurationSmall = 160
    const val DurationNavigation = 220
    const val DurationSheet = 260
    const val DurationMediaOpen = 300
    const val DurationShake = 250

    val DefaultEasing: Easing = FastOutSlowInEasing
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
}
