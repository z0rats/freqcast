package com.freqcast.ui

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers [RadioPlaybackService.updateMediaItemMetadataForTrack] - wired as
 * [com.freqcast.ui.playback.TimeshiftController]'s `onMetadata` callback in `applyPlayback`, fired
 * whenever [com.freqcast.ui.playback.StreamRecorder] parses a new ICY `StreamTitle=` block (see
 * [com.freqcast.ui.playback.StreamRecorderTest]'s identical ICY fixture for the wire format) -
 * so the lock screen / Android Auto / media-button apps show the current track, not just the
 * station name. A real (non-HLS) [MockWebServer] stream is required: this callback is only wired
 * for the timeshift/progressive path, never the HLS one.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioPlaybackServiceMetadataTest {
    private var service: RadioPlaybackService? = null
    private var server: MockWebServer? = null

    @After
    fun tearDown() {
        service?.onDestroy()
        server?.shutdown()
    }

    @Test
    fun `an ICY track title update sets the media item's artist without changing its title`() {
        val icyMetaInt = 128
        val audioChunk = ByteArray(icyMetaInt) { 'A'.code.toByte() }
        val titleText = "StreamTitle='Test Song';"
        val padded = titleText.padEnd(((titleText.length / 16) + 1) * 16, ' ')
        val metaBlock = byteArrayOf((padded.length / 16).toByte()) + padded.toByteArray(Charsets.UTF_8)
        val responseBody =
            Buffer()
                .write(audioChunk)
                .write(metaBlock)
                // A second, identical audio chunk after the metadata block so the recorder keeps
                // streaming (and the connection doesn't just end) once the title's been parsed.
                .write(audioChunk)
        val mockServer = MockWebServer()
        mockServer.enqueue(
            MockResponse()
                .addHeader("icy-metaint", icyMetaInt)
                .setBody(responseBody)
                .throttleBody(icyMetaInt.toLong(), 50, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        mockServer.start()
        server = mockServer
        val streamUrl = mockServer.url("/stream").toString()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = AppDatabase.getDatabase(context).radioStationDao()
        val service = Robolectric.buildService(RadioPlaybackService::class.java).create().get()
        this.service = service
        runBlocking {
            dao.getAllStations().forEach { dao.deleteStation(it.id) }
            dao.insertStation(RadioStation(name = "Metadata FM", streamUrl = streamUrl, sortOrder = 0))
            service.loadBrowsableStations()
        }
        service.playFromBrowseTree(streamUrl)

        val deadline = System.currentTimeMillis() + 5_000
        while (service
                .getPlayer()
                ?.currentMediaItem
                ?.mediaMetadata
                ?.artist
                ?.toString() != "Test Song" &&
            System.currentTimeMillis() < deadline
        ) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }

        val metadata = service.getPlayer()?.currentMediaItem?.mediaMetadata
        assertEquals("Test Song", metadata?.artist?.toString())
        // The station name stays the title - only the artist field carries the track (see the
        // notification's title=station/text=track convention this mirrors).
        assertEquals("Metadata FM", metadata?.title?.toString())
    }
}
