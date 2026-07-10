package com.urlradiodroid.ui.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * DataSource that reads from a file which is being written by [StreamRecorder].
 * When read position reaches current file length, blocks (with timeout) until more data is available.
 * Used for timeshift: play from buffer while recording continues.
 */
@UnstableApi
class LiveFileDataSource(
    private val file: File,
    private val currentLengthSupplier: () -> Long,
    private val blockTimeoutMs: Long = 5000L,
    private val startPositionOverride: (() -> Long)? = null,
    private val isRecordingSupplier: () -> Boolean = { true },
) : BaseDataSource(false) { // isNetwork = false
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var opened = false

    @Throws(DataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = Uri.fromFile(file)
        try {
            if (!file.exists()) {
                throw DataSourceException(
                    "Buffer file does not exist",
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                )
            }
            val r = RandomAccessFile(file, "r")
            raf = r
            val position = startPositionOverride?.invoke() ?: dataSpec.position
            r.seek(position.coerceAtLeast(0))
        } catch (e: DataSourceException) {
            throw e
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    @Throws(DataSourceException::class)
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        val r = raf ?: return C.RESULT_END_OF_INPUT
        val currentLen = currentLengthSupplier()
        val pos = r.filePointer
        if (pos >= currentLen) {
            val deadline = System.currentTimeMillis() + blockTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(Companion.BLOCK_POLL_MS)
                val len = currentLengthSupplier()
                if (pos < len) break
            }
            if (r.filePointer >= currentLengthSupplier()) {
                // No new data arrived within the timeout. If the recorder has stopped for good
                // (error, explicit stop, or max buffer size reached) there is nothing left to wait
                // for, so signal end of input instead of stalling playback forever.
                return if (isRecordingSupplier()) 0 else C.RESULT_END_OF_INPUT
            }
        }
        val available = currentLengthSupplier() - r.filePointer
        if (available <= 0) return 0
        return try {
            val toRead = min(length.toLong(), available).toInt()
            val bytesRead = r.read(buffer, offset, toRead)
            if (bytesRead > 0) bytesTransferred(bytesRead)
            bytesRead
        } catch (e: Exception) {
            throw DataSourceException(
                e,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        }
    }

    override fun getUri(): Uri? = uri

    @Throws(DataSourceException::class)
    override fun close() {
        uri = null
        try {
            raf?.close()
        } catch (e: Exception) {
            throw DataSourceException(
                e,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        } finally {
            raf = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val file: File,
        private val currentLengthSupplier: () -> Long,
        private val blockTimeoutMs: Long = 5000L,
        private val startPositionOverride: (() -> Long)? = null,
        private val isRecordingSupplier: () -> Boolean = { true },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            LiveFileDataSource(
                file,
                currentLengthSupplier,
                blockTimeoutMs,
                startPositionOverride = startPositionOverride,
                isRecordingSupplier = isRecordingSupplier,
            )
    }

    companion object {
        private const val BLOCK_POLL_MS = 100L
    }
}
