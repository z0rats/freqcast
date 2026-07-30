package com.freqcast.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Drives a long-press-anywhere-then-drag-vertically reorder gesture for a [LazyListState]-backed
 * list (here: [com.freqcast.ui.MainScreen]'s station list). Long-press starts the drag;
 * dragging past a neighboring item's midpoint swaps them immediately via [onMove] (called once
 * per crossing, with the two 0-based indices to swap — the caller applies this to its own backing
 * list, e.g. [com.freqcast.ui.MainViewModel.moveStation]); releasing calls [onDragEnd].
 *
 * Adapted from the shape widely used for hand-rolled Compose reorderable lists (no dependency
 * pulled in for this, matching the project's low-ceremony style) since `LazyColumn` has no
 * built-in drag-to-reorder as of this Compose BOM.
 */
class DragDropState internal constructor(
    private val state: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    private val onDragStopped: () -> Unit,
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    private var draggedDistance by mutableFloatStateOf(0f)
    private var draggingItemInitialOffset by mutableFloatStateOf(0f)

    internal val draggingItemOffset: Float
        get() =
            draggingItemLayoutInfo?.let { item ->
                draggingItemInitialOffset + draggedDistance - item.offset
            } ?: 0f

    private val draggingItemLayoutInfo: LazyListItemInfo?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingItemIndex }

    internal val scrollChannel = Channel<Float>()

    fun onDragStart(offset: Offset) {
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { item -> offset.y.toInt() in item.offset..(item.offset + item.size) }
            ?.also {
                draggingItemIndex = it.index
                draggingItemInitialOffset = it.offset.toFloat()
            }
    }

    fun onDragEnd() {
        val wasDragging = draggingItemIndex != null
        draggingItemIndex = null
        draggedDistance = 0f
        draggingItemInitialOffset = 0f
        if (wasDragging) onDragStopped()
    }

    fun onDrag(offset: Offset) {
        draggedDistance += offset.y

        val currentIndex = draggingItemIndex ?: return
        val hovered = draggingItemLayoutInfo ?: return
        val startOffset = hovered.offset + draggingItemOffset
        val endOffset = hovered.offset + hovered.size + draggingItemOffset
        val middleOffset = startOffset + (endOffset - startOffset) / 2f

        val target =
            state.layoutInfo.visibleItemsInfo.find { item ->
                middleOffset.toInt() in item.offset..(item.offset + item.size) && item.index != currentIndex
            }
        if (target != null) {
            onMove(currentIndex, target.index)
            draggingItemIndex = target.index
        }

        // Auto-scroll the list when dragging near its top/bottom edge. Capped at scrollEdgeSize
        // per tick (not left to grow with how far past the edge the finger has travelled) and
        // gated on there actually being somewhere left to scroll to. Without both, holding the
        // drag at the very first/last item keeps requesting an ever-larger, pointless scroll on
        // every single onDrag call - which, fighting the swap-target search below over the same
        // still-settling layoutInfo, is what produces the heavy jitter right at the list's edge.
        val viewportStart = state.layoutInfo.viewportStartOffset
        val viewportEnd = state.layoutInfo.viewportEndOffset
        val scrollEdgeSize = (viewportEnd - viewportStart) / 6f
        val distanceFromEnd = viewportEnd - endOffset
        val distanceFromStart = startOffset - viewportStart
        val overscroll =
            when {
                distanceFromEnd < scrollEdgeSize && state.canScrollForward -> {
                    (scrollEdgeSize - distanceFromEnd).coerceAtMost(scrollEdgeSize)
                }

                distanceFromStart < scrollEdgeSize && state.canScrollBackward -> {
                    -(scrollEdgeSize - distanceFromStart).coerceAtMost(scrollEdgeSize)
                }

                else -> {
                    0f
                }
            }
        if (overscroll != 0f) {
            scope.launch { scrollChannel.trySend(overscroll) }
        }
    }
}

@Composable
fun rememberDragDropState(
    lazyListState: LazyListState,
    onDragStopped: () -> Unit = {},
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): DragDropState {
    val scope = rememberCoroutineScope()
    val state = remember(lazyListState) { DragDropState(lazyListState, scope, onMove, onDragStopped) }
    LaunchedEffect(state) {
        while (true) {
            val diff = state.scrollChannel.receive()
            lazyListState.scrollBy(diff)
        }
    }
    return state
}

/** Attaches the long-press-drag gesture; apply to the same node the list items are laid out in. */
fun Modifier.dragContainer(dragDropState: DragDropState): Modifier =
    pointerInput(dragDropState) {
        detectDragGesturesAfterLongPress(
            onDrag = { change, offset ->
                change.consume()
                dragDropState.onDrag(offset)
            },
            onDragStart = { offset -> dragDropState.onDragStart(offset) },
            onDragEnd = { dragDropState.onDragEnd() },
            onDragCancel = { dragDropState.onDragEnd() },
        )
    }
