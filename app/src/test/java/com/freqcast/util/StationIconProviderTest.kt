package com.freqcast.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/**
 * The provider is registered from the manifest, so [Context.getContentResolver] hits the real
 * [StationIconProvider]. No [androidx.core.content.FileProvider.getUriForFile] — that call stays
 * in [StationShareTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StationIconProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val iconName = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.jpg"
    private val iconBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)

    private fun writeIcon(name: String = iconName): File {
        val dir = File(context.filesDir, "station_icons").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(iconBytes) }
    }

    @Test
    fun `a stored icon is readable through the content uri with no uri grant`() {
        val file = writeIcon()
        val uri = IconStorage.contentUri(context, file.absolutePath)

        assertEquals(Uri.parse("content://com.freqcast.stationicons/icons/$iconName"), uri)
        val opened = context.contentResolver.openInputStream(uri!!)!!.use { it.readBytes() }
        assertArrayEquals(iconBytes, opened)
        assertEquals("image/jpeg", context.contentResolver.getType(uri))
        context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { descriptor ->
            assertEquals(iconBytes.size.toLong(), descriptor.declaredLength)
        }
        context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(iconName, cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(iconBytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertNull(
            context.contentResolver.query(
                Uri.parse("content://com.freqcast.stationicons/icons/logo.jpg"),
                null,
                null,
                null,
                null,
            ),
        )
    }

    @Test
    fun `openFile rejects writes and paths that are not one icon file`() {
        val file = writeIcon()
        val uri = IconStorage.contentUri(context, file.absolutePath)!!
        val outside = File(context.filesDir, "secret.txt").apply { writeText("nope") }
        val iconsDir = File(context.filesDir, "station_icons")
        val link = File(iconsDir, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.jpg")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openFileDescriptor(uri, "w")
        }
        assertNull(IconStorage.fileForIconUri(context, linkUri(link.name)))
        listOf(
            "content://com.freqcast.stationicons/icons/../secret.txt",
            "content://com.freqcast.stationicons/icons/..%2F..%2Fsecret.txt",
            "content://com.freqcast.stationicons/icons/$iconName/../../secret.txt",
            "content://com.freqcast.stationicons/icons/logo.jpg",
            "content://com.freqcast.stationicons/$iconName",
            "content://com.freqcast.fileprovider/station_icons/$iconName",
            "content://com.freqcast.stationicons/icons/${outside.name}",
            "file://${file.absolutePath}",
        ).forEach { raw ->
            assertNull(raw, IconStorage.fileForIconUri(context, Uri.parse(raw)))
        }
        assertArrayEquals(iconBytes, file.readBytes())
    }

    private fun linkUri(name: String): Uri = IconStorage.iconContentUri(name)
}
