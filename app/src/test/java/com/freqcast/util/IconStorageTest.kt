package com.freqcast.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Uses native graphics (not the legacy Robolectric shadow) so BitmapFactory decodes real pixel data/dimensions. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class IconStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun sourceUriFor(
        width: Int,
        height: Int,
    ): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "source_${width}x$height.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    private fun sourceBytesFor(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    @Test
    fun `isImagePath is true only for absolute file paths, not emoji strings`() {
        assertTrue(IconStorage.isImagePath("/data/user/0/com.freqcast/files/station_icons/a.jpg"))
        assertFalse(IconStorage.isImagePath("📻"))
    }

    @Test
    fun `saveImage copies the picked image into app-private storage and it decodes back`() {
        val path = IconStorage.saveImage(context, sourceUriFor(64, 64))

        assertNotNull(path)
        assertTrue(IconStorage.isImagePath(path!!))
        assertTrue(File(path).exists())
        assertTrue(File(path).parentFile?.name == "station_icons")
        assertNotNull(IconStorage.decodeBitmap(path))
    }

    @Test
    fun `saveImage downscales an oversized image to the max dimension`() {
        val path = IconStorage.saveImage(context, sourceUriFor(2048, 1024))

        val decoded = IconStorage.decodeBitmap(path!!)
        assertNotNull(decoded)
        assertTrue(decoded!!.width <= 512)
        assertTrue(decoded.height <= 512)
    }

    @Test
    fun `decodeBitmap returns null for a missing file`() {
        assertNull(IconStorage.decodeBitmap("/no/such/file.jpg"))
    }

    @Test
    fun `delete removes a saved icon file`() {
        val path = IconStorage.saveImage(context, sourceUriFor(32, 32))!!
        assertTrue(File(path).exists())

        IconStorage.delete(path)

        assertTrue(!File(path).exists())
    }

    @Test
    fun `delete is a no-op for an emoji string or a path outside station_icons`() {
        val outsideFile = File(context.cacheDir, "not_an_icon.jpg").apply { writeText("x") }

        IconStorage.delete("📻")
        IconStorage.delete(outsideFile.absolutePath)

        assertTrue(outsideFile.exists())
    }

    @Test
    fun `delete is a no-op for null`() {
        IconStorage.delete(null)
    }

    @Test
    fun `saveImage returns null for an unresolvable uri`() {
        val path = IconStorage.saveImage(context, Uri.parse("content://nonexistent/authority/1"))

        assertNull(path)
    }

    @Test
    fun `saveImageBytes persists downloaded bytes into app-private storage and it decodes back`() {
        val path = IconStorage.saveImageBytes(context, sourceBytesFor(64, 64))

        assertNotNull(path)
        assertTrue(IconStorage.isImagePath(path!!))
        assertTrue(File(path).exists())
        assertTrue(File(path).parentFile?.name == "station_icons")
        assertNotNull(IconStorage.decodeBitmap(path))
    }

    @Test
    fun `saveImageBytes downscales an oversized image to the max dimension`() {
        val path = IconStorage.saveImageBytes(context, sourceBytesFor(2048, 1024))

        val decoded = IconStorage.decodeBitmap(path!!)
        assertNotNull(decoded)
        assertTrue(decoded!!.width <= 512)
        assertTrue(decoded.height <= 512)
    }

    @Test
    fun `saveImageBytes returns null for garbage bytes instead of throwing`() {
        val path = IconStorage.saveImageBytes(context, byteArrayOf(1, 2, 3, 4))

        assertNull(path)
    }

    /** A minimal but spec-correct raw 32bpp `.ico` container - see IcoDecoderTest for the full parser coverage. */
    private fun icoBytesFor(
        width: Int,
        height: Int,
    ): ByteArray {
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

        fun le32(v: Int) =
            byteArrayOf(
                (v and 0xFF).toByte(),
                ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(),
                ((v shr 24) and 0xFF).toByte(),
            )
        val rowStride = width * 4
        val colorData = ByteArray(rowStride * height) { -1 } // opaque white, BGRA
        val header =
            le32(40) + le32(width) + le32(height * 2) + le16(1) + le16(32) +
                le32(0) + le32(0) + le32(0) + le32(0) + le32(0) + le32(0)
        val image = header + colorData
        val dirHeader = le16(0) + le16(1) + le16(1)
        val entryHeader =
            byteArrayOf(width.toByte(), height.toByte(), 0, 0) + le16(1) + le16(32) + le32(image.size) + le32(22)
        return dirHeader + entryHeader + image
    }

    @Test
    fun `saveImageBytes decodes a raw ico favicon since BitmapFactory alone can't`() {
        val path = IconStorage.saveImageBytes(context, icoBytesFor(32, 32))

        assertNotNull(path)
        assertTrue(IconStorage.isImagePath(path!!))
        val decoded = IconStorage.decodeBitmap(path)
        assertNotNull(decoded)
        assertEquals(32, decoded!!.width)
    }
}
