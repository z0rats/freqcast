package com.freqcast.ui.playback

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StreamRecorderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var outputFile: File

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        outputFile = tempFolder.newFile("buffer.tmp")
    }

    @After
    fun tearDown() {
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
    fun `start writes response body to output file`() {
        val body = "x".repeat(1000)
        server.enqueue(MockResponse().setBody(body))
        val recorder = StreamRecorder(server.url("/stream").toString(), outputFile)

        recorder.start()
        awaitTrue { !recorder.isRecording() }

        assertEquals(body.length.toLong(), outputFile.length())
        assertEquals(body.length.toLong(), recorder.getCurrentLength())
    }

    @Test
    fun `start is idempotent while already recording`() {
        server.enqueue(MockResponse().setBody("data"))
        server.enqueue(MockResponse().setBody("unused"))
        val recorder = StreamRecorder(server.url("/stream").toString(), outputFile)

        recorder.start()
        recorder.start() // recording flag already set synchronously, this call must be a no-op

        awaitTrue { !recorder.isRecording() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `recording stops once maxSizeBytes is reached`() {
        val body = "a".repeat(50_000)
        server.enqueue(MockResponse().setBody(body))
        val recorder = StreamRecorder(server.url("/stream").toString(), outputFile, maxSizeBytes = 10_000L)

        recorder.start()
        awaitTrue { !recorder.isRecording() }

        assertEquals(10_000L, outputFile.length())
        assertEquals(10_000L, recorder.getCurrentLength())
    }

    @Test
    fun `HTTP error response invokes onError`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val recorder = StreamRecorder(server.url("/missing").toString(), outputFile)
        val errorLatch = CountDownLatch(1)
        var captured: Throwable? = null

        recorder.start(onError = {
            captured = it
            errorLatch.countDown()
        })

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS))
        assertTrue(captured?.message?.contains("404") == true)
        awaitTrue { !recorder.isRecording() }
    }

    @Test
    fun `stop interrupts recording without reporting an error`() {
        val body = "x".repeat(200_000)
        server.enqueue(MockResponse().setBody(body).throttleBody(2_048, 100, TimeUnit.MILLISECONDS))
        val recorder = StreamRecorder(server.url("/stream").toString(), outputFile)
        var errorReported = false

        recorder.start(onError = { errorReported = true })
        awaitTrue { recorder.getCurrentLength() > 0 }
        recorder.stop()
        awaitTrue { !recorder.isRecording() }
        Thread.sleep(200) // let any exception from the cancelled call settle

        assertFalse(errorReported)
        assertTrue(outputFile.length() < body.length)
    }

    @Test
    fun `clip format is detected from an mpeg Content-Type header`() {
        server.enqueue(MockResponse().addHeader("Content-Type", "audio/mpeg").setBody("x".repeat(100)))
        val recorder = StreamRecorder(server.url("/stream").toString(), outputFile)

        recorder.start()
        awaitTrue { !recorder.isRecording() }

        assertEquals(ClipFormat.MP3, recorder.getClipFormat())
    }

    @Test
    fun `clip format is detected from an aac Content-Type header`() {
        server.enqueue(MockResponse().addHeader("Content-Type", "audio/aac; charset=utf-8").setBody("x".repeat(100)))
        val recorder = StreamRecorder(server.url("/stream").toString(), outputFile)

        recorder.start()
        awaitTrue { !recorder.isRecording() }

        assertEquals(ClipFormat.AAC, recorder.getClipFormat())
    }

    @Test
    fun `clip format falls back to sniffing MP3 frame-sync bytes when Content-Type is missing`() {
        val body = Buffer().write(byteArrayOf(0xFF.toByte(), 0xFB.toByte())).write("x".repeat(100).toByteArray())
        server.enqueue(MockResponse().setBody(body))
        val recorder = StreamRecorder(server.url("/stream").toString(), outputFile)

        recorder.start()
        awaitTrue { !recorder.isRecording() }

        assertEquals(ClipFormat.MP3, recorder.getClipFormat())
    }

    @Test
    fun `clip format falls back to sniffing ADTS AAC frame-sync bytes when Content-Type is missing`() {
        val body = Buffer().write(byteArrayOf(0xFF.toByte(), 0xF1.toByte())).write("x".repeat(100).toByteArray())
        server.enqueue(MockResponse().setBody(body))
        val recorder = StreamRecorder(server.url("/stream").toString(), outputFile)

        recorder.start()
        awaitTrue { !recorder.isRecording() }

        assertEquals(ClipFormat.AAC, recorder.getClipFormat())
    }

    @Test
    fun `clip format stays null for bytes matching neither sync pattern`() {
        val body = Buffer().write(byteArrayOf(0xFF.toByte(), 0x00)).write("x".repeat(100).toByteArray())
        server.enqueue(MockResponse().setBody(body))
        val recorder = StreamRecorder(server.url("/stream").toString(), outputFile)

        recorder.start()
        awaitTrue { !recorder.isRecording() }

        assertEquals(null, recorder.getClipFormat())
    }

    @Test
    fun `a connection failure invokes onError`() {
        // Nothing enqueued and the server is shut down immediately, so the request fails at the
        // socket level (ConnectException) rather than with an HTTP error response - a different
        // code path than the "HTTP error response" case above.
        val url = server.url("/stream").toString()
        server.shutdown()
        val recorder = StreamRecorder(url, outputFile)
        val errorLatch = CountDownLatch(1)
        var captured: Throwable? = null

        recorder.start(onError = {
            captured = it
            errorLatch.countDown()
        })

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(captured)
        awaitTrue { !recorder.isRecording() }
    }

    @Test
    fun `ICY metadata is parsed and stripped from the recorded audio`() {
        val icyMetaInt = 128
        val audioChunk1 = ByteArray(icyMetaInt) { 'A'.code.toByte() }
        val audioChunk2 = ByteArray(icyMetaInt) { 'B'.code.toByte() }
        val titleText = "StreamTitle='Test Song';"
        val padded = titleText.padEnd(((titleText.length / 16) + 1) * 16, ' ')
        val metaBlock = byteArrayOf((padded.length / 16).toByte()) + padded.toByteArray(Charsets.UTF_8)

        val responseBody =
            Buffer()
                .write(audioChunk1)
                .write(metaBlock)
                .write(audioChunk2)
        server.enqueue(
            MockResponse()
                .addHeader("icy-metaint", icyMetaInt)
                .setBody(responseBody),
        )
        val recorder = StreamRecorder(server.url("/stream").toString(), outputFile)
        val metadataLatch = CountDownLatch(1)
        var receivedTitle: String? = null

        recorder.start(onMetadata = {
            receivedTitle = it
            metadataLatch.countDown()
        })

        assertTrue(metadataLatch.await(5, TimeUnit.SECONDS))
        awaitTrue { !recorder.isRecording() }

        assertEquals("Test Song", receivedTitle)
        assertEquals("Test Song", recorder.getCurrentTrackTitle())
        assertEquals((icyMetaInt * 2).toLong(), outputFile.length())
        assertArrayEquals(audioChunk1 + audioChunk2, outputFile.readBytes())
    }
}
