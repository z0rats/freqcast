package com.freqcast.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.data.AppDatabase
import com.freqcast.data.CuratedStations
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.UpdateChecker
import com.freqcast.testing.FakeActivityResultRegistry
import com.freqcast.testing.composeHostRuleChain
import com.freqcast.testing.inMemoryDatabase
import com.freqcast.ui.playback.SettingsStore
import com.freqcast.ui.playback.TimeshiftBufferSize
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * [SettingsScreen] over a real [SettingsViewModel] + in-memory Room + real [SettingsStore]:
 * preferences persist, import/restore change the station table, support links open the right
 * intents, and the footer reflects the update check. Export-with-data isn't driven here - it goes
 * through `FileProvider`, and `StationShareTest` must stay the suite's one FileProvider test (see
 * AGENTS.md's Testing section).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsScreenTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository
    private lateinit var server: MockWebServer
    private val settingsStore by lazy { SettingsStore(context) }
    private val registry = FakeActivityResultRegistry()
    private var backCount = 0

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = RadioStationRepository(database.radioStationDao())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    private fun show(
        checkForUpdates: Boolean = false,
        currentVersion: String = "3.0.0",
    ) {
        val viewModel =
            SettingsViewModel(
                repository,
                settingsStore,
                currentVersion,
                UpdateChecker(releaseUrl = server.url("/").toString()),
                checkForUpdates,
            )
        composeTestRule.setContent {
            registry.Provide { SettingsScreen(viewModel = viewModel, onBackClick = { backCount++ }) }
        }
        composeTestRule.waitForIdle()
    }

    private fun click(textRes: Int) {
        composeTestRule.onNodeWithText(context.getString(textRes)).performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }

    private fun awaitToast(): String {
        composeTestRule.waitUntil(5_000) { ShadowToast.getLatestToast() != null }
        return ShadowToast.getTextOfLatestToast()
    }

    private fun stationNames() = runBlocking { repository.getAllStations().map { it.name } }

    @Test
    fun `metered warning switch persists both ways`() {
        settingsStore.warnOnMeteredConnection = false
        show()
        val toggle = composeTestRule.onNode(isToggleable())
        toggle.assertIsOff()
        toggle.performClick()
        toggle.assertIsOn()
        assertTrue(settingsStore.warnOnMeteredConnection)
        toggle.performClick()
        assertEquals(false, settingsStore.warnOnMeteredConnection)
    }

    @Test
    fun `timeshift buffer size menu persists the picked size`() {
        show()
        composeTestRule
            .onNodeWithText(
                context.getString(R.string.settings_timeshift_buffer_size_short, TimeshiftBufferSize.DEFAULT.mb),
            ).performClick()
        val large = TimeshiftBufferSize.LARGE
        composeTestRule
            .onNodeWithText(
                context.getString(R.string.settings_timeshift_buffer_size_value, large.mb, large.approxMinutes),
            ).performClick()
        composeTestRule.waitForIdle()

        assertEquals(large.mb, settingsStore.timeshiftBufferSizeMb)
        composeTestRule
            .onNodeWithText(
                context.getString(R.string.settings_timeshift_buffer_size_short, large.mb),
            ).assertExists()
    }

    @Test
    fun `language menu applies the picked app locale, and system default clears it`() {
        show()
        click(R.string.settings_language_system_default)
        composeTestRule.onNodeWithText("Русский").performClick()
        composeTestRule.waitForIdle()
        assertEquals("ru", AppCompatDelegate.getApplicationLocales().toLanguageTags())

        composeTestRule.onNodeWithText("Русский").performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.settings_language_system_default)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    @Test
    fun `export with no stations says there is nothing to export`() {
        show()
        click(R.string.export_stations)
        assertEquals(context.getString(R.string.export_stations_empty), awaitToast())
    }

    @Test
    fun `import reads the picked file into the station list and reports the counts`() {
        val backup =
            File(context.cacheDir, "backup.m3u").apply {
                writeText("#EXTM3U\n#EXTINF:-1,Jazz FM\nhttps://jazz.example/s\n")
            }
        registry.resultFor = { if (it is ActivityResultContracts.OpenDocument) Uri.fromFile(backup) else null }
        show()

        click(R.string.import_stations)

        assertEquals(context.getString(R.string.import_result, 1, 0), awaitToast())
        assertEquals(listOf("Jazz FM"), stationNames())
    }

    @Test
    fun `import of an unreadable file reports an error and a cancelled pick does nothing`() {
        registry.resultFor = { Uri.fromFile(File(context.cacheDir, "missing.json")) }
        show()
        click(R.string.import_stations)
        assertEquals(context.getString(R.string.import_error), awaitToast())

        ShadowToast.reset()
        registry.resultFor = { null }
        click(R.string.import_stations)
        composeTestRule.waitForIdle()
        assertEquals(null, ShadowToast.getLatestToast())
        assertTrue(stationNames().isEmpty())
    }

    @Test
    fun `restore curated re-adds the pack and reports how many`() {
        show()
        click(R.string.settings_restore_curated)
        assertEquals(context.getString(R.string.restore_curated_result, CuratedStations.pack.size), awaitToast())
        assertEquals(CuratedStations.pack.size, stationNames().size)
    }

    @Test
    fun `battery optimization row requests the exemption and disappears once granted`() {
        registry.resultFor = {
            // The user allows it in the system dialog, which reports no meaningful result code.
            shadowOf(context.getSystemService(PowerManager::class.java))
                .setIgnoringBatteryOptimizations(context.packageName, true)
            ActivityResult(Activity.RESULT_CANCELED, null)
        }
        show()
        click(R.string.settings_battery_optimization)

        val (contract, input) = registry.launches.single()
        assertTrue(contract is ActivityResultContracts.StartActivityForResult)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, (input as Intent).action)
        composeTestRule.onNodeWithText(context.getString(R.string.settings_battery_optimization)).assertDoesNotExist()
    }

    @Test
    fun `feedback rows open a prefilled github issue and email`() {
        show()
        click(R.string.settings_feedback_github)
        val github = shadowOf(context as Application).nextStartedActivity
        assertEquals("github.com", github.data?.host)
        assertTrue(github.data?.getQueryParameter("body")!!.contains("App version:"))

        click(R.string.settings_feedback_email)
        val email = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, email.action)
        assertEquals(context.getString(R.string.feedback_email_subject), email.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `footer offers a newer release and opens it`() {
        server.enqueue(MockResponse().setBody("""{"tag_name": "v9.0.0", "html_url": "https://example.com/rel/v9"}"""))
        show(checkForUpdates = true)
        composeTestRule.onNodeWithText(context.getString(R.string.settings_version, "3.0.0")).assertExists()
        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText(context.getString(R.string.settings_update_available)),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }

        click(R.string.settings_update_available)
        assertEquals(Uri.parse("https://example.com/rel/v9"), shadowOf(context as Application).nextStartedActivity.data)
    }

    @Test
    fun `footer says up to date when the latest release is ours`() {
        server.enqueue(MockResponse().setBody("""{"tag_name": "v3.0.0", "html_url": "https://example.com/rel/v3"}"""))
        show(checkForUpdates = true)
        composeTestRule.waitUntil(5_000) {
            composeTestRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText(context.getString(R.string.settings_up_to_date)),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun `back button calls back`() {
        show()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals(1, backCount)
    }
}
