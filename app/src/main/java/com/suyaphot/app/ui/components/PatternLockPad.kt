package com.suyaphot.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.suyaphot.app.ui.theme.SuyaColors
import kotlinx.coroutines.delay

enum class PatternVisualState {
    IDLE,
    DRAWING,
    VERIFYING,
    ERROR,
    SUCCESS
}

/** Gesture-only pad; never use rememberSaveable for the selected nodes. */
@Composable
fun PatternLockPad(
    onPatternComplete: (IntArray) -> Unit,
    errorTrigger: Int = 0,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    visualState: PatternVisualState? = null
) {
    val selected = remember { mutableStateListOf<Int>() }
    val finger = remember { mutableStateOf<Offset?>(null) }
    var internalState by remember { mutableStateOf(PatternVisualState.IDLE) }
    val effectiveState = visualState ?: internalState
    val haptic = LocalHapticFeedback.current
    val stroke = with(LocalDensity.current) { 3.dp.toPx() }

    LaunchedEffect(errorTrigger) {
        if (errorTrigger > 0) {
            internalState = PatternVisualState.ERROR
            delay(400)
            selected.clear()
            finger.value = null
            internalState = PatternVisualState.IDLE
        }
    }

    val lineColor = when (effectiveState) {
        PatternVisualState.ERROR -> SuyaColors.Negative
        PatternVisualState.SUCCESS -> SuyaColors.Positive
        PatternVisualState.DRAWING, PatternVisualState.VERIFYING -> SuyaColors.Accent
        PatternVisualState.IDLE -> SuyaColors.Accent
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "Pattern unlock grid" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    selected.clear()
                    internalState = PatternVisualState.DRAWING
                    fun add(position: Offset) {
                        val spacing = size.width / 3f
                        val column = (position.x / spacing).toInt().coerceIn(0, 2)
                        val row = (position.y / spacing).toInt().coerceIn(0, 2)
                        val center = Offset((column + 0.5f) * spacing, (row + 0.5f) * spacing)
                        if ((position - center).getDistance() > spacing * 0.38f) return
                        val node = row * 3 + column
                        if (node !in selected) {
                            selected.add(node)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    add(down.position)
                    finger.value = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        finger.value = change.position
                        if (!change.pressed) break
                        add(change.position)
                    }
                    finger.value = null
                    if (selected.isNotEmpty()) {
                        internalState = PatternVisualState.VERIFYING
                        onPatternComplete(selected.toIntArray())
                    } else {
                        internalState = PatternVisualState.IDLE
                    }
                }
            }
    ) {
        val spacing = size.width / 3f
        fun center(node: Int) = Offset((node % 3 + 0.5f) * spacing, (node / 3 + 0.5f) * spacing)
        selected.zipWithNext().forEach { (from, to) ->
            drawLine(lineColor, center(from), center(to), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        if (selected.isNotEmpty() && effectiveState == PatternVisualState.DRAWING) finger.value?.let { current ->
            drawLine(lineColor, center(selected.last()), current, strokeWidth = stroke, cap = StrokeCap.Round)
        }
        repeat(9) { node ->
            val active = node in selected
            if (active && (effectiveState == PatternVisualState.ERROR || effectiveState == PatternVisualState.SUCCESS)) {
                drawCircle(
                    color = lineColor.copy(alpha = 0.25f),
                    radius = spacing * 0.16f,
                    center = center(node)
                )
            }
            drawCircle(
                color = if (active) lineColor else SuyaColors.TextMuted,
                radius = if (active) spacing * 0.075f else spacing * 0.045f,
                center = center(node)
            )
        }
    }
}
