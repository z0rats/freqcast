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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun `hasTimeshift is false before start and after stop`() {
        assertFalse(controller.hasTimeshift())

        server.enqueue(MockResponse().setBody("x".repeat(10_000)))
        controller.start(server.url("/stream").toString(), onError = {})
        assertTrue(controller.hasTimeshift())

        controller.stop()
        assertFalse(controller.hasTimeshift())
    }

    @Test
    fun `exportClip reports failure immediately when nothing is being recorded`() {
        val resultLatch = CountDownLatch(1)
        var result: Boolean? = null

        controller.exportClip(5_000, tempFolder.newFile("clip.mp3")) {
            result = it
            resultLatch.countDown()
        }

        assertTrue(resultLatch.await(1, TimeUnit.SECONDS))
        assertEquals(false, result)
    }

    @Test
    fun `exportClip copies the buffered bytes to the destination file`() {
        server.enqueue(MockResponse().addHeader("Content-Type", "audio/mpeg").setBody("x".repeat(20_000)))
        controller.start(server.url("/stream").toString(), onError = {})
        awaitTrue { controller.currentClipFormat() != null }
        awaitTrue { controller.bufferedDurationMs() > 0 }

        val destination = File(tempFolder.root, "clip.mp3")
        val resultLatch = CountDownLatch(1)
        var result: Boolean? = null

        controller.exportClip(2_000, destination) {
            result = it
            resultLatch.countDown()
        }

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS))
        assertEquals(true, result)
        assertTrue(destination.exists())
        assertTrue(destination.length() > 0)
    }

    @Test
    fun `exportClip reports failure when the buffer file can no longer be opened`() {
        server.enqueue(MockResponse().setBody("x".repeat(10_000)))
        controller.start(server.url("/stream").toString(), onError = {})
        awaitTrue { controller.bufferedDurationMs() > 0 }
        // Simulate the buffer file disappearing out from under exportClip (e.g. a concurrent
        // stop()/restart racing it) without going through the controller's own stop(), so the
        // controller's in-memory state still thinks it's recording.
        controller.currentBufferFile()!!.delete()

        val resultLatch = CountDownLatch(1)
        var result: Boolean? = null

        controller.exportClip(2_000, File(tempFolder.root, "clip.mp3")) {
            result = it
            resultLatch.countDown()
        }

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS))
        assertEquals(false, result)
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

    private fun icyMetaBlock(title: String): ByteArray {
        val text = "StreamTitle='$title';"
        val padded = text.padEnd(((text.length / 16) + 1) * 16, ' ')
        return byteArrayOf((padded.length / 16).toByte()) + padded.toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `seeking backward shows the title that was playing at that position, not the live one`() {
        val icyMetaInt = 64
        val audioChunk = ByteArray(icyMetaInt) { 'A'.code.toByte() }
        val responseBody =
            Buffer()
                .write(audioChunk)
                .write(icyMetaBlock("First Song"))
                .write(audioChunk)
                .write(icyMetaBlock("Second Song"))
                .write(audioChunk)
        server.enqueue(
            MockResponse()
                .addHeader("icy-metaint", icyMetaInt)
                .setBody(responseBody)
                .throttleBody(32, 200, TimeUnit.MILLISECONDS),
        )
        val titles = mutableListOf<String>()

        controller.start(server.url("/stream").toString(), onError = {}, onMetadata = { titles.add(it) })
        awaitTrue(timeoutMs = 10_000) { titles.size >= 1 }
        val positionAtFirstTitleMs = controller.bufferedDurationMs()
        awaitTrue(timeoutMs = 10_000) { titles.size >= 2 }

        // Rewind back to that earlier buffer position: how far behind live it now sits.
        val offsetFromLiveMs = controller.bufferedDurationMs() - positionAtFirstTitleMs
        controller.seekBackward(offsetFromLiveMs)
        assertFalse(controller.isAtLive())
        // Rewinding to roughly when "First Song" started must show it, not the meanwhile-live "Second Song".
        assertEquals("First Song", controller.currentTrackTitle())

        // Back at the live edge, the title reflects what's actually airing now.
        controller.seekToLive()
        assertEquals("Second Song", controller.currentTrackTitle())
    }
}
