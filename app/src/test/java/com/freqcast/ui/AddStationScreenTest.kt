package com.freqcast.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteraction
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
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.StationUrlResolver
import com.freqcast.testing.awaitCondition
import com.freqcast.testing.composeHostRuleChain
import com.freqcast.testing.inMemoryDatabase
import com.freqcast.util.EmojiGenerator
import com.freqcast.util.StreamValidator
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
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
import org.robolectric.shadows.ShadowToast
import java.net.InetAddress

/**
 * [AddStationScreen] over a real [AddStationViewModel], in-memory Room and a real
 * [StationUrlResolver] pointed at a [MockWebServer] (the WebView stage disabled): typing a form,
 * saving, and every user-visible outcome - success, validation/resolve errors with their report
 * links, edit mode, the icon picker and the ambiguous-directory candidate sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AddStationScreenTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository
    private lateinit var server: MockWebServer
    private val loopbackClient = OkHttpClient.Builder().dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()

    /** Path -> response; anything else answers as a plain audio stream. */
    private val routes = mutableMapOf<String, MockResponse>()
    private var savedCount = 0
    private var backCount = 0

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = RadioStationRepository(database.radioStationDao())
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    routes[request.requestUrl!!.encodedPath]
                        ?: MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/mpeg")
            }
        server.start()
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    private fun show(editingId: Long? = null) {
        val validator = StreamValidator(client = loopbackClient)
        val viewModel =
            AddStationViewModel(
                repository = repository,
                editingStationId = editingId,
                appContext = RuntimeEnvironment.getApplication(),
                streamValidator = validator,
                stationUrlResolver =
                    StationUrlResolver(
                        radioBrowserApi = RadioBrowserApi(baseUrl = server.url("/")),
                        streamValidator = validator,
                        client = loopbackClient,
                        webViewSniff = null,
                    ),
                checkVpnActive = { false },
            )
        composeTestRule.setContent {
            AddStationScreen(viewModel = viewModel, onSaveSuccess = { savedCount++ }, onBackClick = { backCount++ })
        }
        composeTestRule.waitForIdle()
    }

    private fun field(labelRes: Int): SemanticsNodeInteraction =
        composeTestRule.onNode(hasSetTextAction() and hasText(context.getString(labelRes)))

    private fun type(
        labelRes: Int,
        value: String,
    ) = field(labelRes).performTextReplacement(value)

    private fun save() {
        composeTestRule.onNodeWithText(context.getString(R.string.save)).performScrollTo().performClick()
    }

    private fun awaitText(text: String) =
        composeTestRule.awaitCondition {
            composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun stations() = runBlocking { repository.getAllStations() }

    @Test
    fun `saving a direct stream stores it with the typed fields and confirms`() {
        show()
        composeTestRule.onNodeWithText(context.getString(R.string.add_station)).assertExists()
        type(R.string.station_name, "My Jazz")
        type(R.string.stream_url, server.url("/live.mp3").toString())
        type(R.string.station_description, "late night")

        save()
        composeTestRule.awaitCondition { savedCount == 1 }

        val saved = stations().single()
        assertEquals("My Jazz", saved.name)
        assertEquals(server.url("/live.mp3").toString(), saved.streamUrl)
        assertEquals("late night", saved.description)
        assertEquals(context.getString(R.string.station_saved), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `blank url asks for one`() {
        show()
        type(R.string.station_name, "My Jazz")
        save()
        awaitText(context.getString(R.string.enter_url))
        assertTrue(stations().isEmpty())
    }

    @Test
    fun `duplicate name is flagged on the name field`() {
        runBlocking { repository.insertStation(RadioStation(name = "Taken", streamUrl = "https://other")) }
        show()
        type(R.string.station_name, "Taken")
        type(R.string.stream_url, server.url("/live.mp3").toString())
        save()
        awaitText(context.getString(R.string.error_duplicate_name))
        assertEquals(1, stations().size)
    }

    @Test
    fun `an unresolvable url shows the unreachable error with report links prefilled with that url`() {
        routes["/"] = MockResponse().setHeader("Content-Type", "text/html").setBody("<html>no player here</html>")
        routes["/json/stations/search"] = MockResponse().setBody("[]")
        show()
        val url = "http://myradio.test:${server.port}/"
        type(R.string.stream_url, url)
        save()
        awaitText(context.getString(R.string.error_stream_unreachable))

        composeTestRule.onNodeWithText(context.getString(R.string.addstation_report_github)).performClick()
        val github = shadowOf(context as Application).nextStartedActivity
        assertTrue(github.data!!.getQueryParameter("body")!!.contains(url))

        composeTestRule.onNodeWithText(context.getString(R.string.addstation_report_email)).performClick()
        val email = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, email.action)
        assertTrue(email.getStringExtra(Intent.EXTRA_TEXT)!!.contains(url))
    }

    @Test
    fun `editing prefills the form and saving updates the same row`() {
        val id =
            runBlocking {
                repository.insertStation(
                    RadioStation(name = "Old", streamUrl = server.url("/a.mp3").toString()),
                )
            }
        show(editingId = id)
        composeTestRule.onNodeWithText(context.getString(R.string.edit_station)).assertExists()
        composeTestRule.onNodeWithText("Old").assertExists()

        type(R.string.station_name, "New")
        save()
        composeTestRule.awaitCondition { savedCount == 1 }

        assertEquals(listOf(id to "New"), stations().map { it.id to it.name })
        assertEquals(context.getString(R.string.station_updated), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `picking an emoji icon shows it and is saved, removing it restores the auto icon`() {
        show()
        val autoEmoji = EmojiGenerator.getEmojiForStation("", "")
        val emoji = EmojiGenerator.pickerEmojis.first { it != autoEmoji }
        composeTestRule.onNodeWithText(autoEmoji).performClick()
        composeTestRule.onNodeWithText(emoji).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.choose_emoji)).assertDoesNotExist()
        composeTestRule.onNodeWithText(emoji).assertExists()

        composeTestRule.onNodeWithText(emoji).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.remove_icon)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.remove_icon)).assertDoesNotExist()
        composeTestRule.onNodeWithText(autoEmoji).assertExists()

        composeTestRule.onNodeWithText(autoEmoji).performClick()
        composeTestRule.onNodeWithText(emoji).performClick()
        type(R.string.stream_url, server.url("/live.mp3").toString())
        save()
        composeTestRule.awaitCondition { savedCount == 1 }
        assertEquals(emoji, stations().single().customIcon)
    }

    @Test
    fun `several directory stations sharing the homepage open the picker, and picking one saves it`() {
        val homepage = "http://myradio.test:${server.port}/"
        routes["/"] = MockResponse().setHeader("Content-Type", "text/html")
        routes["/json/stations/search"] =
            MockResponse().setBody(
                """
                [{"name":"Nova Paris","url":"${server.url("/paris.mp3")}","homepage":"$homepage"},
                 {"name":"Nova Lyon","url":"${server.url("/lyon.mp3")}","homepage":"$homepage"}]
                """.trimIndent(),
            )
        show()
        type(R.string.stream_url, homepage)
        save()
        awaitText(context.getString(R.string.candidate_picker_title))

        composeTestRule.onNodeWithText("Nova Lyon").performClick()
        composeTestRule.awaitCondition { savedCount == 1 }
        assertEquals(server.url("/lyon.mp3").toString(), stations().single().streamUrl)
    }

    @Test
    fun `dismissing the candidate picker saves nothing`() {
        val homepage = "http://myradio.test:${server.port}/"
        routes["/"] = MockResponse().setHeader("Content-Type", "text/html")
        routes["/json/stations/search"] =
            MockResponse().setBody(
                """[{"name":"A","url":"${server.url(
                    "/a.mp3",
                )}","homepage":"$homepage"},{"name":"B","url":"${server.url("/b.mp3")}","homepage":"$homepage"}]""",
            )
        show()
        type(R.string.stream_url, homepage)
        save()
        awaitText(context.getString(R.string.candidate_picker_title))

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(context.getString(R.string.candidate_picker_title)).assertDoesNotExist()
        assertTrue(stations().isEmpty())
        assertEquals(0, savedCount)
    }

    @Test
    fun `back button calls back`() {
        show()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals(1, backCount)
    }
}
