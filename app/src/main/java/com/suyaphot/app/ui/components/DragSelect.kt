package com.suyaphot.app.ui.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-press, then drag across a LazyVerticalGrid to select a contiguous range.
 *
 * Implemented using PointerEventPass.Initial and concurrent coroutine delay so that
 * child item clickables (such as MediaTile combinedClickable) cannot starve or cancel
 * the drag-selection gesture.
 *
 * Features:
 * - Robust long-press detection (~400ms delay without exceeding touch slop)
 * - Once activated, consumes pointer events in Initial pass so children do not receive spurious clicks
 * - Reversible range selection: reports (anchorIndex, currentIndex) continuously
 * - Smooth auto-scrolling when dragging near top or bottom edges of the grid
 * - Clean lifecycle: onStart(anchor), onRange(anchor, current), onEnd()
 */
fun Modifier.longPressDragSelect(
    gridState: LazyGridState,
    enabled: Boolean = true,
    autoScrollThresholdDp: Float = 60f,
    maxScrollVelocity: Float = 20f,
    onStart: (anchorIndex: Int) -> Unit = {},
    onRange: (anchorIndex: Int, currentIndex: Int) -> Unit,
    onEnd: () -> Unit = {}
): Modifier = if (!enabled) this else pointerInput(gridState) {
    coroutineScope {
        var autoScrollJob: Job? = null
        var longPressJob: Job? = null

        fun indexAt(position: Offset): Int? {
            val items = gridState.layoutInfo.visibleItemsInfo
            if (items.isEmpty()) return null
            val exact = items.firstOrNull { item ->
                position.x >= item.offset.x &&
                    position.x < item.offset.x + item.size.width &&
                    position.y >= item.offset.y &&
                    position.y < item.offset.y + item.size.height
            }?.index
            if (exact != null) return exact
            return items.minByOrNull { item ->
                val centerX = item.offset.x + item.size.width / 2f
                val centerY = item.offset.y + item.size.height / 2f
                val dx = position.x - centerX
                val dy = position.y - centerY
                dx * dx + dy * dy
            }?.index
        }

        val density = this@pointerInput.density
        val topThresholdPx = autoScrollThresholdDp * density
        val touchSlopPx = viewConfiguration.touchSlop
        val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis

        while (isActive) {
            var isLongPressActive = false
            var anchor = -1
            try {
                awaitPointerEventScope {
                    // 1. Wait for pointer down in Initial pass
                    var downChange: PointerInputChange? = null
                    while (downChange == null) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.firstOrNull { it.pressed }
                        if (down != null) {
                            downChange = down
                        }
                    }

                    val pointerId = downChange.id
                    val startPos = downChange.position
                    var currentPointerOffset = startPos
                    var currentPointerY = startPos.y

                    // 2. Launch concurrent coroutine timer for long-press
                    longPressJob = this@coroutineScope.launch {
                        delay(longPressTimeoutMs)
                        val targetIndex = indexAt(currentPointerOffset)
                        if (targetIndex != null && targetIndex >= 0) {
                            isLongPressActive = true
                            anchor = targetIndex
                            onStart(anchor)
                            onRange(anchor, anchor)

                            // Start auto-scroll job
                            val bottomThresholdPx = size.height - (autoScrollThresholdDp * density)
                            autoScrollJob = this@coroutineScope.launch {
                                while (isActive && isLongPressActive) {
                                    val dy = when {
                                        currentPointerY < topThresholdPx -> {
                                            val factor = ((topThresholdPx - currentPointerY) / topThresholdPx).coerceIn(0f, 1f)
                                            -maxScrollVelocity * density * factor
                                        }
                                        currentPointerY > bottomThresholdPx -> {
                                            val factor = ((currentPointerY - bottomThresholdPx) / (size.height - bottomThresholdPx)).coerceIn(0f, 1f)
                                            maxScrollVelocity * density * factor
                                        }
                                        else -> 0f
                                    }

                                    if (dy != 0f) {
                                        gridState.scrollBy(dy)
                                        val currentIndex = indexAt(currentPointerOffset)
                                        if (currentIndex != null && anchor >= 0) {
                                            onRange(anchor, currentIndex)
                                        }
                                    }
                                    delay(16)
                                }
                            }
                        }
                    }

                    // 3. Process pointer events in Initial pass
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                        if (!change.pressed) {
                            longPressJob?.cancel()
                            if (isLongPressActive) {
                                change.consume()
                            }
                            break
                        }

                        currentPointerOffset = change.position
                        currentPointerY = change.position.y

                        if (!isLongPressActive) {
                            if ((change.position - startPos).getDistance() > touchSlopPx) {
                                longPressJob?.cancel()
                                break
                            }
                        } else {
                            change.consume()
                            val currentIndex = indexAt(change.position)
                            if (currentIndex != null && anchor >= 0) {
                                onRange(anchor, currentIndex)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                longPressJob?.cancel()
                longPressJob = null
                autoScrollJob?.cancel()
                autoScrollJob = null
                if (isLongPressActive) {
                    onEnd()
                }
            }
        }
    }
}
