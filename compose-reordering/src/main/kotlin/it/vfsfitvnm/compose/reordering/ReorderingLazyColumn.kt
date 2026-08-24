package it.vfsfitvnm.compose.reordering

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A [LazyColumn] whose items can be reordered by dragging.
 *
 * This was originally a fork of Compose Foundation's internal `LazyList`, copied so it could reach
 * into private measure-pass APIs. None of those APIs exist any more, and nothing here needs them:
 * item movement is animated through the public `Modifier.animateItem`, and the item under the
 * finger is kept composed by pinning it (see [draggedItem]) rather than by injecting a
 * `LazyListBeyondBoundsInfo` into the measure policy.
 */
@Composable
fun ReorderingLazyColumn(
    reorderingState: ReorderingState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        state = reorderingState.lazyListState,
        modifier = modifier,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content
    )
}
