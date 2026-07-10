package com.urlradiodroid.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioBrowserApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RadioBrowserApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = RadioBrowserApi(baseUrl = server.url("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search parses stations from a successful response`() =
        runTest {
            val body =
                """
                [
                  {
                    "stationuuid": "abc-123",
                    "name": "Test FM",
                    "url": "http://example.com/stream",
                    "url_resolved": "http://resolved.example.com/stream",
                    "country": "Germany",
                    "tags": "pop,talk",
                    "bitrate": 128
                  }
                ]
                """.trimIndent()
            server.enqueue(MockResponse().setBody(body))

            val results = api.search("test", RadioBrowserApi.SearchBy.NAME)

            assertEquals(1, results.size)
            val station = results[0]
            assertEquals("abc-123", station.uuid)
            assertEquals("Test FM", station.name)
            assertEquals("http://resolved.example.com/stream", station.url)
            assertEquals("Germany", station.country)
            assertEquals("pop,talk", station.tags)
            assertEquals(128, station.bitrate)
        }

    @Test
    fun `search falls back to url when url_resolved is blank`() =
        runTest {
            val body = """[{"name":"No Resolved","url":"http://example.com/a","url_resolved":""}]"""
            server.enqueue(MockResponse().setBody(body))

            val results = api.search("x", RadioBrowserApi.SearchBy.NAME)

            assertEquals("http://example.com/a", results[0].url)
        }

    @Test
    fun `search skips entries missing a name or url`() =
        runTest {
            val body =
                """
                [
                  {"name":"","url":"http://example.com/a"},
                  {"name":"No URL","url":""},
                  {"name":"Valid","url":"http://example.com/valid"}
                ]
                """.trimIndent()
            server.enqueue(MockResponse().setBody(body))

            val results = api.search("x", RadioBrowserApi.SearchBy.NAME)

            assertEquals(1, results.size)
            assertEquals("Valid", results[0].name)
        }

    @Test
    fun `search sends the query under the requested search-by param`() =
        runTest {
            server.enqueue(MockResponse().setBody("[]"))

            api.search("jazz", RadioBrowserApi.SearchBy.TAG)

            val request = server.takeRequest()
            assertTrue(request.path?.startsWith("/json/stations/search") == true)
            assertTrue(request.path?.contains("tag=jazz") == true)
        }

    @Test(expected = IOException::class)
    fun `search throws on a non-2xx response`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            api.search("x", RadioBrowserApi.SearchBy.NAME)
        }
}
