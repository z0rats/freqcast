package com.freqcast.testing

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Real-time poll for work that hops onto a real background dispatcher (OkHttp/`Dispatchers.IO`)
 * and then resumes on `Dispatchers.Main`: `ComposeTestRule.waitUntil` doesn't reliably drain
 * Robolectric's paused main looper between checks, so that continuation can sit there unrun. This
 * idles the looper (advancing its clock) and settles composition on every pass instead.
 */
fun ComposeContentTestRule.awaitCondition(
    timeoutMs: Long = 5_000,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        // idleFor (not idle): also advances Robolectric's paused clock, so a `delay()` on Main
        // (e.g. a search debounce) elapses between checks.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        waitForIdle()
        if (condition()) return
        check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
        Thread.sleep(20)
    }
}
