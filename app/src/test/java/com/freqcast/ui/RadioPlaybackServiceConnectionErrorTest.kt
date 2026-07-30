package com.freqcast.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Covers [PlaybackSnapshot.connectionErrorAt] - the single signal [PlaybackScreen]/[MainScreen]
 * watch to show the "connection failed" toast. Exercises its three write sites directly through
 * their internal test seams ([RadioPlaybackService.onTimeshiftError],
 * [RadioPlaybackService.handlePlayerError], [RadioPlaybackService.tryResumePlaybackAfterNetworkRestored])
 * rather than a real failing stream connection, same reasoning as [RadioPlaybackServiceAutoTest].
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioPlaybackServiceConnectionErrorTest {
    // MediaSession enforces a unique id per process; each test creates its own service via
    // Robolectric.buildService(...).create() (like RadioPlaybackServiceAutoTest), so it must be
    // torn down afterwards or the next test's session creation fails with "Session ID must be unique".
    private var service: RadioPlaybackService? = null

    @After
    fun tearDown() {
        service?.onDestroy()
    }

    private fun playbackException(errorCode: Int) = PlaybackException("test", null, errorCode)

    /** Makes [com.freqcast.util.isNetworkAvailable] report true for the service's active network. */
    private fun markNetworkAvailable(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(network, capabilities)
    }

    @Test
    fun `timeshift recorder error sets connectionErrorAt and changes on a repeated error`() {
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        service.onTimeshiftError()
        val first = service.playbackSnapshot.value.connectionErrorAt
        assertNotNull(first)

        Thread.sleep(2)
        service.onTimeshiftError()
        val second = service.playbackSnapshot.value.connectionErrorAt
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    @Test
    fun `handlePlayerError sets connectionErrorAt once retries are exhausted, and changes on the next exhaustion`() {
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        // 5 retryable errors back off; the 6th exceeds the retry budget and gives up.
        repeat(5) { service.handlePlayerError(playbackException(2000)) }
        service.handlePlayerError(playbackException(2000))
        val first = service.playbackSnapshot.value.connectionErrorAt
        assertNotNull(first)

        Thread.sleep(2)
        repeat(5) { service.handlePlayerError(playbackException(2000)) }
        service.handlePlayerError(playbackException(2000))
        val second = service.playbackSnapshot.value.connectionErrorAt
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    @Test
    fun `network-restore retry exhaustion sets connectionErrorAt and changes on the next exhaustion`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        runBlocking {
            // Same defensive clear as RadioPlaybackServiceAutoTest - AppDatabase.getDatabase caches
            // its Room instance for the process lifetime.
            dao.getAllStations().forEach { dao.deleteStation(it.id) }
            dao.insertStation(
                RadioStation(name = "Test FM", streamUrl = "https://example.com/test.m3u8", sortOrder = 0),
            )
            service.loadBrowsableStations()
        }
        markNetworkAvailable(context)

        service.playFromBrowseTree("https://example.com/test.m3u8")
        repeat(5) { service.handlePlayerError(playbackException(2000)) }
        service.tryResumePlaybackAfterNetworkRestored()
        val first = service.playbackSnapshot.value.connectionErrorAt
        assertNotNull(first)

        Thread.sleep(2)
        service.playFromBrowseTree("https://example.com/test.m3u8")
        repeat(5) { service.handlePlayerError(playbackException(2000)) }
        service.tryResumePlaybackAfterNetworkRestored()
        val second = service.playbackSnapshot.value.connectionErrorAt
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    /**
     * Covers [RadioPlaybackService.handleRetryDecision]'s `RetryAfter` branch: a retryable error
     * within budget must be deferred via `postDelayed`, not retried inline. Robolectric's main
     * looper is paused by default and never runs posted work (delayed or not) without an explicit
     * `idle()`/`idleFor()` call, so checking the foreground notification identity right after
     * [RadioPlaybackService.handlePlayerError] returns - with no idling in between - proves nothing
     * ran synchronously, regardless of what else (ExoPlayer's own internal handler traffic, etc.)
     * is separately queued on that same looper.
     */
    @Test
    fun `handlePlayerError with retries remaining defers the retry instead of restarting inline`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        runBlocking {
            dao.getAllStations().forEach { dao.deleteStation(it.id) }
            dao.insertStation(
                RadioStation(name = "Test FM", streamUrl = "https://example.com/test.m3u8", sortOrder = 0),
            )
            service.loadBrowsableStations()
        }
        service.playFromBrowseTree("https://example.com/test.m3u8")
        val notificationBefore = shadowOf(service).lastForegroundNotification

        service.handlePlayerError(playbackException(2000))

        assertSame(notificationBefore, shadowOf(service).lastForegroundNotification)
        // Still pending, not given up on - handlePlayerError's RetryAfter branch never touches this.
        assertNull(service.playbackSnapshot.value.connectionErrorAt)
    }

    /**
     * Covers [RadioPlaybackService.handleRetryDecision]'s `RetryNow` branch: with a retry already
     * pending (set by [RadioPlaybackService.onTimeshiftError], same as a recorder I/O failure) and
     * the network back, the network-restored path must restart playback synchronously, unlike the
     * player-error path's deferred `RetryAfter` above - proven the same way, by observing the
     * foreground notification change with no `idle()` call in between.
     */
    @Test
    fun `tryResumePlaybackAfterNetworkRestored with a pending retry restarts inline`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        runBlocking {
            dao.getAllStations().forEach { dao.deleteStation(it.id) }
            dao.insertStation(
                RadioStation(name = "Test FM", streamUrl = "https://example.com/test.m3u8", sortOrder = 0),
            )
            service.loadBrowsableStations()
        }
        markNetworkAvailable(context)
        service.playFromBrowseTree("https://example.com/test.m3u8")
        service.onTimeshiftError()
        val connectionErrorBefore = service.playbackSnapshot.value.connectionErrorAt
        assertNotNull(connectionErrorBefore)
        val notificationBefore = shadowOf(service).lastForegroundNotification

        service.tryResumePlaybackAfterNetworkRestored()

        // A new notification was posted with no idle() call in between, so applyPlayback() ran
        // synchronously inside this call - and connectionErrorAt is untouched, so it wasn't GiveUp.
        assertNotSame(notificationBefore, shadowOf(service).lastForegroundNotification)
        assertEquals(connectionErrorBefore, service.playbackSnapshot.value.connectionErrorAt)
    }

    /**
     * Covers [PlaybackSnapshot.isConnectionBroken]'s lifecycle, driving
     * [com.freqcast.ui.components.PlaybackStatus.ERROR] in the UI - unlike
     * [PlaybackSnapshot.connectionErrorAt], which never resets, this must flip back to false once
     * a new attempt starts, or the mini player would show ERROR forever after the first failure.
     */
    @Test
    fun `isConnectionBroken becomes true on GiveUp and false again on the next applyPlayback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service

        runBlocking {
            dao.getAllStations().forEach { dao.deleteStation(it.id) }
            dao.insertStation(
                RadioStation(name = "Test FM", streamUrl = "https://example.com/test.m3u8", sortOrder = 0),
            )
            service.loadBrowsableStations()
        }
        service.playFromBrowseTree("https://example.com/test.m3u8")
        assertFalse(service.playbackSnapshot.value.isConnectionBroken)

        // 5 retryable errors back off; the 6th exceeds the retry budget and gives up.
        repeat(5) { service.handlePlayerError(playbackException(2000)) }
        service.handlePlayerError(playbackException(2000))
        assertTrue(service.playbackSnapshot.value.isConnectionBroken)

        // A fresh attempt at the same station clears the stale error state.
        service.playFromBrowseTree("https://example.com/test.m3u8")
        assertFalse(service.playbackSnapshot.value.isConnectionBroken)
    }
}
