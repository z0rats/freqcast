package com.freqcast.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.freqcast.testing.composeHostRuleChain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [DragDropState]'s reorder logic (swap-target search, edge-crossing hysteresis,
 * auto-scroll) directly against a real, laid-out [LazyColumn] under Robolectric, so
 * [DragDropState]'s internal `draggingItemLayoutInfo` lookup sees real item offsets/sizes the way
 * it does on-device - a fake/mocked `LazyListState` can't produce those, they only exist once
 * something has actually been measured and laid out.
 *
 * Deliberately does *not* go through [rememberDragDropState]: its `LaunchedEffect` drives
 * [DragDropState.settle] off an infinite per-frame `withFrameNanos` loop (see that function's
 * doc), which never lets `ComposeTestRule.waitForIdle()` observe the app as idle - it throws
 * `AppNotIdleException` (confirmed by running it). Constructing [DragDropState] directly (its
 * constructor is `internal`, reachable from a test in the same module) and calling [settle]
 * explicitly after each simulated gesture step sidesteps that entirely.
 *
 * See AGENTS.md's Testing section and [DragDropState]'s class doc for why the underlying gesture
 * logic is documented so heavily: it has a real history of frame-timing and hysteresis bugs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DragDropStateTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = composeHostRuleChain(composeTestRule)

    private val itemHeightDp = 50

    private fun layOutList(itemCount: Int = 10): LazyListState {
        lateinit var lazyListState: LazyListState
        composeTestRule.setContent {
            lazyListState = rememberLazyListState()
            LazyColumn(state = lazyListState, modifier = Modifier.height(300.dp)) {
                itemsIndexed((0 until itemCount).toList(), key = { _, item -> item }) { _, _ ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(itemHeightDp.dp),
                    ) {}
                }
            }
        }
        composeTestRule.waitForIdle()
        return lazyListState
    }

    private fun DragDropState.settleNow() {
        composeTestRule.runOnIdle {
            runBlocking { settle() }
        }
    }

    @Test
    fun `onDragStart on the first item captures its index and key`() {
        val lazyListState = layOutList()
        val state = DragDropState(lazyListState, onMove = { _, _ -> }, onDragStopped = {})

        composeTestRule.runOnIdle { state.onDragStart(Offset(10f, 10f)) }

        assertEquals(0, state.draggingItemIndex)
        assertEquals(0, state.draggingItemKey)
    }

    @Test
    fun `onDragEnd clears drag state and notifies the caller`() {
        val lazyListState = layOutList()
        var stopped = false
        val state = DragDropState(lazyListState, onMove = { _, _ -> }, onDragStopped = { stopped = true })
        composeTestRule.runOnIdle { state.onDragStart(Offset(10f, 10f)) }

        composeTestRule.runOnIdle { state.onDragEnd() }

        assertNull(state.draggingItemIndex)
        assertNull(state.draggingItemKey)
        assertTrue(stopped)
    }

    @Test
    fun `onDragEnd without an active drag does not notify the caller`() {
        val lazyListState = layOutList()
        var stopped = false
        val state = DragDropState(lazyListState, onMove = { _, _ -> }, onDragStopped = { stopped = true })

        composeTestRule.runOnIdle { state.onDragEnd() }

        assertFalse(stopped)
    }

    @Test
    fun `dragging past the next item's leading edge swaps them`() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val lazyListState = layOutList()
        val state = DragDropState(lazyListState, onMove = { from, to -> moves.add(from to to) }, onDragStopped = {})
        composeTestRule.runOnIdle { state.onDragStart(Offset(10f, 10f)) }
        assertEquals(0, state.draggingItemIndex)

        // itemHeightDp is 50dp; drag just over one item's height (in px, via density) to fully
        // clear item 1's edge - matches settle()'s "full edge crossing", not a midpoint check.
        val density = composeTestRule.density.density
        val overOneItemPx = (itemHeightDp * density) + 5f
        composeTestRule.runOnIdle { state.onDrag(Offset(0f, overOneItemPx)) }
        state.settleNow()

        assertTrue("expected a swap of (0, 1), got $moves", moves.contains(0 to 1))
        assertEquals(1, state.draggingItemIndex)
    }

    @Test
    fun `a small drag within the same item's bounds does not trigger a swap`() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val lazyListState = layOutList()
        val state = DragDropState(lazyListState, onMove = { from, to -> moves.add(from to to) }, onDragStopped = {})
        composeTestRule.runOnIdle { state.onDragStart(Offset(10f, 10f)) }

        composeTestRule.runOnIdle { state.onDrag(Offset(0f, 5f)) }
        state.settleNow()

        assertTrue("expected no swap, got $moves", moves.isEmpty())
        assertEquals(0, state.draggingItemIndex)
    }

    @Test
    fun `dragging up from the second item swaps it back to the first slot`() {
        val moves = mutableListOf<Pair<Int, Int>>()
        val lazyListState = layOutList()
        val state = DragDropState(lazyListState, onMove = { from, to -> moves.add(from to to) }, onDragStopped = {})
        val density = composeTestRule.density.density
        val itemHeightPx = itemHeightDp * density

        // Start the drag inside item 1 (just below item 0's bottom edge).
        composeTestRule.runOnIdle { state.onDragStart(Offset(10f, itemHeightPx + 5f)) }
        assertEquals(1, state.draggingItemIndex)

        composeTestRule.runOnIdle { state.onDrag(Offset(0f, -(itemHeightPx + 5f))) }
        state.settleNow()

        assertTrue("expected a swap of (1, 0), got $moves", moves.contains(1 to 0))
        assertEquals(0, state.draggingItemIndex)
    }

    @Test
    fun `dragging an item into the bottom edge zone auto-scrolls the list forward`() {
        val lazyListState = layOutList(itemCount = 20)
        val state = DragDropState(lazyListState, onMove = { _, _ -> }, onDragStopped = {})
        val density = composeTestRule.density.density
        composeTestRule.runOnIdle { state.onDragStart(Offset(10f, 10f)) }

        // Viewport is 300dp, so the edge zone is the last 50dp: drag item 0 (0..50dp) to ~230..280dp.
        composeTestRule.runOnIdle { state.onDrag(Offset(0f, 230 * density)) }
        state.settleNow()

        assertTrue(
            "expected a forward scroll, got index=${lazyListState.firstVisibleItemIndex} offset=${lazyListState.firstVisibleItemScrollOffset}",
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0,
        )
    }

    @Test
    fun `dragging an item into the top edge zone auto-scrolls the list back`() {
        val lazyListState = layOutList(itemCount = 20)
        composeTestRule.runOnIdle { runBlocking { lazyListState.scrollToItem(10) } }
        composeTestRule.waitForIdle()
        val state = DragDropState(lazyListState, onMove = { _, _ -> }, onDragStopped = {})
        val density = composeTestRule.density.density
        // Item 12 sits at 100..150dp in the viewport; drag it up into the top 50dp edge zone.
        composeTestRule.runOnIdle { state.onDragStart(Offset(10f, 110 * density)) }
        composeTestRule.runOnIdle { state.onDrag(Offset(0f, -90 * density)) }
        state.settleNow()

        assertTrue(
            "expected a backward scroll from item 10, got ${lazyListState.firstVisibleItemIndex}/${lazyListState.firstVisibleItemScrollOffset}",
            lazyListState.firstVisibleItemIndex < 10,
        )
    }

    @Test
    fun `an item resting mid-list does not auto-scroll`() {
        val lazyListState = layOutList(itemCount = 20)
        val state = DragDropState(lazyListState, onMove = { _, _ -> }, onDragStopped = {})
        val density = composeTestRule.density.density
        composeTestRule.runOnIdle { state.onDragStart(Offset(10f, 110 * density)) }
        composeTestRule.runOnIdle { state.onDrag(Offset(0f, 2 * density)) }
        state.settleNow()

        assertEquals(0, lazyListState.firstVisibleItemIndex)
        assertEquals(0, lazyListState.firstVisibleItemScrollOffset)
    }
}
