package com.urlradiodroid.ui.playback

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TimeshiftControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var controller: TimeshiftController

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        controller = TimeshiftController(tempFolder.root)
    }

    @After
    fun tearDown() {
        controller.stop()
        server.shutdown()
    }

    private fun awaitTrue(
        timeoutMs: Long = 5000L,
        poll: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return
            Thread.sleep(20)
        }
        assertTrue("condition not met within ${timeoutMs}ms", poll())
    }

    @Test
    fun `start creates a buffer file and starts recording`() {
        server.enqueue(MockResponse().setBody("x".repeat(10_000)))

        val factory = controller.start(server.url("/stream").toString(), onError = {})

        assertNotNull(factory)
        assertTrue(controller.currentBufferFile()!!.exists())
        assertTrue(controller.isAtLive())
        assertTrue(controller.hasTimeshift())
    }

    @Test
    fun `stop deletes the buffer file and resets state`() {
        server.enqueue(MockResponse().setBody("x".repeat(10_000)))
        controller.start(server.url("/stream").toString(), onError = {})
        val bufferFile = controller.currentBufferFile()!!

        controller.stop()

        assertFalse(bufferFile.exists())
        assertNull(controller.currentBufferFile())
        assertFalse(controller.hasTimeshift())
    }

    @Test
    fun `starting again replaces the previous buffer file`() {
        server.enqueue(MockResponse().setBody("x".repeat(10_000)))
        server.enqueue(MockResponse().setBody("y".repeat(10_000)))
        controller.start(server.url("/stream-a").toString(), onError = {})
        val firstFile = controller.currentBufferFile()!!

        controller.start(server.url("/stream-b").toString(), onError = {})
        val secondFile = controller.currentBufferFile()!!

        assertNotEquals(firstFile, secondFile)
        assertFalse(firstFile.exists())
        assertTrue(secondFile.exists())
    }

    @Test
    fun `seekBackward returns null when nothing is being recorded`() {
        assertNull(controller.seekBackward(5_000))
    }

    @Test
    fun `seekToLive returns null when nothing is being recorded`() {
        assertNull(controller.seekToLive())
    }

    @Test
    fun `seekBackward moves off the live edge`() {
        server.enqueue(MockResponse().setBody("x".repeat(10_000)))
        controller.start(server.url("/stream").toString(), onError = {})

        val factory = controller.seekBackward(5_000)

        assertNotNull(factory)
        assertFalse(controller.isAtLive())
    }

    @Test
    fun `seekToLive returns to the live edge`() {
        server.enqueue(MockResponse().setBody("x".repeat(10_000)))
        controller.start(server.url("/stream").toString(), onError = {})
        controller.seekBackward(5_000)

        val factory = controller.seekToLive()

        assertNotNull(factory)
        assertTrue(controller.isAtLive())
    }

    @Test
    fun `hasTimeshift is false before start and after stop`() {
        assertFalse(controller.hasTimeshift())

        server.enqueue(MockResponse().setBody("x".repeat(10_000)))
        controller.start(server.url("/stream").toString(), onError = {})
        assertTrue(controller.hasTimeshift())

        controller.stop()
        assertFalse(controller.hasTimeshift())
    }

    @Test
    fun `currentTrackTitle delegates to the underlying recorder`() {
        val icyMetaInt = 128
        val audioChunk = ByteArray(icyMetaInt) { 'A'.code.toByte() }
        val titleText = "StreamTitle='Test Song';"
        val padded = titleText.padEnd(((titleText.length / 16) + 1) * 16, ' ')
        val metaBlock = byteArrayOf((padded.length / 16).toByte()) + padded.toByteArray(Charsets.UTF_8)
        val responseBody = Buffer().write(audioChunk).write(metaBlock).write(audioChunk)
        server.enqueue(MockResponse().addHeader("icy-metaint", icyMetaInt).setBody(responseBody))
        val metadataLatch = CountDownLatch(1)

        assertNull(controller.currentTrackTitle())
        controller.start(
            server.url("/stream").toString(),
            onError = {},
            onMetadata = { metadataLatch.countDown() },
        )

        assertTrue(metadataLatch.await(5, TimeUnit.SECONDS))
        assertEquals("Test Song", controller.currentTrackTitle())
    }
}
