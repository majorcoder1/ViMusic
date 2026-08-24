package it.vfsfitvnm.compose.reordering

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier

/**
 * Placement animation for a reorderable list item.
 *
 * While a drag is in progress the item is positioned manually by [draggedItem], so the built-in
 * placement animation has to stay out of the way; it is re-enabled as soon as the drag ends.
 */
fun LazyItemScope.animateItemPlacement(reorderingState: ReorderingState): Modifier =
    if (reorderingState.isDragging) Modifier else Modifier.animateItem()
