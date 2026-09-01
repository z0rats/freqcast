package com.freqcast.ui.playback

import com.freqcast.util.STREAM_USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Elementary audio container detected for the current recording; see [StreamRecorder.getClipFormat]. */
enum class ClipFormat(
    val extension: String,
    val mimeType: String,
) {
    MP3("mp3", "audio/mpeg"),
    AAC("aac", "audio/aac"),
}

/**
 * Records a stream URL to a file in the background. Used for timeshift (rewind) support.
 * Stops when [maxSizeBytes] is reached (defaults to ~30 MB = ~25–30 min at 128 kbps; see
 * [TimeshiftBufferSize] for the user-configurable presets).
 *
 * Also requests and parses in-band ICY metadata (the `StreamTitle=` blocks Shoutcast/Icecast
 * servers interleave with audio every `icy-metaint` bytes) so the current track title can be
 * shown to the user. Metadata bytes are stripped before writing to [outputFile] so only audio
 * data reaches the player.
 */
class StreamRecorder(
    private val streamUrl: String,
    private val outputFile: File,
    private val maxSizeBytes: Long = TimeshiftBufferSize.DEFAULT_MB * 1024L * 1024L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    private val bytesWritten = AtomicLong(0L)
    private val startTimeMs = AtomicLong(0L)
    private val recording = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var call: Call? = null
    private var job: Job? = null

    // ICY in-band metadata parsing state (all only touched from the recording coroutine).
    private var icyMetaIntervalBytes = 0
    private var audioBytesUntilMeta = 0
    private var metaBytesRemaining = 0
    private val metaBuffer = ByteArrayOutputStream()
    private var onMetadata: (String) -> Unit = {}

    @Volatile
    private var currentTrackTitle: String? = null

    /**
     * Every title change, timestamped by elapsed ms since [startTimeMs] - the same clock basis
     * [TimeshiftController.bufferedDurationMs] uses - so a seek back to an earlier buffer position
     * can look up which track was actually playing there instead of always reporting the latest
     * (live) title. Written only from the recording coroutine, read from the main thread.
     */
    private val titleHistory = CopyOnWriteArrayList<TitleAt>()

    private data class TitleAt(
        val atMs: Long,
        val title: String,
    )

    // Format detection: only touched from the recording coroutine, like the ICY state above.
    @Volatile
    private var clipFormat: ClipFormat? = null
    private val magicBytes = ByteArray(2)
    private var magicBytesFilled = 0

    fun getCurrentLength(): Long = bytesWritten.get()

    fun getStartTimeMs(): Long = startTimeMs.get()

    fun isRecording(): Boolean = recording.get()

    fun getCurrentTrackTitle(): String? = currentTrackTitle

    /** The title that was in effect at [positionMs] into the recording, or null if unknown that early. */
    fun getTrackTitleAt(positionMs: Long): String? = titleHistory.lastOrNull { it.atMs <= positionMs }?.title

    /**
     * The elementary audio container of the recorded bytes (MP3 or ADTS AAC), or null if not yet
     * known or neither (e.g. Ogg - out of scope for clip export, see [ClipFormat]). Determined from
     * the response's `Content-Type` header, falling back to sniffing the first two audio bytes'
     * frame-sync pattern for servers that omit or lie about the header.
     */
    fun getClipFormat(): ClipFormat? = clipFormat

    fun start(
        onError: (Throwable) -> Unit = {},
        onMetadata: (String) -> Unit = {},
    ) {
        if (recording.getAndSet(true)) return
        stopping.set(false)
        this.onMetadata = onMetadata
        job =
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        recordStream(onError)
                    }
                } finally {
                    recording.set(false)
                }
            }
    }

    fun stop() {
        // Mark first so the exception thrown by call.cancel() below is not reported as a playback error,
        // then cancel the in-flight call to interrupt any blocking socket read immediately.
        stopping.set(true)
        call?.cancel()
        job?.cancel()
        job = null
        recording.set(false)
    }

    private fun recordStream(onError: (Throwable) -> Unit) {
        try {
            val request =
                Request
                    .Builder()
                    .url(streamUrl)
                    .header("Icy-Metadata", "1")
                    .header("User-Agent", STREAM_USER_AGENT)
                    .build()
            val activeCall = client.newCall(request)
            call = activeCall
            activeCall.execute().use { response ->
                if (!response.isSuccessful) {
                    onError(RuntimeException("HTTP ${response.code}"))
                    return
                }
                val body =
                    response.body ?: run {
                        onError(RuntimeException("Empty response body"))
                        return
                    }
                clipFormat = classifyContentType(response.header("Content-Type"))
                icyMetaIntervalBytes = response.header("icy-metaint")?.toIntOrNull() ?: 0
                audioBytesUntilMeta = icyMetaIntervalBytes
                body.byteStream().use { input ->
                    FileOutputStream(outputFile, false).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        if (startTimeMs.get() == 0L) {
                            startTimeMs.set(System.currentTimeMillis())
                        }
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1 && total < maxSizeBytes) {
                            total = writeAudioAndExtractMetadata(buffer, read, output, total)
                            bytesWritten.set(total)
                            if (total >= maxSizeBytes) break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopping.get()) {
                onError(e)
            }
        } finally {
            call = null
        }
    }

    /**
     * Splits [len] bytes of [buffer] into audio (written to [output], capped at [maxSizeBytes]
     * total) and ICY metadata (parsed for the track title, never written). Returns the new
     * running total of audio bytes written, starting from [startTotal].
     */
    private fun writeAudioAndExtractMetadata(
        buffer: ByteArray,
        len: Int,
        output: FileOutputStream,
        startTotal: Long,
    ): Long {
        if (icyMetaIntervalBytes <= 0) {
            val take = minOf(len.toLong(), maxSizeBytes - startTotal).toInt()
            if (take > 0) {
                sniffFormatIfNeeded(buffer, 0, take)
                output.write(buffer, 0, take)
            }
            return startTotal + take
        }

        var total = startTotal
        var pos = 0
        while (pos < len && total < maxSizeBytes) {
            if (metaBytesRemaining > 0) {
                val take = minOf(metaBytesRemaining, len - pos)
                metaBuffer.write(buffer, pos, take)
                metaBytesRemaining -= take
                pos += take
                if (metaBytesRemaining == 0) {
                    parseIcyMetadata(metaBuffer.toByteArray())
                    metaBuffer.reset()
                }
                continue
            }
            if (audioBytesUntilMeta == 0) {
                val lengthBlocks = buffer[pos].toInt() and 0xFF
                pos += 1
                audioBytesUntilMeta = icyMetaIntervalBytes
                if (lengthBlocks > 0) {
                    metaBytesRemaining = lengthBlocks * 16
                }
                continue
            }
            val audioTake = minOf(audioBytesUntilMeta, len - pos, (maxSizeBytes - total).toInt())
            if (audioTake <= 0) break
            sniffFormatIfNeeded(buffer, pos, audioTake)
            output.write(buffer, pos, audioTake)
            total += audioTake
            audioBytesUntilMeta -= audioTake
            pos += audioTake
        }
        return total
    }

    private fun parseIcyMetadata(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8).trimEnd(' ')
        val title =
            Regex("StreamTitle='([^']*)'")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.trim()
        if (!title.isNullOrEmpty() && title != currentTrackTitle) {
            currentTrackTitle = title
            titleHistory.add(TitleAt(atMs = System.currentTimeMillis() - startTimeMs.get(), title = title))
            onMetadata(title)
        }
    }

    private fun classifyContentType(header: String?): ClipFormat? =
        when (header?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> ClipFormat.MP3
            "audio/aac", "audio/aacp", "audio/x-aac" -> ClipFormat.AAC
            else -> null
        }

    /**
     * Fallback for servers that omit/misreport `Content-Type`: buffers the first two *audio* bytes
     * written (post ICY-metadata-stripping) across calls and, once both are in hand, classifies
     * them by frame-sync pattern - MP3's 11-bit sync (`0xFFEx`-`0xFFFx` with a non-zero layer) vs.
     * ADTS AAC's 12-bit sync with layer bits forced to `00` (`0xFFF0`/`0xFFF1` et al). No-op once
     * [clipFormat] is already known (from the header) or the first two bytes are already captured.
     */
    private fun sniffFormatIfNeeded(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (clipFormat != null || magicBytesFilled >= 2) return
        var i = offset
        val end = offset + length
        while (i < end && magicBytesFilled < 2) {
            magicBytes[magicBytesFilled] = buffer[i]
            magicBytesFilled++
            i++
        }
        if (magicBytesFilled == 2) {
            clipFormat = sniffFormat(magicBytes[0], magicBytes[1])
        }
    }

    private fun sniffFormat(
        b0: Byte,
        b1: Byte,
    ): ClipFormat? {
        if ((b0.toInt() and 0xFF) != 0xFF) return null
        val second = b1.toInt() and 0xFF
        return when {
            (second and 0xF6) == 0xF0 -> ClipFormat.AAC
            (second and 0xE0) == 0xE0 -> ClipFormat.MP3
            else -> null
        }
    }
}
