package com.freqcast.ui.components

import androidx.compose.animation.core.Animatable
import com.freqcast.testing.VirtualFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SwipeRevealState]'s threshold/clamp/settle logic, driven without a composition: animations run
 * on a [VirtualFrameClock] so `animateTo` settles under `advanceUntilIdle()`.
 */
class SwipeRevealStateTest {
    private val maxOffsetPx = -300f

    private fun TestScope.newState(): SwipeRevealState =
        SwipeRevealState(
            offsetAnim = Animatable(0f),
            scope = CoroutineScope(coroutineContext + VirtualFrameClock(this)),
            maxOffsetPx = maxOffsetPx,
        )

    @Test
    fun `drag tracks the finger 1 to 1 and is not revealed before half of max`() =
        runTest {
            val state = newState()
            state.onDrag(-100f)
            advanceUntilIdle()
            assertEquals(-100f, state.offsetPx)
            assertFalse(state.isRevealed)
        }

    @Test
    fun `drag past half of max marks revealed`() =
        runTest {
            val state = newState()
            state.onDrag(-160f)
            advanceUntilIdle()
            assertTrue(state.isRevealed)
        }

    @Test
    fun `drag is clamped between max offset and zero`() =
        runTest {
            val state = newState()
            state.onDrag(-1000f)
            advanceUntilIdle()
            assertEquals(maxOffsetPx, state.offsetPx)

            state.onDrag(5000f)
            advanceUntilIdle()
            assertEquals(0f, state.offsetPx)
            assertFalse(state.isRevealed)
        }

    @Test
    fun `releasing past the threshold settles fully open`() =
        runTest {
            val state = newState()
            state.onDrag(-200f)
            advanceUntilIdle()
            state.onDragEnd()
            advanceUntilIdle()
            assertEquals(maxOffsetPx, state.offsetPx)
            assertTrue(state.isRevealed)
        }

    @Test
    fun `releasing before the threshold bounces back closed`() =
        runTest {
            val state = newState()
            state.onDrag(-100f)
            advanceUntilIdle()
            state.onDragEnd()
            advanceUntilIdle()
            assertEquals(0f, state.offsetPx)
            assertFalse(state.isRevealed)
        }

    @Test
    fun `close from revealed animates back to zero`() =
        runTest {
            val state = newState()
            state.onDrag(-300f)
            advanceUntilIdle()
            state.onDragEnd()
            advanceUntilIdle()
            assertTrue(state.isRevealed)

            state.close()
            assertFalse("isRevealed flips immediately, before the animation ends", state.isRevealed)
            advanceUntilIdle()
            assertEquals(0f, state.offsetPx)
        }

    @Test
    fun `tutorial reveal then close pair round-trips`() =
        runTest {
            val state = newState()
            val clock = VirtualFrameClock(this)
            withContext(clock) { state.revealAnimated() }
            assertTrue(state.isRevealed)
            assertEquals(maxOffsetPx, state.offsetPx)

            withContext(clock) { state.closeAnimated() }
            assertFalse(state.isRevealed)
            assertEquals(0f, state.offsetPx)
        }
}
