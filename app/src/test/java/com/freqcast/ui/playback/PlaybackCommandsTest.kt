package com.freqcast.ui.playback

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.freqcast.ui.RadioPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlaybackCommandsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a start intent round-trips its station name and stream url`() {
        val intent = PlaybackCommands.startIntent(context, "Jazz FM", "https://example.com/jazz")

        assertEquals(RadioPlaybackService::class.java.name, intent.component?.className)
        assertEquals(
            PlaybackCommands.Command.Start("Jazz FM", "https://example.com/jazz"),
            PlaybackCommands.parse(intent),
        )
    }

    @Test
    fun `a start intent keeps a null station name null`() {
        val intent = PlaybackCommands.startIntent(context, null, "https://example.com/jazz")

        assertEquals(PlaybackCommands.Command.Start(null, "https://example.com/jazz"), PlaybackCommands.parse(intent))
    }

    @Test
    fun `a stop intent parses as Stop`() {
        assertEquals(PlaybackCommands.Command.Stop, PlaybackCommands.parse(PlaybackCommands.stopIntent(context)))
    }

    @Test
    fun `a null or extra-less intent is no command`() {
        // Both mean "not a command" to onStartCommand, which then decides between a process-death
        // resume and ignoring a stray delivery.
        assertNull(PlaybackCommands.parse(null))
        assertNull(PlaybackCommands.parse(Intent()))
    }
}
