package com.freqcast.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioBrowserApi
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.RadioTag
import com.freqcast.data.RadioTagRepository
import com.freqcast.testing.FakeActivityResultRegistry
import com.freqcast.testing.awaitCondition
import com.freqcast.testing.composeHostRuleChain
import com.freqcast.testing.inMemoryDatabase
import com.freqcast.util.GenreCatalog
import com.freqcast.util.LocationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [DiscoverStationsScreen] over a real [DiscoverStationsViewModel], in-memory Room and a
 * [MockWebServer] directory that answers each search with a station named after its query
 * (e.g. `name=jazz` -> "Result for name=jazz"), so each test can tell which search actually ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiscoverStationsScreenTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository
    private lateinit var tagRepository: RadioTagRepository
    private lateinit var server: MockWebServer
    private val registry = FakeActivityResultRegistry()
    private var emptySearches = false
    private var backCount = 0

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = RadioStationRepository(database.radioStationDao())
        tagRepository = RadioTagRepository(database.radioTagDao())
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val key =
                        listOf("name", "tag", "country", "geo_lat")
                            .firstNotNullOfOrNull { p -> url.queryParameter(p)?.let { "$p=$it" } }
                            ?: "top"
                    if (emptySearches && key != "top") return MockResponse().setBody("[]")
                    return MockResponse().setBody(
                        """[{"stationuuid":"$key","name":"Result for $key","url":"https://s.example/$key",
                            "countrycode":"DE","tags":"t","bitrate":128}]""",
                    )
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    private fun show(autoLoadDefaultBrowse: Boolean = false) {
        val viewModel =
            DiscoverStationsViewModel(
                repository,
                RuntimeEnvironment.getApplication(),
                RadioBrowserApi(baseUrl = server.url("/")),
                LocationProvider(RuntimeEnvironment.getApplication()),
                tagRepository,
                autoLoadDefaultBrowse,
                false,
            )
        composeTestRule.setContent {
            registry.Provide { DiscoverStationsScreen(viewModel = viewModel, onBackClick = { backCount++ }) }
        }
        composeTestRule.waitForIdle()
    }

    private fun awaitText(text: String) =
        composeTestRule.awaitCondition { composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun chip(labelRes: Int) = composeTestRule.onNodeWithText(context.getString(labelRes))

    private fun searchField() = composeTestRule.onNode(hasSetTextAction())

    @Test
    fun `opens onto a default browse list instead of a bare prompt`() {
        show(autoLoadDefaultBrowse = true)
        // Robolectric's system region is US, which CountryCatalog knows, so this is a country search.
        awaitText("Result for country=United States")
    }

    @Test
    fun `without default browse it prompts to search`() {
        show()
        composeTestRule.onNodeWithText(context.getString(R.string.discover_prompt)).assertExists()
    }

    @Test
    fun `typing a name searches after the debounce, and adding a result saves it once`() {
        show()
        searchField().performTextReplacement("jazz")
        awaitText("Result for name=jazz")

        composeTestRule.onNodeWithText(context.getString(R.string.discover_add)).performClick()
        composeTestRule.awaitCondition { runBlocking { repository.getAllStations() }.isNotEmpty() }
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.discover_added)).assertExists()
        assertEquals(listOf("Result for name=jazz"), runBlocking { repository.getAllStations() }.map { it.name })
    }

    @Test
    fun `a search with no matches says so`() {
        emptySearches = true
        show()
        searchField().performTextReplacement("zzzz")
        awaitText(context.getString(R.string.discover_empty_results))
    }

    @Test
    fun `genre chips search their tag, and typed text offers cached tag suggestions`() {
        runBlocking { tagRepository.replaceAll(listOf(RadioTag("synthwave", 120), RadioTag("synthpop", 80))) }
        show()
        chip(R.string.discover_search_mode_genre).performClick()
        val genre = GenreCatalog.genres.first()
        composeTestRule.onNodeWithText(context.getString(genre.labelRes)).performClick()
        awaitText("Result for tag=${genre.queryTag}")

        searchField().performTextReplacement("synth")
        awaitText("synthwave")
        composeTestRule.onNodeWithText("synthpop").performClick()
        awaitText("Result for tag=synthpop")
    }

    @Test
    fun `country tab searches the tapped flag's country`() {
        show()
        chip(R.string.discover_search_mode_country).performClick()
        composeTestRule.onNodeWithContentDescription("Germany").performScrollTo().performClick()
        awaitText("Result for country=Germany")
    }

    @Test
    fun `nearby without permission explains first, and cancelling shows the denied hint`() {
        show()
        chip(R.string.discover_search_mode_nearby).performClick()
        composeTestRule
            .onNodeWithText(
                context.getString(R.string.discover_location_permission_rationale),
            ).assertExists()

        composeTestRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.discover_location_permission_denied)).assertExists()
        assertTrue(registry.launches.isEmpty())
    }

    @Test
    fun `nearby rationale continue requests the permission, and a refusal shows the denied hint`() {
        registry.resultFor = { false }
        show()
        chip(R.string.discover_search_mode_nearby).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.discover_location_permission_continue)).performClick()
        composeTestRule.waitForIdle()

        val (contract, input) = registry.launches.single()
        assertTrue(contract is ActivityResultContracts.RequestPermission)
        assertEquals(Manifest.permission.ACCESS_COARSE_LOCATION, input)
        composeTestRule.onNodeWithText(context.getString(R.string.discover_location_permission_denied)).assertExists()
    }

    @Test
    fun `nearby with permission searches around the current fix`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(context.getSystemService(LocationManager::class.java)).apply {
            setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            setLastKnownLocation(
                LocationManager.NETWORK_PROVIDER,
                Location(LocationManager.NETWORK_PROVIDER).apply {
                    latitude = 52.5
                    longitude = 13.4
                },
            )
        }
        show()
        chip(R.string.discover_search_mode_nearby).performClick()
        awaitText("Result for geo_lat=52.5")
        assertTrue(registry.launches.isEmpty())
    }

    @Test
    fun `nearby with permission but location services off reports location unavailable`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(context.getSystemService(LocationManager::class.java)).apply {
            setProviderEnabled(LocationManager.GPS_PROVIDER, false)
            setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
            setProviderEnabled(LocationManager.PASSIVE_PROVIDER, false)
        }
        show()
        chip(R.string.discover_search_mode_nearby).performClick()
        awaitText(context.getString(R.string.discover_location_unavailable))
    }

    @Test
    fun `back button calls back`() {
        show()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals(1, backCount)
    }
}
