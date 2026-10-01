package com.suyaphot.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.longPressDragSelect(
    gridState: LazyGridState,
    enabled: Boolean = true,
    onRange: (anchorIndex: Int, currentIndex: Int) -> Unit
): Modifier = if (!enabled) this else pointerInput(gridState) {
    var anchor = -1

    fun indexAt(position: Offset): Int? =
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            position.x >= item.offset.x &&
                position.x < item.offset.x + item.size.width &&
                position.y >= item.offset.y &&
                position.y < item.offset.y + item.size.height
        }?.index

    detectDragGesturesAfterLongPress(
        onDragStart = { start ->
            anchor = indexAt(start) ?: -1
            if (anchor >= 0) onRange(anchor, anchor)
        },
        onDragCancel = { anchor = -1 },
        onDragEnd = { anchor = -1 },
        onDrag = { change, _ ->
            if (anchor < 0) return@detectDragGesturesAfterLongPress
            val current = indexAt(change.position) ?: return@detectDragGesturesAfterLongPress
            onRange(anchor, current)
            change.consume()
        }
    )
}
