package com.freqcast.ui

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import com.freqcast.util.StationNavigator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers the Android Auto / Assistant browse tree end to end: the station list surfaces as
 * browsable media items in the DB's manual `sortOrder` order, and "tapping" one (the real user
 * scenario: [RadioPlaybackService.playFromBrowseTree] is what
 * `MediaLibrarySessionCallback.onAddMediaItems` calls when a browse-tree item is selected) actually
 * starts that station playing through the real player/session pipeline. Also covers
 * [RadioPlaybackService.switchToAdjacentStation] (the media notification's skip-next/skip-previous
 * buttons, wired through `TimeshiftSeekPlayer.seekToNext`/`seekToPrevious`) end to end against the
 * same seeded station list, since it's the same "resolve a station, start it playing" shape.
 *
 * Goes through [RadioPlaybackService.loadBrowsableStations]/[RadioPlaybackService.playFromBrowseTree]
 * directly rather than a full [androidx.media3.session.MediaBrowser] Binder round trip: media3's own
 * team tests that round trip with instrumentation, not Robolectric, which this project deliberately
 * doesn't have (see Testing in AGENTS.md) — these `internal` methods are the seam, same idea as
 * [RadioPlaybackService.isHlsUrl] just above them in the class.
 *
 * A single test method on purpose: [AppDatabase.getDatabase] caches its Room instance in a
 * companion-object singleton for the process lifetime (see `RadioStationRepository.create`), so a
 * second Robolectric service instance with its own fake files dir would collide with the first
 * call's cached database, same hazard `StationShareTest` documents for `FileProvider`.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioPlaybackServiceAutoTest {
    // MediaSession enforces a unique id per process; leaving it unreleased would collide with any
    // other test in the same JVM that also creates a RadioPlaybackService (e.g.
    // RadioPlaybackServiceConnectionErrorTest).
    private var service: RadioPlaybackService? = null

    @After
    fun tearDown() {
        service?.onDestroy()
    }

    @Test
    fun `browse tree lists stations in sortOrder and tapping one starts it playing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        val browseItems =
            runBlocking {
                // AppDatabase.getDatabase caches its Room instance for the process lifetime (see the
                // class doc), so this clears out anything a previous test in the same JVM left behind.
                dao.getAllStations().forEach { dao.deleteStation(it.id) }
                dao.insertStation(
                    RadioStation(
                        name = "Classical FM",
                        streamUrl = "https://example.com/classical.m3u8",
                        sortOrder = 0,
                    ),
                )
                dao.insertStation(
                    RadioStation(name = "Jazz FM", streamUrl = "https://example.com/jazz.m3u8", sortOrder = 1),
                )
                dao.insertStation(
                    RadioStation(name = "Rock FM", streamUrl = "https://example.com/rock.m3u8", sortOrder = 2),
                )
                service.loadBrowsableStations()
            }

        // Same sortOrder ASC, id ASC ordering as the main station list (RadioStationDao.getAllStations()).
        assertEquals(
            listOf("Classical FM", "Jazz FM", "Rock FM"),
            browseItems.map { it.mediaMetadata.title.toString() },
        )
        assertEquals(
            listOf(
                "https://example.com/classical.m3u8",
                "https://example.com/jazz.m3u8",
                "https://example.com/rock.m3u8",
            ),
            browseItems.map { it.mediaId },
        )
        assertTrue(browseItems.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == false })

        val startedPlaying = service.playFromBrowseTree("https://example.com/jazz.m3u8")

        assertTrue(startedPlaying)
        assertEquals("Jazz FM", service.getCurrentStationName())
        assertEquals("https://example.com/jazz.m3u8", service.getPlayer()?.currentMediaItem?.mediaId)

        // Tapping an unknown id (e.g. a stale browse-tree entry from before a station was deleted)
        // is a no-op: it must not disturb what's already playing.
        val startedUnknown = service.playFromBrowseTree("https://not-a-real-station.example.com/stream")

        assertFalse(startedUnknown)
        assertEquals("Jazz FM", service.getCurrentStationName())

        // Notification skip-next/skip-previous buttons (see TimeshiftSeekPlayer.seekToNext/
        // seekToPrevious) - switchToAdjacentStation fetches the station list via a suspend Room
        // query, a real dispatcher hop off serviceScope's Dispatchers.Main.immediate, so a single
        // idle() isn't reliable; poll with real Thread.sleep + shadowOf(Looper).idle() the same way
        // RadioPlaybackServiceTickerTest does for its own real-hop coroutine.
        service.switchToAdjacentStation(StationNavigator::next)
        awaitStationName(service, "Rock FM")

        service.switchToAdjacentStation(StationNavigator::previous)
        service.switchToAdjacentStation(StationNavigator::previous)
        awaitStationName(service, "Classical FM")
    }

    private fun awaitStationName(
        service: RadioPlaybackService,
        expected: String,
        timeoutMs: Long = 2_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (service.getCurrentStationName() != expected && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(expected, service.getCurrentStationName())
    }
}
