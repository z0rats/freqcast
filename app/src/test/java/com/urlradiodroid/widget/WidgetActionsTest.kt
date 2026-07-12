package com.urlradiodroid.widget

import android.app.Application
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.test.core.app.ApplicationProvider
import com.urlradiodroid.data.AppDatabase
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.ui.RadioPlaybackService
import com.urlradiodroid.ui.playback.WidgetStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Marker id, same idea as Glance's own internal fakes — [GlanceId] carries no data. */
private val fakeGlanceId = object : GlanceId {}

/**
 * Covers what happens when the user taps a widget button: each `ActionCallback` is a plain class
 * with a public suspend `onAction`, so it's exercised directly here (real [WidgetStateStore] /
 * [RadioStationRepository][com.urlradiodroid.data.RadioStationRepository], real service intents
 * via Robolectric's shadow), the same "drive the real component" style as `AlarmReceiverTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WidgetActionsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val widgetStateStore = WidgetStateStore(context)

    @Test
    fun `toggle stops playback when the widget shows a station currently playing`() {
        widgetStateStore.save(stationName = "Jazz FM", streamUrl = "https://example.com/jazz", isPlaying = true)

        runBlocking { TogglePlaybackAction().onAction(context, fakeGlanceId, actionParametersOf()) }

        val started = shadowOf(context as Application).nextStartedService
        assertEquals(RadioPlaybackService.ACTION_STOP, started?.action)
    }

    @Test
    fun `toggle resumes the last station when the widget shows it paused`() {
        widgetStateStore.save(stationName = "Jazz FM", streamUrl = "https://example.com/jazz", isPlaying = false)

        runBlocking { TogglePlaybackAction().onAction(context, fakeGlanceId, actionParametersOf()) }

        val started = shadowOf(context as Application).nextStartedService
        assertEquals("Jazz FM", started?.getStringExtra(RadioPlaybackService.EXTRA_STATION_NAME))
        assertEquals("https://example.com/jazz", started?.getStringExtra(RadioPlaybackService.EXTRA_STREAM_URL))
    }

    @Test
    fun `toggle does nothing when no station has ever played`() {
        runBlocking { TogglePlaybackAction().onAction(context, fakeGlanceId, actionParametersOf()) }

        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `next and previous start the neighboring station relative to what's currently showing`() {
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        runBlocking {
            // AppDatabase.getDatabase caches its Room instance for the process lifetime, so this
            // clears out anything a previous test in the same JVM left behind (see
            // RadioPlaybackServiceAutoTest, which documents the same hazard in more detail).
            dao.getAllStations().forEach { dao.deleteStation(it.id) }
            dao.insertStation(RadioStation(name = "Jazz FM", streamUrl = "https://example.com/jazz"))
            dao.insertStation(RadioStation(name = "Rock FM", streamUrl = "https://example.com/rock"))
            dao.insertStation(RadioStation(name = "Classical FM", streamUrl = "https://example.com/classical"))
        }
        widgetStateStore.save(stationName = "Rock FM", streamUrl = "https://example.com/rock", isPlaying = true)

        runBlocking { NextStationAction().onAction(context, fakeGlanceId, actionParametersOf()) }

        val startedNext = shadowOf(context as Application).nextStartedService
        assertEquals("Classical FM", startedNext?.getStringExtra(RadioPlaybackService.EXTRA_STATION_NAME))
        assertEquals(
            "https://example.com/classical",
            startedNext?.getStringExtra(RadioPlaybackService.EXTRA_STREAM_URL),
        )

        runBlocking { PreviousStationAction().onAction(context, fakeGlanceId, actionParametersOf()) }

        val startedPrevious = shadowOf(context as Application).nextStartedService
        assertEquals("Jazz FM", startedPrevious?.getStringExtra(RadioPlaybackService.EXTRA_STATION_NAME))
        assertEquals("https://example.com/jazz", startedPrevious?.getStringExtra(RadioPlaybackService.EXTRA_STREAM_URL))
    }
}
