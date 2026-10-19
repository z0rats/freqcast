package com.freqcast.appfunctions

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import com.freqcast.ui.playback.PlaybackCommands
import com.freqcast.ui.playback.startCommandOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture

/**
 * Drives the real (KSP-generated) [RadioAppFunctionService] against the real Room database and
 * asserts on the playback service intent it actually fires, same style as `WidgetActionsTest`.
 *
 * The no-match branch isn't covered here: `AppFunctionElementNotFoundException` extends
 * `com.android.extensions.appfunctions.AppFunctionException`, a platform extension library the
 * Robolectric sandbox doesn't have (`NoClassDefFoundError`), and SDK 36 doesn't boot under the
 * current Robolectric/JDK. The lookup itself is covered by [RadioAppFunctionServiceTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioAppFunctionServicePlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var service: RadioAppFunctionService

    @Before
    fun setUp() {
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        runBlocking {
            // AppDatabase caches its instance for the JVM - clear anything a previous test left.
            dao.getAllStations().forEach { dao.deleteStation(it.id) }
            dao.insertStation(RadioStation(name = "Jazz FM", streamUrl = "https://example.com/jazz"))
        }
        service = Robolectric.buildService(RadioAppFunctionService::class.java).create().get()
    }

    /**
     * [block] hops onto `Dispatchers.IO`, where Room and `startForegroundService` post back to the
     * main looper - so a plain `runBlocking` on the (Robolectric) main thread deadlocks. Run it on
     * a background thread instead and keep idling the main looper until it finishes.
     */
    private fun <T> callIdlingMainLooper(block: suspend () -> T): T {
        val future = CompletableFuture.supplyAsync { runBlocking { block() } }
        val deadline = System.currentTimeMillis() + 10_000
        while (!future.isDone) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return future.get()
    }

    @Test
    fun `playStation matches case-insensitively, starts it and reports what it started`() {
        val result = callIdlingMainLooper { service.playStation("jazz fm") }

        assertEquals(PlaybackActionResult(stationName = "Jazz FM", streamUrl = "https://example.com/jazz"), result)
        val started = startCommandOf(shadowOf(context as Application).nextStartedService)
        assertEquals("Jazz FM", started?.stationName)
        assertEquals("https://example.com/jazz", started?.streamUrl)
    }

    @Test
    fun `stopPlayback sends the stop command`() {
        callIdlingMainLooper { service.stopPlayback() }

        assertEquals(
            PlaybackCommands.Command.Stop,
            PlaybackCommands.parse(shadowOf(context as Application).nextStartedService),
        )
    }
}
