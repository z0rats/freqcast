package com.freqcast.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    "countrycode": "DE",
                    "tags": "pop,talk",
                    "bitrate": 128,
                    "hls": 1,
                    "codec": "MP3",
                    "votes": 1234,
                    "homepage": "https://example.com",
                    "favicon": "https://example.com/favicon.png",
                    "ssl_error": 1
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
            assertEquals("DE", station.countryCode)
            assertEquals("pop,talk", station.tags)
            assertEquals(128, station.bitrate)
            assertTrue(station.hls)
            assertEquals("MP3", station.codec)
            assertEquals(1234, station.votes)
            assertEquals("https://example.com", station.homepage)
            assertEquals("https://example.com/favicon.png", station.favicon)
            assertTrue(station.sslError)
        }

    @Test
    fun `search defaults sslError to false when the field is absent`() =
        runTest {
            val body = """[{"name":"No Ssl Error Field","url":"http://example.com/a"}]"""
            server.enqueue(MockResponse().setBody(body))

            val results = api.search("x", RadioBrowserApi.SearchBy.NAME)

            assertFalse(results[0].sslError)
        }

    @Test
    fun `search defaults codec, votes, homepage and favicon when the fields are absent`() =
        runTest {
            val body = """[{"name":"No Extra Fields","url":"http://example.com/a"}]"""
            server.enqueue(MockResponse().setBody(body))

            val results = api.search("x", RadioBrowserApi.SearchBy.NAME)

            assertEquals("", results[0].codec)
            assertEquals(0, results[0].votes)
            assertEquals("", results[0].homepage)
            assertEquals("", results[0].favicon)
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
    fun `search defaults hls to false when the field is absent`() =
        runTest {
            val body = """[{"name":"No Hls Field","url":"http://example.com/a"}]"""
            server.enqueue(MockResponse().setBody(body))

            val results = api.search("x", RadioBrowserApi.SearchBy.NAME)

            assertFalse(results[0].hls)
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

    @Test
    fun `search sorts by votes, matching the star badge shown in results`() =
        runTest {
            server.enqueue(MockResponse().setBody("[]"))

            api.search("jazz", RadioBrowserApi.SearchBy.TAG)

            val request = server.takeRequest()
            assertTrue(request.path?.contains("order=votes") == true)
        }

    @Test
    fun `search retries after a failed attempt and returns the retry's results`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""[{"name":"Recovered FM","url":"http://example.com/a"}]"""))

            val results = api.search("x", RadioBrowserApi.SearchBy.NAME)

            assertEquals(2, server.requestCount)
            assertEquals(1, results.size)
            assertEquals("Recovered FM", results[0].name)
        }

    @Test(expected = IOException::class)
    fun `search throws once retries are exhausted on a persistently failing mirror`() =
        runTest {
            // fetchStations retries up to MAX_RETRIES (3) times, so 4 total queued failures are
            // needed here for a persistently-failing mirror to actually surface as an IOException.
            repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }

            api.search("x", RadioBrowserApi.SearchBy.NAME)
        }

    @Test
    fun `searchNearby sends geo params and reads distance in km`() =
        runTest {
            val body =
                """
                [{"name":"Local FM","url":"http://example.com/local","distance":2500.0}]
                """.trimIndent()
            server.enqueue(MockResponse().setBody(body))

            val results = api.searchNearby(latitude = 52.5, longitude = 13.4, radiusMeters = 50_000)

            val request = server.takeRequest()
            assertTrue(request.path?.startsWith("/json/stations/search") == true)
            assertTrue(request.path?.contains("geo_lat=52.5") == true)
            assertTrue(request.path?.contains("geo_long=13.4") == true)
            assertTrue(request.path?.contains("geo_distance=50000") == true)
            assertTrue(request.path?.contains("order=distance") == true)
            assertEquals(1, results.size)
            assertEquals(2.5, results[0].distanceKm)
        }

    @Test
    fun `searchNearby leaves distanceKm null when the field is absent`() =
        runTest {
            server.enqueue(MockResponse().setBody("""[{"name":"No Distance","url":"http://example.com/a"}]"""))

            val results = api.searchNearby(latitude = 52.5, longitude = 13.4, radiusMeters = 50_000)

            assertEquals(null, results[0].distanceKm)
        }

    @Test
    fun `search leaves distanceKm null since it never sends geo params`() =
        runTest {
            server.enqueue(MockResponse().setBody("""[{"name":"Test FM","url":"http://example.com/a"}]"""))

            val results = api.search("x", RadioBrowserApi.SearchBy.NAME)

            assertEquals(null, results[0].distanceKm)
        }

    @Test(expected = IOException::class)
    fun `searchNearby throws once retries are exhausted on a persistently failing mirror`() =
        runTest {
            repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }

            api.searchNearby(latitude = 52.5, longitude = 13.4, radiusMeters = 50_000)
        }

    @Test
    fun `an identical repeat search is served from cache instead of hitting the network again`() =
        runTest {
            server.enqueue(MockResponse().setBody("""[{"name":"Cached FM","url":"http://example.com/a"}]"""))

            val first = api.search("jazz", RadioBrowserApi.SearchBy.NAME)
            val second = api.search("jazz", RadioBrowserApi.SearchBy.NAME)

            assertEquals(1, server.requestCount)
            assertEquals(first, second)
        }

    @Test
    fun `a different query is not served from another query's cache entry`() =
        runTest {
            server.enqueue(MockResponse().setBody("""[{"name":"Jazz FM","url":"http://example.com/jazz"}]"""))
            server.enqueue(MockResponse().setBody("""[{"name":"Rock FM","url":"http://example.com/rock"}]"""))

            api.search("jazz", RadioBrowserApi.SearchBy.NAME)
            api.search("rock", RadioBrowserApi.SearchBy.NAME)

            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a search past its cache ttl hits the network again`() =
        runTest {
            val shortLivedApi = RadioBrowserApi(baseUrl = server.url("/"), cacheTtlMs = 0L)
            server.enqueue(MockResponse().setBody("""[{"name":"First","url":"http://example.com/a"}]"""))
            server.enqueue(MockResponse().setBody("""[{"name":"Second","url":"http://example.com/b"}]"""))

            val first = shortLivedApi.search("jazz", RadioBrowserApi.SearchBy.NAME)
            val second = shortLivedApi.search("jazz", RadioBrowserApi.SearchBy.NAME)

            assertEquals(2, server.requestCount)
            assertEquals("First", first[0].name)
            assertEquals("Second", second[0].name)
        }

    @Test
    fun `registerClick hits json url with the uuid`() =
        runTest {
            server.enqueue(MockResponse().setBody("{}"))

            api.registerClick("abc-123")

            val request = server.takeRequest()
            assertEquals("/json/url/abc-123", request.path)
        }

    @Test
    fun `registerClick swallows a non-2xx response without throwing`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            api.registerClick("abc-123") // Must not throw.
        }

    @Test
    fun `registerClick swallows a network failure without throwing`() =
        runTest {
            server.shutdown()

            api.registerClick("abc-123") // Must not throw even though the server is unreachable.
        }

    @Test
    fun `downloadFavicon returns the response bytes on success`() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3, 4)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

            val result = api.downloadFavicon(server.url("/favicon.png").toString())

            assertTrue(bytes.contentEquals(result))
        }

    @Test
    fun `downloadFavicon returns null on a non-2xx response`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = api.downloadFavicon(server.url("/favicon.png").toString())

            assertEquals(null, result)
        }

    @Test
    fun `downloadFavicon returns null for a malformed url instead of throwing`() =
        runTest {
            val result = api.downloadFavicon("not a url")

            assertEquals(null, result)
        }

    @Test
    fun `downloadFavicon returns null on a network failure`() =
        runTest {
            val url = server.url("/favicon.png").toString()
            server.shutdown()

            val result = api.downloadFavicon(url)

            assertEquals(null, result)
        }

    @Test
    fun `topStations sends no name, tag or country filter`() =
        runTest {
            server.enqueue(MockResponse().setBody("[]"))

            api.topStations()

            val request = server.takeRequest()
            assertTrue(request.path?.startsWith("/json/stations/search") == true)
            assertTrue(request.path?.contains("order=votes") == true)
            assertFalse(request.path?.contains("name=") == true)
            assertFalse(request.path?.contains("tag=") == true)
            assertFalse(request.path?.contains("country=") == true)
        }
}
