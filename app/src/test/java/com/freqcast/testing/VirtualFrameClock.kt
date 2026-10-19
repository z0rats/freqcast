package com.freqcast.testing

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope

/**
 * A [MonotonicFrameClock] ticking at ~60fps in `runTest`'s virtual time, so an
 * `Animatable.animateTo` launched outside of a composition (which otherwise throws for want of a
 * frame clock in its coroutine context) runs to completion under `advanceUntilIdle()` without any
 * real waiting. Add it to the scope a state holder launches its animations on:
 * `CoroutineScope(coroutineContext + VirtualFrameClock(this))`.
 */
class VirtualFrameClock(
    private val testScope: TestScope,
) : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        delay(FRAME_MS)
        return onFrame(testScope.testScheduler.currentTime * 1_000_000)
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
