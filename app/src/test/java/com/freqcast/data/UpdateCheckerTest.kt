package com.freqcast.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateCheckerTest {
    private lateinit var server: MockWebServer
    private lateinit var updateChecker: UpdateChecker

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        updateChecker = UpdateChecker(releaseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `latestRelease parses the tag name and strips a leading v`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"tag_name": "v3.5.0", "html_url": "https://github.com/z0rats/freqcast/releases/tag/v3.5.0"}""",
                ),
            )

            val release = updateChecker.latestRelease()

            assertEquals("3.5.0", release?.version)
            assertEquals("https://github.com/z0rats/freqcast/releases/tag/v3.5.0", release?.url)
        }

    @Test
    fun `latestRelease returns null on a non-2xx response`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            assertNull(updateChecker.latestRelease())
        }

    @Test
    fun `latestRelease returns null on a malformed response`() =
        runTest {
            server.enqueue(MockResponse().setBody("not json"))

            assertNull(updateChecker.latestRelease())
        }

    @Test
    fun `isNewerVersion detects a newer patch, minor and major version`() {
        assertTrue(isNewerVersion("3.4.3", "3.4.4"))
        assertTrue(isNewerVersion("3.4.3", "3.5.0"))
        assertTrue(isNewerVersion("3.4.3", "4.0.0"))
    }

    @Test
    fun `isNewerVersion is false for an equal or older version`() {
        assertFalse(isNewerVersion("3.4.3", "3.4.3"))
        assertFalse(isNewerVersion("3.4.3", "3.4.2"))
        assertFalse(isNewerVersion("3.4.3", "2.9.9"))
    }

    @Test
    fun `isNewerVersion compares numerically, not lexicographically`() {
        assertTrue(isNewerVersion("3.4.3", "3.10.0"))
    }
}
