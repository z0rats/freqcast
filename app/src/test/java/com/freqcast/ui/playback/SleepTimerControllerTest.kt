package com.freqcast.ui.playback

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SleepTimerControllerTest {
    private val handler = Handler(Looper.getMainLooper())

    @Test
    fun `fires onFire after the requested duration`() {
        var fired = false
        val controller = SleepTimerController(handler) { fired = true }

        controller.start(1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
        assertFalse("must not fire before the full minute elapses", fired)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(fired)
    }

    @Test
    fun `cancel prevents a pending timer from firing`() {
        var fired = false
        val controller = SleepTimerController(handler) { fired = true }

        controller.start(1)
        controller.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))

        assertFalse(fired)
        assertNull(controller.endAtMsOrNull())
    }

    @Test
    fun `starting again replaces the previous timer instead of stacking`() {
        var fireCount = 0
        val controller = SleepTimerController(handler) { fireCount++ }

        controller.start(1)
        controller.start(5)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))

        // The first (1-minute) timer must have been cancelled by the second start() - only the
        // 5-minute one is still pending, so nothing has fired yet.
        assertEquals(0, fireCount)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertEquals(1, fireCount)
    }

    @Test
    fun `endAtMsOrNull is null before starting and set while a timer is pending`() {
        val controller = SleepTimerController(handler) {}
        assertNull(controller.endAtMsOrNull())

        val before = System.currentTimeMillis()
        controller.start(10)
        val endAt = controller.endAtMsOrNull()

        assertNotNull(endAt)
        assertTrue(endAt!! >= before + 10 * 60_000L)
    }

    @Test
    fun `cancel without a running timer is a no-op`() {
        var fired = false
        val controller = SleepTimerController(handler) { fired = true }

        controller.cancel()

        assertFalse(fired)
        assertNull(controller.endAtMsOrNull())
    }
}
