package com.freqcast.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Covers the once-a-second timeshift ticker started in [RadioPlaybackService.onCreate] - the
 * single process-wide poller that replaced the per-screen `LaunchedEffect` loop that used to live
 * in [com.freqcast.ui.components.rememberPlaybackPresentation]. Must grow
 * [PlaybackSnapshot.bufferedDurationMs] while timeshift is recording, stay inert with nothing to
 * timeshift, and never write the widget's state (that's [RadioPlaybackService.updateWidget], gated
 * off for the ticker via `refreshSnapshot(updateWidgetToo = false)`).
 *
 * Uses a real [MockWebServer] (throttled, like [com.freqcast.ui.playback.TimeshiftControllerTest])
 * so [com.freqcast.ui.playback.TimeshiftController.bufferedDurationMs] - which reads the real
 * `System.currentTimeMillis()`, not Robolectric's fake scheduler clock - actually grows between
 * ticks. Combines real [Thread.sleep] (to advance that wall clock) with
 * `shadowOf(Looper).idleFor(...)` (to run the coroutine's `delay(1_000)` continuation, queued on
 * Robolectric's paused main looper) each iteration; neither alone is enough.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioPlaybackServiceTickerTest {
    // Same MediaSession-uniqueness teardown requirement as RadioPlaybackServiceAutoTest/
    // RadioPlaybackServiceConnectionErrorTest.
    private var service: RadioPlaybackService? = null
    private var server: MockWebServer? = null

    @After
    fun tearDown() {
        service?.onDestroy()
        server?.shutdown()
    }

    private fun tick() {
        Thread.sleep(1_050)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
    }

    @Test
    fun `ticker grows bufferedDurationMs each second while timeshift is recording, and never writes the widget`() {
        val mockServer = MockWebServer()
        // ~40 KB/s: the 400 KB body takes ~10s to fully deliver, comfortably outlasting this test's
        // few real seconds of ticks, so the recorder stays in isRecording()==true the whole time.
        mockServer.enqueue(
            MockResponse()
                .setBody("x".repeat(400_000))
                .throttleBody(4_000, 100, TimeUnit.MILLISECONDS),
        )
        mockServer.start()
        server = mockServer
        val streamUrl = mockServer.url("/stream").toString()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        runBlocking {
            // Same defensive clear as the other RadioPlaybackService tests - AppDatabase.getDatabase
            // caches its Room instance for the process lifetime.
            dao.getAllStations().forEach { dao.deleteStation(it.id) }
            dao.insertStation(RadioStation(name = "Test FM", streamUrl = streamUrl, sortOrder = 0))
            service.loadBrowsableStations()
        }
        service.playFromBrowseTree(streamUrl)
        assertTrue(service.hasTimeshift())

        // Mirrors WidgetStateStore's own PREFS_NAME (private to that class) - the only way to
        // observe from outside whether it was ever written to.
        val widgetPrefs = context.getSharedPreferences("widget_state", Context.MODE_PRIVATE)
        var widgetWrites = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> widgetWrites++ }
        widgetPrefs.registerOnSharedPreferenceChangeListener(listener)

        val before = service.playbackSnapshot.value.bufferedDurationMs
        repeat(3) { tick() }
        val after = service.playbackSnapshot.value.bufferedDurationMs

        widgetPrefs.unregisterOnSharedPreferenceChangeListener(listener)

        assertTrue("expected bufferedDurationMs to grow: before=$before after=$after", after > before)
        assertEquals("ticker must not write the widget's SharedPreferences", 0, widgetWrites)
    }

    @Test
    fun `ticker leaves the snapshot untouched with no active timeshift`() {
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service
        assertTrue(!service.hasTimeshift())

        val before = service.playbackSnapshot.value
        repeat(3) { tick() }
        val after = service.playbackSnapshot.value

        assertEquals(before, after)
    }
}
