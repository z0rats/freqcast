package com.freqcast.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.data.AppDatabase
import com.freqcast.data.CuratedStations
import com.freqcast.ui.playback.startCommandOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowToast

/**
 * [MainActivity] end to end: a fresh install seeds the curated pack into the real database, and a
 * play tap that lands before the playback service is bound (Robolectric never completes the bind)
 * takes `playStation`'s fallback - network check, then the started-service command.
 *
 * Paused main clock for the same reason as [MainScreenTest] (`rememberDragDropState`'s endless
 * per-frame loop); semantic clicks because `performScrollTo` can't run with the clock paused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MainActivityTest {
    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val firstCurated = CuratedStations.pack.first()

    @Before
    fun clearDatabase() {
        // AppDatabase.getDatabase caches its instance for the JVM - drop rows other tests left.
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        runBlocking { dao.getAllStations().forEach { dao.deleteStation(it.id) } }
    }

    private fun setOnline() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val capabilities =
            ShadowNetworkCapabilities.newInstance().also {
                shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
    }

    private var scenario: ActivityScenario<MainActivity>? = null

    // AppCompatDelegate keeps live AppCompatActivities in a static list; one left undestroyed is
    // recreated (and crashes) when a later test in the JVM changes the app locale.
    @After
    fun closeActivity() {
        scenario?.close()
    }

    private fun launch() {
        composeTestRule.mainClock.autoAdvance = false
        scenario = ActivityScenario.launch(MainActivity::class.java)
        settleUntil { composeTestRule.onAllNodes(hasText(firstCurated.name)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun settleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            composeTestRule.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun tapPlayOnFirstStation() {
        composeTestRule
            .onAllNodesWithContentDescription(context.getString(R.string.play))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        repeat(5) {
            composeTestRule.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun nextStartCommand() =
        generateSequence { shadowOf(context as Application).nextStartedService }
            .mapNotNull { startCommandOf(it) }
            .firstOrNull()

    @Test
    fun `a fresh install seeds the curated pack and lists it`() {
        launch()
        val names = runBlocking { AppDatabase.getDatabase(context).radioStationDao().getAllStations() }.map { it.name }
        assertEquals(CuratedStations.pack.map { it.name }, names)
        assertTrue(
            composeTestRule
                .onAllNodes(
                    hasContentDescription(context.getString(R.string.play)),
                ).fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    @Test
    fun `play before the service is bound starts the station directly`() {
        setOnline()
        launch()
        tapPlayOnFirstStation()

        val start = nextStartCommand()
        assertEquals(firstCurated.name, start?.stationName)
        assertEquals(firstCurated.streamUrl, start?.streamUrl)
    }

    @Test
    fun `play before the service is bound and without a network refuses with a toast`() {
        launch()
        tapPlayOnFirstStation()

        assertNull(nextStartCommand())
        assertEquals(context.getString(R.string.error_network), ShadowToast.getTextOfLatestToast())
    }
}
