package com.freqcast.ui.playback

import androidx.media3.datasource.DataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Owns the recording buffer and seek math for timeshift (rewind) playback of single-URL streams.
 * [RadioPlaybackService] delegates all buffer-file/recorder bookkeeping here so it only has to
 * deal with [DataSource.Factory] instances when wiring up ExoPlayer media sources.
 */
class TimeshiftController(
    private val cacheDir: File,
) {
    private var bufferFile: File? = null
    private var recorder: StreamRecorder? = null
    private var atLiveEdge: Boolean = true

    // Deliberately not the caller's scope: exportClip() must survive a service teardown
    // (RadioPlaybackService.onDestroy() cancels its own serviceScope) mid-copy.
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Absolute position within the buffer timeline (ms since recording started), as of [lastPositionAnchorMs]. */
    private var lastPositionMs: Long = 0L
    private var lastPositionAnchorMs: Long = 0L

    /** Starts recording [streamUrl] to a fresh buffer file and returns a data source factory to play it back live. */
    fun start(
        streamUrl: String,
        onError: (Throwable) -> Unit,
        onMetadata: (String) -> Unit = {},
        maxBufferBytes: Long = TimeshiftBufferSize.DEFAULT_MB * 1024L * 1024L,
    ): DataSource.Factory {
        stop()
        val file = File(cacheDir, "timeshift_${streamUrl.hashCode().toString(36)}.tmp")
        file.createNewFile()
        bufferFile = file
        val newRecorder = StreamRecorder(streamUrl, file, maxBufferBytes)
        recorder = newRecorder
        atLiveEdge = true
        lastPositionMs = 0L
        lastPositionAnchorMs = System.currentTimeMillis()
        newRecorder.start(onError, onMetadata)
        return dataSourceFactory(newRecorder, file)
    }

    fun stop() {
        recorder?.stop()
        recorder = null
        bufferFile?.takeIf { it.exists() }?.delete()
        bufferFile = null
    }

    fun currentBufferFile(): File? = bufferFile

    private fun currentAbsolutePositionMs(): Long = lastPositionMs + (System.currentTimeMillis() - lastPositionAnchorMs)

    /** Total duration currently held in the buffer (ms since recording started), or 0 if not recording. */
    fun bufferedDurationMs(): Long {
        val rec = recorder ?: return 0L
        val startMs = rec.getStartTimeMs()
        if (startMs <= 0L) return 0L
        return (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
    }

    /** How far behind the live edge playback currently sits, in ms. 0 when at live. */
    fun offsetFromLiveMs(): Long {
        val buffered = bufferedDurationMs()
        return (buffered - currentAbsolutePositionMs()).coerceIn(0L, buffered)
    }

    /** Returns a data source factory positioned [ms] further behind live than the current position, or null if not recording. */
    fun seekBackward(ms: Long): DataSource.Factory? = seekToOffsetFromLive(offsetFromLiveMs() + ms)

    /** Returns a data source factory positioned at the live edge, or null if not recording. */
    fun seekToLive(): DataSource.Factory? = seekToOffsetFromLive(0L)

    /** Returns a data source factory positioned [offsetMs] behind the live edge, or null if not recording. */
    fun seekToOffsetFromLive(offsetMs: Long): DataSource.Factory? {
        val rec = recorder ?: return null
        val file = bufferFile ?: return null
        val now = System.currentTimeMillis()
        val clampedOffsetMs = offsetMs.coerceAtLeast(0L)
        val targetMs = (bufferedDurationMs() - clampedOffsetMs).coerceAtLeast(0L)

        // ExoPlayer often ignores seekTo(ms) for live-style progressive source (C.LENGTH_UNSET).
        // Seek by reopening source at byte offset corresponding to targetMs.
        val bytesTotal = rec.getCurrentLength()
        val startMs = rec.getStartTimeMs()
        val elapsedMs = (now - startMs).coerceAtLeast(1L)
        val bytesPerMs = if (bytesTotal > 0) bytesTotal / elapsedMs else 0L
        val targetBytes = if (bytesPerMs > 0) (targetMs * bytesPerMs).coerceIn(0L, bytesTotal) else 0L

        lastPositionMs = targetMs
        lastPositionAnchorMs = now
        atLiveEdge = clampedOffsetMs == 0L
        val isLive = atLiveEdge

        return LiveFileDataSource.Factory(
            file = file,
            currentLengthSupplier = { rec.getCurrentLength() },
            // Live stays pinned to the growing file end; a specific offset uses the byte position
            // computed above so it doesn't drift forward as more data is recorded.
            startPositionOverride = { if (isLive) rec.getCurrentLength() else targetBytes },
        )
    }

    fun isAtLive(): Boolean = atLiveEdge

    fun hasTimeshift(): Boolean = recorder?.isRecording() == true

    fun currentTrackTitle(): String? = recorder?.getCurrentTrackTitle()

    /** MP3/AAC or null (unknown, or a container out of clip-export's scope) - see [StreamRecorder.getClipFormat]. */
    fun currentClipFormat(): ClipFormat? = recorder?.getClipFormat()

    /**
     * Copies the last [durationMs] of the buffer (clamped to what's actually buffered) to
     * [destination], then reports success on the main thread via [onResult].
     *
     * The buffer file's read handle is opened synchronously, before any suspension point, and only
     * *then* is the actual copy handed off to [exportScope]. That ordering matters: [stop] (e.g. on
     * station switch) unlinks [bufferFile] but Android/Linux keep an already-open fd's inode
     * readable after unlink - so as long as the open happens before this function could possibly be
     * suspended and a concurrent [stop] gets to run first, the read stays valid even if the file
     * disappears from the directory mid-copy.
     */
    fun exportClip(
        durationMs: Long,
        destination: File,
        onResult: (Boolean) -> Unit,
    ) {
        val rec = recorder
        val file = bufferFile
        if (rec == null || file == null) {
            onResult(false)
            return
        }
        val input =
            try {
                FileInputStream(file)
            } catch (e: IOException) {
                onResult(false)
                return
            }

        // Same byte-range math as seekToOffsetFromLive(): the buffer isn't a rolling window (see
        // StreamRecorder.recordStream), so a clip request longer than what's actually buffered is
        // clamped to endByte via startByte's coerceAtLeast(0L) below.
        val now = System.currentTimeMillis()
        val endByte = rec.getCurrentLength()
        val startMs = rec.getStartTimeMs()
        val elapsedMs = (now - startMs).coerceAtLeast(1L)
        val bytesPerMs = if (endByte > 0) endByte / elapsedMs else 0L
        val clipBytes = if (bytesPerMs > 0) durationMs * bytesPerMs else 0L
        val startByte = (endByte - clipBytes).coerceAtLeast(0L)

        exportScope.launch {
            val success =
                try {
                    input.use { inStream ->
                        inStream.channel.position(startByte)
                        FileOutputStream(destination).use { out ->
                            val buffer = ByteArray(8192)
                            var remaining = endByte - startByte
                            while (remaining > 0) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val read = inStream.read(buffer, 0, toRead)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                remaining -= read
                            }
                        }
                    }
                    true
                } catch (e: IOException) {
                    false
                }
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    private fun dataSourceFactory(
        rec: StreamRecorder,
        file: File,
    ): DataSource.Factory =
        LiveFileDataSource.Factory(
            file = file,
            currentLengthSupplier = { rec.getCurrentLength() },
            isRecordingSupplier = { rec.isRecording() },
        )
}
