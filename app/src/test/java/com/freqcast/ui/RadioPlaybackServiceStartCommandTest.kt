package com.freqcast.ui

import android.content.Intent
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import com.freqcast.ui.playback.PlaybackStateStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers [RadioPlaybackService.onStartCommand]'s dispatch, separately from the browse-tree/ticker/
 * connection-error paths the other `RadioPlaybackService*Test` classes already own: which of its
 * four branches (explicit stop, a new stream request, a stray no-extras intent while already
 * playing, or a null intent after process death) fires for a given intent, and what it does.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioPlaybackServiceStartCommandTest {
    // Same MediaSession-uniqueness teardown requirement as RadioPlaybackServiceAutoTest/
    // RadioPlaybackServiceConnectionErrorTest/RadioPlaybackServiceTickerTest.
    private var service: RadioPlaybackService? = null

    // AppDatabase.getDatabase caches its Room instance for the process lifetime (same hazard the
    // other RadioPlaybackService*Test classes document) - the *first* test in the whole suite to
    // touch it pays for the full v1->v12 migration chain synchronously. Run over runBlocking (no
    // timeout) here in @Before, not inside a test's own timing-sensitive awaitStationName poll -
    // otherwise, run standalone/first, a slow cold migration can outlast that poll's short window
    // and fail the test on pure timing, independent of onStartCommand's own behavior.
    @Before
    fun warmUpDatabase() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking { AppDatabase.getDatabase(context).radioStationDao().getAllStations() }
    }

    @After
    fun tearDown() {
        service?.onDestroy()
    }

    private fun awaitStationName(
        service: RadioPlaybackService,
        expected: String?,
        timeoutMs: Long = 2_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (service.getCurrentStationName() != expected && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(expected, service.getCurrentStationName())
    }

    @Test
    fun `ACTION_STOP stops playback and returns START_NOT_STICKY`() {
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service
        val streamUrl = "https://example.com/stop-test.m3u8"
        service.onStartCommand(
            Intent().apply {
                putExtra(RadioPlaybackService.EXTRA_STREAM_URL, streamUrl)
                putExtra(RadioPlaybackService.EXTRA_STATION_NAME, "Stop Test FM")
            },
            0,
            1,
        )
        awaitStationName(service, "Stop Test FM")

        val result = service.onStartCommand(Intent(RadioPlaybackService.ACTION_STOP), 0, 2)

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertFalse(service.playbackSnapshot.value.isPlaying)
    }

    @Test
    fun `a stream url intent looks up the station and starts it playing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        val streamUrl = "https://example.com/new-request.m3u8"
        runBlocking {
            dao.getAllStations().forEach { dao.deleteStation(it.id) }
            dao.insertStation(RadioStation(name = "Db Name", streamUrl = streamUrl, sortOrder = 0))
        }
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        val result =
            service.onStartCommand(
                Intent().apply {
                    putExtra(RadioPlaybackService.EXTRA_STREAM_URL, streamUrl)
                    putExtra(RadioPlaybackService.EXTRA_STATION_NAME, "Intent Name")
                },
                0,
                1,
            )

        assertEquals(android.app.Service.START_STICKY, result)
        // The intent's own station name is what actually gets used, not the DB row's - only the
        // known-HLS hint/uuid/customIcon are looked up by URL (see onStartCommand's doc).
        awaitStationName(service, "Intent Name")
        assertEquals(streamUrl, service.getPlayer()?.currentMediaItem?.mediaId)
    }

    @Test
    fun `a stray no-extras intent while already playing is ignored`() {
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service
        val streamUrl = "https://example.com/already-playing.m3u8"
        service.onStartCommand(
            Intent().apply {
                putExtra(RadioPlaybackService.EXTRA_STREAM_URL, streamUrl)
                putExtra(RadioPlaybackService.EXTRA_STATION_NAME, "Already Playing FM")
            },
            0,
            1,
        )
        awaitStationName(service, "Already Playing FM")
        assertTrue(service.getPlayer()?.playbackState != Player.STATE_IDLE)

        // A plain explicit-component intent, same shape a stray/duplicate delivery would have -
        // must not restart or otherwise disturb what's already playing.
        val result = service.onStartCommand(Intent(), 0, 2)

        assertEquals(android.app.Service.START_STICKY, result)
        assertEquals("Already Playing FM", service.getCurrentStationName())
        assertEquals(streamUrl, service.getPlayer()?.currentMediaItem?.mediaId)
    }

    @Test
    fun `a null intent with nothing to restore stops the service`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PlaybackStateStore(context).clear()
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        val result = service.onStartCommand(null, 0, 1)

        assertEquals(android.app.Service.START_STICKY, result)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(service.getCurrentStationName())
    }

    @Test
    fun `a null intent with saved state resumes the last station after a process restart`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val streamUrl = "https://example.com/resumed.m3u8"
        PlaybackStateStore(context).save(stationName = "Resumed FM", streamUrl = streamUrl)
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        val result = service.onStartCommand(null, 0, 1)

        assertEquals(android.app.Service.START_STICKY, result)
        assertFalse(shadowOf(service).isStoppedBySelf)
        awaitStationName(service, "Resumed FM")
        assertEquals(streamUrl, service.getPlayer()?.currentMediaItem?.mediaId)
    }
}
