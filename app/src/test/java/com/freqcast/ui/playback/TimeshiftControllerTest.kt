package com.freqcast.ui.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class TimeshiftControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var controller: TimeshiftController

    @Before
    fun setup() {
        // exportClip() hops back to Dispatchers.Main to report its result; the plain JUnit
        // environment here has no real main looper (no Robolectric), so tests exercising it need
        // a fake Main dispatcher registered.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        controller = TimeshiftController(tempFolder.root)
    }

    @After
    fun tearDown() {
        controller.stop()
        server.shutdown()
        Dispatchers.resetMain()
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
    fun `seekToOffsetFromLive and exportClip agree on the byte offset for the same duration`() {
        server.enqueue(MockResponse().setBody("x".repeat(50_000)))
        controller.start(server.url("/stream").toString(), onError = {})
        awaitTrue { !controller.hasTimeshift() }

        // Half the buffered duration keeps the target well clear of the 0/full-buffer clamps,
        // so this exercises the shared mid-buffer byte math, not just the edge cases.
        val durationMs = (controller.bufferedDurationMs() / 2).coerceAtLeast(1L)

        val seekFactory = controller.seekToOffsetFromLive(durationMs) as LiveFileDataSource.Factory
        val overrideField = LiveFileDataSource.Factory::class.java.getDeclaredField("startPositionOverride")
        overrideField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val startPositionOverride = overrideField.get(seekFactory) as () -> Long
        val seekTargetByte = startPositionOverride()

        val bufferLength = controller.currentBufferFile()!!.length()
        val dest = tempFolder.newFile("clip.tmp")
        val resultLatch = CountDownLatch(1)
        controller.exportClip(durationMs, dest) { resultLatch.countDown() }
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS))
        val exportStartByte = bufferLength - dest.length()

        // seekToOffsetFromLive and exportClip both derive their byte offset from the same
        // bytes-per-ms rate (TimeshiftController.bytesForDuration); the tolerance absorbs wall-clock
        // drift between their two independent System.currentTimeMillis() reads, not a formula split.
        assertTrue(
            "seek target byte ($seekTargetByte) should be close to export start byte ($exportStartByte)",
            abs(seekTargetByte - exportStartByte) <= 2_000L,
        )
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
