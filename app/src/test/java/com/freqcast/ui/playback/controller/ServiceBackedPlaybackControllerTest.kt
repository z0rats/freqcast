package com.freqcast.ui.playback.controller

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import com.freqcast.ui.RadioPlaybackService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.TimeUnit

/**
 * Covers the production [PlaybackController] adapter end to end against a real (Robolectric)
 * [RadioPlaybackService] - same reasoning as [com.freqcast.ui.RadioPlaybackServiceAutoTest] for why
 * a real service instance is used instead of mocking it: [ServiceBackedPlaybackController.toggle]
 * combines the pure [decideToggleAction] decision (already covered by
 * [PlaybackControllerLogicTest]) with the real side effect of issuing a foreground-service start
 * intent, which only exists at this integration seam.
 *
 * The STOP branch of [ServiceBackedPlaybackController.toggle] isn't covered here: it requires
 * [RadioPlaybackService.playbackSnapshot]'s `isPlaying` to actually be true, which - like
 * [com.freqcast.ui.RadioPlaybackServiceConnectionErrorTest]'s choice to drive error states through
 * internal seams instead - doesn't reliably happen under Robolectric without a real decodable audio
 * stream (a fake byte stream never gets ExoPlayer past buffering). The delegation test below uses
 * [RadioPlaybackService.hasTimeshift] instead, which - like
 * [com.freqcast.ui.RadioPlaybackServiceAutoTest] and
 * [com.freqcast.ui.RadioPlaybackServiceTickerTest] - only depends on the real (non-ExoPlayer)
 * [com.freqcast.ui.playback.StreamRecorder] background thread and so is reachable synchronously.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ServiceBackedPlaybackControllerTest {
    private var service: RadioPlaybackService? = null
    private var server: MockWebServer? = null

    @After
    fun tearDown() {
        service?.onDestroy()
        server?.shutdown()
    }

    private fun markNetworkAvailable(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(network, capabilities)
    }

    private fun awaitTrue(
        timeoutMs: Long = 5_000,
        poll: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("condition not met within ${timeoutMs}ms", poll())
    }

    @Test
    fun `toggle starts a new station via a foreground-service intent when network is available`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            markNetworkAvailable(context)
            val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
            this@ServiceBackedPlaybackControllerTest.service = service
            val controller = ServiceBackedPlaybackController(context, service)
            val station = RadioStation(name = "Jazz FM", streamUrl = "https://example.com/jazz.mp3")

            val result = controller.toggle(station)

            assertEquals(ToggleResult.STARTED, result)
            val started = shadowOf(context as Application).nextStartedService
            assertEquals("Jazz FM", started.getStringExtra(RadioPlaybackService.EXTRA_STATION_NAME))
            assertEquals("https://example.com/jazz.mp3", started.getStringExtra(RadioPlaybackService.EXTRA_STREAM_URL))
        }

    @Test
    fun `toggle rejects starting a new station without network, and issues no intent`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            // No markNetworkAvailable() call: Robolectric's default ConnectivityManager has no
            // validated active network, so isNetworkAvailable(context) is false.
            val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
            this@ServiceBackedPlaybackControllerTest.service = service
            val controller = ServiceBackedPlaybackController(context, service)
            val station = RadioStation(name = "Jazz FM", streamUrl = "https://example.com/jazz.mp3")

            val result = controller.toggle(station)

            assertEquals(ToggleResult.NETWORK_UNAVAILABLE, result)
            assertNull(shadowOf(context as Application).nextStartedService)
        }

    @Test
    fun `getCurrentStationName and the seek, sleep-timer and clip-export controls delegate to the bound service`() {
        val mockServer = MockWebServer()
        mockServer.enqueue(
            MockResponse()
                .addHeader("Content-Type", "audio/mpeg")
                .setBody("x".repeat(200_000))
                .throttleBody(8_000, 100, TimeUnit.MILLISECONDS),
        )
        mockServer.start()
        server = mockServer
        val streamUrl = mockServer.url("/stream").toString()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service
        runBlocking {
            dao.getAllStations().forEach { dao.deleteStation(it.id) }
            dao.insertStation(RadioStation(name = "Test FM", streamUrl = streamUrl, sortOrder = 0))
            service.loadBrowsableStations()
        }
        service.playFromBrowseTree(streamUrl)
        awaitTrue { service.hasTimeshift() }
        val controller = ServiceBackedPlaybackController(context, service)

        assertEquals("Test FM", controller.getCurrentStationName())

        controller.setSleepTimer(15)
        assertTrue(service.playbackSnapshot.value.sleepTimerEndAtMs != null)
        controller.cancelSleepTimer()
        assertNull(service.playbackSnapshot.value.sleepTimerEndAtMs)

        // A little real wall-clock time must pass first: offsetFromLiveMs is clamped to what's
        // actually buffered so far (TimeshiftController.bufferedDurationMs reads the real system
        // clock), which is ~0 in the instant right after playFromBrowseTree returns.
        Thread.sleep(50)
        controller.seekBackward(2_000)
        assertTrue(service.playbackSnapshot.value.offsetFromLiveMs > 0L)
        controller.seekToLive()
        assertEquals(0L, service.playbackSnapshot.value.offsetFromLiveMs)
        Thread.sleep(50)
        controller.seekToOffsetFromLive(1_000)
        assertTrue(service.playbackSnapshot.value.offsetFromLiveMs > 0L)

        awaitTrue { controller.currentClipFormat() != null }

        // exportClip's completion callback is posted back via Dispatchers.Main, which under
        // Robolectric is this same test thread's paused looper - a blocking CountDownLatch.await()
        // here would deadlock (nothing left to pump it), so poll instead (awaitTrue calls idle()).
        var exportResult: Boolean? = null
        controller.exportClip(500, tempClipFile()) { exportResult = it }
        awaitTrue { exportResult != null }
        assertEquals(true, exportResult)

        controller.stopPlayback()
        awaitTrue { !service.hasTimeshift() }
    }

    private fun tempClipFile() =
        java.io.File
            .createTempFile("clip", ".mp3")
            .apply { deleteOnExit() }
}
