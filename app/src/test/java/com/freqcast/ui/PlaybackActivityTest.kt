package com.freqcast.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.ui.playback.startCommandOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowToast

/**
 * [PlaybackActivity]'s intent handling: an App Shortcut's auto-play launch (and a later
 * `onNewIntent` re-launch) starts the requested station through the started-service command,
 * since the service isn't bound yet at that point - Robolectric never completes the bind, which is
 * exactly that "controller == null" path. Without a network it refuses and says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlaybackActivityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val launched = mutableListOf<ActivityController<PlaybackActivity>>()

    // AppCompatDelegate keeps live AppCompatActivities in a static list; one left undestroyed is
    // recreated (and crashes) when a later test in the JVM changes the app locale.
    @After
    fun destroyActivities() {
        launched.forEach { it.pause().stop().destroy() }
    }

    private fun launch(intent: Intent): ActivityController<PlaybackActivity> =
        Robolectric.buildActivity(PlaybackActivity::class.java, intent).setup().also { launched += it }

    private fun setOnline() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val capabilities =
            ShadowNetworkCapabilities.newInstance().also {
                shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
    }

    private fun intentFor(
        name: String,
        url: String,
        autoPlay: Boolean,
    ) = Intent(context, PlaybackActivity::class.java)
        .putExtra(PlaybackActivity.EXTRA_STATION_NAME, name)
        .putExtra(PlaybackActivity.EXTRA_STREAM_URL, url)
        .putExtra(PlaybackActivity.EXTRA_AUTO_PLAY, autoPlay)

    /** The next started-service intent that isn't the bind's own service start. */
    private fun nextStartCommand() =
        generateSequence { shadowOf(context as Application).nextStartedService }
            .mapNotNull { startCommandOf(it) }
            .firstOrNull()

    @Test
    fun `an auto-play launch starts the station right away`() {
        setOnline()
        launch(intentFor("Jazz FM", "https://jazz", autoPlay = true))
        shadowOf(Looper.getMainLooper()).idle()

        val start = nextStartCommand()
        assertEquals("Jazz FM", start?.stationName)
        assertEquals("https://jazz", start?.streamUrl)
    }

    @Test
    fun `a plain launch just shows the station without starting it`() {
        setOnline()
        launch(intentFor("Jazz FM", "https://jazz", autoPlay = false))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(nextStartCommand())
    }

    @Test
    fun `a new auto-play intent switches to and starts the other station`() {
        setOnline()
        val controller =
            launch(intentFor("Jazz FM", "https://jazz", autoPlay = false))

        controller.newIntent(intentFor("Rock FM", "https://rock", autoPlay = true))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("https://rock", nextStartCommand()?.streamUrl)
    }

    @Test
    fun `auto-play without a network refuses with a toast`() {
        launch(intentFor("Jazz FM", "https://jazz", autoPlay = true))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(nextStartCommand())
        assertEquals(context.getString(R.string.error_network), ShadowToast.getTextOfLatestToast())
    }
}
