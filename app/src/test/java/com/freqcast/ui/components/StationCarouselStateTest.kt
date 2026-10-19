package com.freqcast.ui.components

import androidx.compose.animation.core.Animatable
import com.freqcast.data.RadioStation
import com.freqcast.testing.VirtualFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StationCarouselState]'s resistance/clamp/switch-threshold physics, driven without a composition
 * (animations run on a [VirtualFrameClock]).
 */
class StationCarouselStateTest {
    private val prev = RadioStation(id = 1, name = "Prev", streamUrl = "https://prev")
    private val next = RadioStation(id = 3, name = "Next", streamUrl = "https://next")

    private val cardWidthPx = 200
    private val gapPx = 10
    private val thresholdPx = 72f
    private val maxDragPx = 180f
    private val overscrollMaxPx = 48f

    private val switched = mutableListOf<RadioStation>()

    private fun TestScope.newState(
        prevStation: RadioStation? = prev,
        nextStation: RadioStation? = next,
    ): StationCarouselState =
        StationCarouselState(
            offsetAnim = Animatable(0f),
            scope = CoroutineScope(coroutineContext + VirtualFrameClock(this)),
            prevStation = prevStation,
            nextStation = nextStation,
            cardWidthPx = cardWidthPx,
            gapPx = gapPx,
            onSwitchStation = { switched += it },
            switchThresholdPx = thresholdPx,
            maxDragPx = maxDragPx,
            overscrollMaxPx = overscrollMaxPx,
        )

    private fun TestScope.drag(
        state: StationCarouselState,
        amount: Float,
    ) {
        state.onDrag(amount)
        advanceUntilIdle()
    }

    @Test
    fun `drag toward an existing neighbor tracks 1 to 1`() =
        runTest {
            val state = newState()
            drag(state, 50f)
            assertEquals(50f, state.offsetPx)
            drag(state, -100f)
            assertEquals(-50f, state.offsetPx)
        }

    @Test
    fun `drag toward a missing neighbor is resisted`() =
        runTest {
            val state = newState(prevStation = null)
            drag(state, 100f)
            assertEquals(30f, state.offsetPx, 0.001f)

            val noNext = newState(nextStation = null)
            drag(noNext, -100f)
            assertEquals(-30f, noNext.offsetPx, 0.001f)
        }

    @Test
    fun `drag with both neighbors is clamped to max drag both ways`() =
        runTest {
            val state = newState()
            drag(state, 1000f)
            assertEquals(maxDragPx, state.offsetPx)
            drag(state, -5000f)
            assertEquals(-maxDragPx, state.offsetPx)
        }

    @Test
    fun `overscroll toward a missing side is clamped to overscroll max`() =
        runTest {
            val onlyPrev = newState(nextStation = null)
            drag(onlyPrev, -1000f)
            assertEquals(-overscrollMaxPx, onlyPrev.offsetPx)
            drag(onlyPrev, 5000f)
            assertEquals(maxDragPx, onlyPrev.offsetPx)

            val onlyNext = newState(prevStation = null)
            drag(onlyNext, 1000f)
            assertEquals(overscrollMaxPx, onlyNext.offsetPx)
            drag(onlyNext, -5000f)
            assertEquals(-maxDragPx, onlyNext.offsetPx)

            val none = newState(prevStation = null, nextStation = null)
            drag(none, 5000f)
            assertEquals(overscrollMaxPx, none.offsetPx)
            drag(none, -50000f)
            assertEquals(-overscrollMaxPx, none.offsetPx)
        }

    @Test
    fun `release past threshold toward prev switches to prev and recenters`() =
        runTest {
            val state = newState()
            drag(state, 100f)
            state.onDragEnd()
            advanceUntilIdle()
            assertEquals(listOf(prev), switched)
            assertEquals(0f, state.offsetPx)
        }

    @Test
    fun `release past threshold toward next switches to next`() =
        runTest {
            val state = newState()
            drag(state, -100f)
            state.onDragEnd()
            advanceUntilIdle()
            assertEquals(listOf(next), switched)
            assertEquals(0f, state.offsetPx)
        }

    @Test
    fun `release below threshold bounces back without switching`() =
        runTest {
            val state = newState()
            drag(state, 50f)
            state.onDragEnd()
            advanceUntilIdle()
            assertTrue(switched.isEmpty())
            assertEquals(0f, state.offsetPx, 0.5f)
        }

    @Test
    fun `overscroll past threshold toward a missing neighbor never switches`() =
        runTest {
            // The overscroll cap (48) is below the threshold (72), but even with resistance the
            // decision must also check the neighbor exists - drive past via a larger cap.
            val state =
                StationCarouselState(
                    offsetAnim = Animatable(0f),
                    scope = CoroutineScope(coroutineContext + VirtualFrameClock(this)),
                    prevStation = null,
                    nextStation = null,
                    cardWidthPx = cardWidthPx,
                    gapPx = gapPx,
                    onSwitchStation = { switched += it },
                    switchThresholdPx = 10f,
                    maxDragPx = maxDragPx,
                    overscrollMaxPx = overscrollMaxPx,
                )
            drag(state, 200f)
            state.onDragEnd()
            advanceUntilIdle()
            drag(state, -200f)
            state.onDragEnd()
            advanceUntilIdle()
            assertTrue(switched.isEmpty())
        }
}
