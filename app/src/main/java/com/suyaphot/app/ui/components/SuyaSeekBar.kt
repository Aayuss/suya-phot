package com.suyaphot.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suyaphot.app.ui.theme.SuyaColors

/**
 * Minimal, fluid video seek bar designed specifically for Suya Phot.
 *
 * Features:
 * - 2.5dp thin track with rounded end caps
 * - Suya orange played rail (`SuyaColors.Accent`)
 * - Subtle inactive background rail
 * - Thumb expands smoothly from 3.5dp to 6dp during touch/drag
 * - 40dp invisible hit-box for reliable thumb interaction
 * - Consumes all horizontal drag events to prevent HorizontalPager paging
 * - Supports both instantaneous tap-to-seek and drag-to-scrub
 */
@Composable
fun SuyaSeekBar(
    progress: Float,
    onSeekStarted: () -> Unit,
    onSeekProgress: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 2.5.dp,
    touchTargetHeight: Dp = 40.dp,
    activeColor: Color = SuyaColors.Accent,
    inactiveColor: Color = Color.White.copy(alpha = 0.25f),
    thumbColor: Color = SuyaColors.Accent
) {
    var isDragging by remember { mutableStateOf(false) }

    val animatedThumbRadius by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 3.5.dp,
        animationSpec = tween(durationMillis = 150),
        label = "thumbRadius"
    )

    val clampedProgress = progress.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(touchTargetHeight)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture

                    var currentFrac = (down.position.x / width).coerceIn(0f, 1f)

                    // Check if user drags beyond slop
                    val dragChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                    }

                    if (dragChange != null) {
                        // User started dragging
                        isDragging = true
                        onSeekStarted()
                        currentFrac = (dragChange.position.x / width).coerceIn(0f, 1f)
                        onSeekProgress(currentFrac)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            change.consume()
                            currentFrac = (change.position.x / width).coerceIn(0f, 1f)
                            onSeekProgress(currentFrac)
                        }

                        isDragging = false
                        onSeekFinished(currentFrac)
                    } else {
                        // It was a tap / press without dragging past slop
                        down.consume()
                        isDragging = true
                        onSeekStarted()
                        onSeekProgress(currentFrac)

                        // Wait for finger release
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }

                        isDragging = false
                        onSeekFinished(currentFrac)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
        ) {
            val canvasWidth = size.width
            val centerY = size.height / 2f
            val strokeWidthPx = trackHeight.toPx()
            val thumbRadiusPx = animatedThumbRadius.toPx()

            // Inactive full rail
            drawLine(
                color = inactiveColor,
                start = Offset(x = 0f, y = centerY),
                end = Offset(x = canvasWidth, y = centerY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )

            // Active played rail
            val activeEnd = (clampedProgress * canvasWidth).coerceIn(0f, canvasWidth)
            if (activeEnd > 0f) {
                drawLine(
                    color = activeColor,
                    start = Offset(x = 0f, y = centerY),
                    end = Offset(x = activeEnd, y = centerY),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            }

            // Thumb
            drawCircle(
                color = thumbColor,
                radius = thumbRadiusPx,
                center = Offset(x = activeEnd, y = centerY)
            )

            // Inner white dot when dragging for high-precision feedback
            if (isDragging) {
                drawCircle(
                    color = Color.White,
                    radius = (thumbRadiusPx * 0.45f).coerceAtLeast(1.5f),
                    center = Offset(x = activeEnd, y = centerY)
                )
            }
        }
    }
}
