package com.freqcast.ui.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TimeshiftController] only ever exercises this through a real [StreamRecorder]-fed file, so
 * the error branches (missing/unreadable buffer file, stalled read with no producer left) are
 * covered directly here against a plain [TemporaryFolder] file instead.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LiveFileDataSourceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `open throws when the buffer file does not exist`() {
        val missing = tempFolder.root.resolve("does-not-exist.tmp")
        val dataSource = LiveFileDataSource(missing, currentLengthSupplier = { 0L })

        val thrown =
            assertThrows(DataSourceException::class.java) {
                dataSource.open(DataSpec(Uri.fromFile(missing)))
            }
        assertEquals(androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, thrown.reason)
    }

    @Test
    fun `open wraps an unexpected failure opening the file`() {
        // A directory exists (passes the exists() check) but can't be opened as a RandomAccessFile,
        // exercising the generic catch-and-wrap branch rather than the not-found one above.
        val directory = tempFolder.newFolder("a-directory")
        val dataSource = LiveFileDataSource(directory, currentLengthSupplier = { 0L })

        assertThrows(DataSourceException::class.java) {
            dataSource.open(DataSpec(Uri.fromFile(directory)))
        }
    }

    @Test
    fun `read returns end of input once the recorder has stopped and no more data arrives`() {
        val file = tempFolder.newFile("buffer.tmp")
        file.writeBytes(ByteArray(10))
        val dataSource =
            LiveFileDataSource(
                file,
                currentLengthSupplier = { 10L },
                blockTimeoutMs = 50L,
                isRecordingSupplier = { false },
            )
        dataSource.open(DataSpec(Uri.fromFile(file)).buildUpon().setPosition(10L).build())

        val result = dataSource.read(ByteArray(8), 0, 8)

        assertEquals(C.RESULT_END_OF_INPUT, result)
    }

    @Test
    fun `read returns zero when still recording but no new data has arrived yet`() {
        val file = tempFolder.newFile("buffer.tmp")
        file.writeBytes(ByteArray(10))
        val dataSource =
            LiveFileDataSource(
                file,
                currentLengthSupplier = { 10L },
                blockTimeoutMs = 50L,
                isRecordingSupplier = { true },
            )
        dataSource.open(DataSpec(Uri.fromFile(file)).buildUpon().setPosition(10L).build())

        val result = dataSource.read(ByteArray(8), 0, 8)

        assertEquals(0, result)
    }

    @Test
    fun `read returns the available bytes without blocking when data is already buffered`() {
        val file = tempFolder.newFile("buffer.tmp")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val dataSource = LiveFileDataSource(file, currentLengthSupplier = { 5L })
        dataSource.open(DataSpec(Uri.fromFile(file)))

        val buffer = ByteArray(8)
        val result = dataSource.read(buffer, 0, buffer.size)

        assertEquals(5, result)
        assertEquals(1.toByte(), buffer[0])
        assertEquals(5.toByte(), buffer[4])
    }

    @Test
    fun `read wraps an unexpected failure reading the file`() {
        val file = tempFolder.newFile("buffer.tmp")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val dataSource = LiveFileDataSource(file, currentLengthSupplier = { 3L })
        dataSource.open(DataSpec(Uri.fromFile(file)))

        // offset+length past the destination buffer's bounds forces RandomAccessFile.read to throw,
        // exercising the read()-side catch-and-wrap the way a real I/O failure would.
        assertThrows(DataSourceException::class.java) {
            dataSource.read(ByteArray(2), 0, 10)
        }
    }

    @Test
    fun `getUri is null before open and set after`() {
        val file = tempFolder.newFile("buffer.tmp")
        val dataSource = LiveFileDataSource(file, currentLengthSupplier = { 0L })

        assertNull(dataSource.getUri())
        dataSource.open(DataSpec(Uri.fromFile(file)))
        assertTrue(dataSource.getUri().toString().endsWith("buffer.tmp"))
    }

    @Test
    fun `close releases the file handle and clears the uri`() {
        val file = tempFolder.newFile("buffer.tmp")
        file.writeBytes(byteArrayOf(1))
        val dataSource = LiveFileDataSource(file, currentLengthSupplier = { 1L })
        dataSource.open(DataSpec(Uri.fromFile(file)))

        dataSource.close()

        assertNull(dataSource.getUri())
        // With the underlying RandomAccessFile released, a subsequent read has nothing to read from.
        assertEquals(C.RESULT_END_OF_INPUT, dataSource.read(ByteArray(4), 0, 4))
    }
}
