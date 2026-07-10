package com.urlradiodroid.ui.playback

import androidx.media3.datasource.DataSource
import java.io.File

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
    private var lastSeekPositionMs: Long = 0L
    private var lastSeekTimeMs: Long = 0L

    /** Starts recording [streamUrl] to a fresh buffer file and returns a data source factory to play it back live. */
    fun start(
        streamUrl: String,
        onError: (Throwable) -> Unit,
        onMetadata: (String) -> Unit = {},
    ): DataSource.Factory {
        stop()
        val file = File(cacheDir, "timeshift_${streamUrl.hashCode().toString(36)}.tmp")
        file.createNewFile()
        bufferFile = file
        val newRecorder = StreamRecorder(streamUrl, file)
        recorder = newRecorder
        atLiveEdge = true
        lastSeekPositionMs = 0L
        lastSeekTimeMs = System.currentTimeMillis()
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

    /** Returns a data source factory positioned [ms] behind the current playback position, or null if not recording. */
    fun seekBackward(ms: Long): DataSource.Factory? {
        val rec = recorder ?: return null
        val file = bufferFile ?: return null
        val now = System.currentTimeMillis()
        val currentPosMs = lastSeekPositionMs + (now - lastSeekTimeMs)
        val targetMs = (currentPosMs - ms).coerceAtLeast(0L)

        // ExoPlayer often ignores seekTo(ms) for live-style progressive source (C.LENGTH_UNSET).
        // Seek by reopening source at byte offset corresponding to targetMs.
        val bytesTotal = rec.getCurrentLength()
        val startMs = rec.getStartTimeMs()
        val elapsedMs = (now - startMs).coerceAtLeast(1L)
        val bytesPerMs = if (elapsedMs > 0 && bytesTotal > 0) bytesTotal / elapsedMs else 0L
        val targetBytes = if (bytesPerMs > 0) (targetMs * bytesPerMs).coerceIn(0L, bytesTotal) else 0L

        lastSeekPositionMs = targetMs
        lastSeekTimeMs = now
        atLiveEdge = false

        return LiveFileDataSource.Factory(
            file = file,
            currentLengthSupplier = { rec.getCurrentLength() },
            startPositionOverride = { targetBytes },
        )
    }

    /** Returns a data source factory positioned at the live edge, or null if not recording. */
    fun seekToLive(): DataSource.Factory? {
        val rec = recorder ?: return null
        val file = bufferFile ?: return null
        atLiveEdge = true
        lastSeekPositionMs = 0L
        lastSeekTimeMs = System.currentTimeMillis()
        return LiveFileDataSource.Factory(
            file = file,
            currentLengthSupplier = { rec.getCurrentLength() },
            startPositionOverride = { rec.getCurrentLength() },
        )
    }

    fun isAtLive(): Boolean = atLiveEdge

    fun hasTimeshift(): Boolean = recorder?.isRecording() == true

    fun currentTrackTitle(): String? = recorder?.getCurrentTrackTitle()

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
