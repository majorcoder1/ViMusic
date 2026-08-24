package it.vfsfitvnm.compose.reordering

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex

/**
 * Offsets an item to follow the drag, and shifts the items it is being dragged past.
 *
 * The item under the finger is also pinned for the duration of the drag: a lazy list disposes
 * items once they leave the viewport, which during a long drag would make the dragged row vanish
 * mid-gesture.
 */
@Composable
fun Modifier.draggedItem(
    reorderingState: ReorderingState,
    index: Int
): Modifier {
    val isBeingDragged = reorderingState.draggingIndex == index
    val pinnableContainer = LocalPinnableContainer.current

    DisposableEffect(pinnableContainer, isBeingDragged) {
        val handle = if (isBeingDragged) pinnableContainer?.pin() else null
        onDispose { handle?.release() }
    }

    return when (reorderingState.draggingIndex) {
        -1 -> this
        index -> offset {
            when (reorderingState.lazyListState.layoutInfo.orientation) {
                Orientation.Vertical -> IntOffset(0, reorderingState.offset.value)
                Orientation.Horizontal -> IntOffset(reorderingState.offset.value, 0)
            }
        }.zIndex(1f)
        else -> offset {
            val offset = when (index) {
                in reorderingState.indexesToAnimate -> reorderingState.indexesToAnimate.getValue(index).value
                in (reorderingState.draggingIndex + 1)..reorderingState.reachedIndex -> -reorderingState.draggingItemSize
                in reorderingState.reachedIndex until reorderingState.draggingIndex -> reorderingState.draggingItemSize
                else -> 0
            }
            when (reorderingState.lazyListState.layoutInfo.orientation) {
                Orientation.Vertical -> IntOffset(0, offset)
                Orientation.Horizontal -> IntOffset(offset, 0)
            }
        }
    }
}
