package com.suyaphot.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Samsung-Gallery-style long-press then drag selection for a LazyVerticalGrid.
 * The screen decides how an index range maps to IDs.
 */
fun Modifier.dragSelectGrid(
    state: LazyGridState,
    onRangeChanged: (anchorIndex: Int, currentIndex: Int) -> Unit
): Modifier = pointerInput(state) {
    var anchorIndex: Int? = null

    fun indexAt(position: Offset): Int? {
        return state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val left = item.offset.x.toFloat()
            val top = item.offset.y.toFloat()
            val right = left + item.size.width
            val bottom = top + item.size.height
            position.x in left..right && position.y in top..bottom
        }?.index
    }

    detectDragGesturesAfterLongPress(
        onDragStart = { position ->
            anchorIndex = indexAt(position)
            anchorIndex?.let { onRangeChanged(it, it) }
        },
        onDrag = { change, _ ->
            val anchor = anchorIndex ?: return@detectDragGesturesAfterLongPress
            val current = indexAt(change.position) ?: return@detectDragGesturesAfterLongPress
            change.consume()
            onRangeChanged(anchor, current)
        },
        onDragEnd = { anchorIndex = null },
        onDragCancel = { anchorIndex = null }
    )
}
