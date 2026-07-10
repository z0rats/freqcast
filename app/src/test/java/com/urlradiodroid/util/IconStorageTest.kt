package com.urlradiodroid.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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

    @Test
    fun `isImagePath is true only for absolute file paths, not emoji strings`() {
        assertTrue(IconStorage.isImagePath("/data/user/0/com.urlradiodroid/files/station_icons/a.jpg"))
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
}
