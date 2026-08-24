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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Drives a long-press-anywhere-then-drag-vertically reorder gesture for a [LazyListState]-backed
 * list (here: [com.freqcast.ui.MainScreen]'s station list). Long-press starts the drag;
 * dragging past a neighboring item's midpoint swaps them via [onMove] (the caller applies this to
 * its own backing list, e.g. [com.freqcast.ui.MainViewModel.moveStation]); releasing calls
 * [onDragEnd].
 *
 * Adapted from the shape widely used for hand-rolled Compose reorderable lists (no dependency
 * pulled in for this, matching the project's low-ceremony style) since `LazyColumn` has no
 * built-in drag-to-reorder as of this Compose BOM.
 *
 * **[onDrag] only accumulates raw finger movement - the swap-target search and auto-scroll live in
 * [settle], called at most once per rendered frame by [rememberDragDropState].** They used to run
 * directly inside [onDrag], which fires once per raw pointer-move sample - on-device that's
 * measurably faster than layout/recomposition can keep up, confirmed via screen recording: several
 * `onDrag` calls landing before a single frame's [state]'s `layoutInfo` had caught up with the
 * *previous* call's [onMove] each independently searched for (and found) a swap target against
 * that same stale snapshot, cascading into multiple wrong-pair swaps per frame - visibly, a
 * completely unrelated station's card would end up highlighted as "the one being dragged", or two
 * cards would show highlighted at once, or a station would jump several list positions on its own
 * while the finger stayed roughly in place. Rate-limiting to one [settle] per frame - the same
 * cadence [state]'s own layout actually updates at - closes that window entirely.
 *
 * **Callers rendering "is this item the one being dragged" (e.g. an elevated `Card`/offset) should
 * key that off [draggingItemKey], not [draggingItemIndex].** [draggingItemKey] is a plain Compose
 * snapshot state written synchronously inside the same [onDragStart]/[settle] calls that reorder the
 * caller's backing list via [onMove] - it's consistent with [draggingItemLayoutInfo] (which already
 * looks the item up by key for the same reason) on every recomposition. [draggingItemIndex] is also
 * kept in sync the same way, but a caller comparing it against an item's position in a list sourced
 * from elsewhere (e.g. a derived/combined `Flow` reaching the composable through its own
 * `collectAsState`) can briefly compare it against a list that hasn't caught up yet - that extra hop
 * lands on a different frame than [draggingItemIndex]'s own synchronous update, so for a frame or two
 * the wrong list item matches the now-stale index. Comparing by [draggingItemKey] instead sidesteps
 * this since the caller only needs to compare against its own already-current item identity, not a
 * second, independently-updating piece of state.
 */
class DragDropState internal constructor(
    private val state: LazyListState,
    private val onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    private val onDragStopped: () -> Unit,
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    private var draggedDistance by mutableFloatStateOf(0f)
    private var draggingItemInitialOffset by mutableFloatStateOf(0f)

    // The dragged item's stable key (its RadioStation.id, from itemsIndexed's `key = { _, station
    // -> station.id }`), not its list index - see draggingItemLayoutInfo's doc for why this matters.
    // Exposed (not private) so callers can key their own "is this the dragged item" checks off it
    // too - see the class doc's note on draggingItemIndex vs draggingItemKey for why that matters.
    var draggingItemKey by mutableStateOf<Any?>(null)
        private set

    /**
     * [draggingItemInitialOffset] is captured once at [onDragStart] and never touched again -
     * looking [draggingItemLayoutInfo] up by the dragged item's own stable key, not by
     * [draggingItemIndex], is what keeps this formula correct across a swap without any further
     * per-swap correction: a key-based lookup finds the *same actual composable* whether or not
     * [state]'s `layoutInfo` has caught up with [onMove]'s list reorder yet, so `item.offset` is
     * always this item's own real (possibly still-stale-for-one-frame, but never *wrong*) position.
     */
    internal val draggingItemOffset: Float
        get() =
            draggingItemLayoutInfo?.let { item ->
                draggingItemInitialOffset + draggedDistance - item.offset
            } ?: 0f

    private val draggingItemLayoutInfo: LazyListItemInfo?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingItemKey }

    fun onDragStart(offset: Offset) {
        state.layoutInfo.visibleItemsInfo
            .firstOrNull { item -> offset.y.toInt() in item.offset..(item.offset + item.size) }
            ?.also {
                draggingItemIndex = it.index
                draggingItemKey = it.key
                draggingItemInitialOffset = it.offset.toFloat()
            }
    }

    fun onDragEnd() {
        val wasDragging = draggingItemIndex != null
        draggingItemIndex = null
        draggingItemKey = null
        draggedDistance = 0f
        draggingItemInitialOffset = 0f
        if (wasDragging) onDragStopped()
    }

    /** Accumulates raw finger movement only - see the class doc for why nothing else happens here. */
    fun onDrag(offset: Offset) {
        draggedDistance += offset.y
    }

    /**
     * Re-evaluates the swap target and auto-scroll against the *current* [draggedDistance] and the
     * latest [state] layout, applying at most one swap and one scroll adjustment. Called once per
     * rendered frame by [rememberDragDropState] - see the class doc for why per-`onDrag`-call was
     * unsafe. Cheap to call every frame regardless of whether a drag is in progress: it's a single
     * null check when [draggingItemIndex] is null, no different from `RadioPlaybackService`'s
     * always-on ticker checking `hasTimeshift()` each second (see AGENTS.md).
     */
    internal suspend fun settle() {
        val currentIndex = draggingItemIndex ?: return
        val hovered = draggingItemLayoutInfo ?: return
        val startOffset = hovered.offset + draggingItemOffset
        val endOffset = hovered.offset + hovered.size + draggingItemOffset

        // A target is only proposed once the dragged card's *leading edge* has fully cleared the
        // neighbor's matching edge - not merely once the card's midpoint overlaps the neighbor's
        // rect at all (a symmetric, hysteresis-free check this used to use). A midpoint check has
        // no memory of which way it last swapped: holding the finger still exactly on a swap
        // boundary lets a couple of pixels of ordinary touch jitter toggle "is the midpoint inside
        // this item's rect" back and forth, re-triggering onMove in alternating directions every
        // single frame - confirmed via screen recording showing the top two items visibly
        // flip-flopping order every 2-3 frames while dragging into the very first slot. Requiring a
        // full edge crossing means reversing needs an equally large move back, the same hysteresis
        // aclassen/ComposeReorderable's ReorderableState.chooseDropItem() (the reference this
        // gesture was adapted from) gets from comparing edges instead of a shared center point.
        val target =
            state.layoutInfo.visibleItemsInfo.find { item ->
                when (item.index) {
                    currentIndex - 1 -> startOffset < item.offset
                    currentIndex + 1 -> endOffset > item.offset + item.size
                    else -> false
                }
            }
        if (target != null) {
            onMove(currentIndex, target.index)
            draggingItemIndex = target.index
        }

        // Auto-scroll the list when dragging near its top/bottom edge. Capped at scrollEdgeSize
        // per tick (not left to grow with how far past the edge the finger has travelled) and
        // gated on there actually being somewhere left to scroll to.
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
            state.scrollBy(overscroll)
        }
    }
}

@Composable
fun rememberDragDropState(
    lazyListState: LazyListState,
    onDragStopped: () -> Unit = {},
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): DragDropState {
    val state = remember(lazyListState) { DragDropState(lazyListState, onMove, onDragStopped) }
    LaunchedEffect(state) {
        while (true) {
            withFrameNanos {}
            state.settle()
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
