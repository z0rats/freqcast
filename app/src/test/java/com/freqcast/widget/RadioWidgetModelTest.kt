package com.freqcast.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.ui.PlaybackActivity
import com.freqcast.ui.playback.WidgetStateStore
import com.freqcast.util.EmojiGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [radioWidgetModel]: what the widget shows/opens for each saved-state case. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioWidgetModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `no saved station shows placeholder and opens the player without extras`() {
        val model = radioWidgetModel(context, null)
        assertFalse(model.hasStation)
        assertFalse(model.isPlaying)
        assertEquals(context.getString(R.string.widget_no_station), model.stationName)
        assertEquals(context.getString(R.string.widget_tap_to_browse), model.statusText)
        assertEquals(PlaybackActivity::class.java.name, model.openIntent.component?.className)
        assertNull(model.openIntent.getStringExtra(PlaybackActivity.EXTRA_STREAM_URL))
        assertTrue(model.openIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(model.openIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun `playing station shows its name, playing status and deep-links to it`() {
        val model = radioWidgetModel(context, WidgetStateStore.State("Jazz FM", "https://jazz", isPlaying = true))
        assertTrue(model.hasStation)
        assertTrue(model.isPlaying)
        assertEquals("Jazz FM", model.stationName)
        assertEquals(context.getString(R.string.playing), model.statusText)
        assertEquals("Jazz FM", model.openIntent.getStringExtra(PlaybackActivity.EXTRA_STATION_NAME))
        assertEquals("https://jazz", model.openIntent.getStringExtra(PlaybackActivity.EXTRA_STREAM_URL))
        assertEquals(EmojiGenerator.getEmojiForStation("Jazz FM", "https://jazz"), model.emoji)
    }

    @Test
    fun `paused station shows paused status`() {
        val model = radioWidgetModel(context, WidgetStateStore.State("Jazz FM", "https://jazz", isPlaying = false))
        assertFalse(model.isPlaying)
        assertEquals(context.getString(R.string.paused), model.statusText)
    }

    @Test
    fun `saved url without a name falls back to the placeholder name`() {
        val model = radioWidgetModel(context, WidgetStateStore.State(null, "https://jazz", isPlaying = false))
        assertTrue(model.hasStation)
        assertEquals(context.getString(R.string.widget_no_station), model.stationName)
        assertEquals("https://jazz", model.openIntent.getStringExtra(PlaybackActivity.EXTRA_STREAM_URL))
    }
}
