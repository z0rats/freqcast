package com.urlradiodroid.util

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamValidatorTest {
    private lateinit var server: MockWebServer
    private lateinit var validator: StreamValidator

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        validator = StreamValidator()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `isReachable returns true when HEAD succeeds`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200))

            assertTrue(validator.isReachable(server.url("/stream").toString()))
            assertEquals("HEAD", server.takeRequest().method)
        }

    @Test
    fun `isReachable falls back to GET when HEAD is rejected`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setResponseCode(200).setBody("audio-bytes"))

            assertTrue(validator.isReachable(server.url("/stream").toString()))
            assertEquals("HEAD", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
        }

    @Test
    fun `isReachable returns false when both HEAD and GET fail`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(500))

            assertFalse(validator.isReachable(server.url("/missing").toString()))
        }

    @Test
    fun `isReachable returns false when the connection is refused`() =
        runTest {
            assertFalse(validator.isReachable("http://127.0.0.1:1/stream"))
        }

    @Test
    fun `isReachable returns false when server drops the connection`() =
        runTest {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

            assertFalse(validator.isReachable(server.url("/stream").toString()))
        }
}
