package com.suyaphot.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Gallery-style long-press + drag multi-selection for LazyVerticalGrid.
 *
 * Normal scrolling is untouched. Once the long-press is recognized, dragging selects every
 * visible item between the anchor and the current finger position. Near the top/bottom edge the
 * grid auto-scrolls and newly revealed rows join the same continuous range.
 */
fun Modifier.dragSelectGrid(
    state: LazyGridState,
    scope: CoroutineScope,
    enabled: Boolean = true,
    onItemsSelected: (List<String>) -> Unit
): Modifier = pointerInput(state, enabled) {
    if (!enabled) return@pointerInput

    var anchorIndex: Int? = null

    fun itemIndexAt(position: Offset): Int? {
        return state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val left = item.offset.x.toFloat()
            val top = item.offset.y.toFloat()
            val right = left + item.size.width
            val bottom = top + item.size.height
            position.x in left..right && position.y in top..bottom
        }?.index
    }

    fun emitRange(currentIndex: Int) {
        val anchor = anchorIndex ?: currentIndex.also { anchorIndex = it }
        val rangeStart = min(anchor, currentIndex)
        val rangeEnd = max(anchor, currentIndex)
        val ids = state.layoutInfo.visibleItemsInfo
            .asSequence()
            .filter { it.index in rangeStart..rangeEnd }
            .mapNotNull { it.key as? String }
            .toList()
        if (ids.isNotEmpty()) onItemsSelected(ids)
    }

    detectDragGesturesAfterLongPress(
        onDragStart = { position ->
            val index = itemIndexAt(position) ?: return@detectDragGesturesAfterLongPress
            anchorIndex = index
            emitRange(index)
        },
        onDrag = { change, _ ->
            change.consume()
            val current = itemIndexAt(change.position)
            if (current != null) emitRange(current)

            val viewportHeight =
                (state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset).toFloat()
            val edgePx = 96f
            val scrollBy = when {
                change.position.y < edgePx -> -42f
                change.position.y > viewportHeight - edgePx -> 42f
                else -> 0f
            }
            if (scrollBy != 0f) {
                scope.launch {
                    state.scrollBy(scrollBy)
                    itemIndexAt(change.position)?.let(::emitRange)
                }
            }
        },
        onDragEnd = { anchorIndex = null },
        onDragCancel = { anchorIndex = null }
    )
}
