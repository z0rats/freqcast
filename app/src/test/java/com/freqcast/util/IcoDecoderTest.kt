package com.freqcast.util

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * Builds minimal but spec-correct `.ico` byte arrays by hand (real ICONDIR/ICONDIRENTRY/
 * BITMAPINFOHEADER structures, not real downloaded files) so [IcoDecoder] can be exercised without
 * network access, including the malformed-input cases a real favicon host would never send on
 * purpose. Native graphics (not the legacy Robolectric shadow) so [Bitmap.createBitmap]/
 * [BitmapFactory] decode real pixel data, same rationale as `IconStorageTest`.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class IcoDecoderTest {
    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Int): ByteArray =
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte(),
        )

    private fun alignTo4(n: Int) = (n + 3) and 3.inv()

    /** A raw (uncompressed) `BITMAPINFOHEADER` DIB entry, as embedded in a classic `.ico`. */
    private fun rawDibEntry(
        width: Int,
        height: Int,
        bitCount: Int,
        argb: IntArray,
        compression: Int = 0,
    ): ByteArray {
        val bytesPerPixel = bitCount / 8
        val rowStride = alignTo4(width * bytesPerPixel)
        val colorData = ByteArray(rowStride * height)
        for (y in 0 until height) {
            val destRow = (height - 1 - y) * rowStride
            for (x in 0 until width) {
                val pixel = argb[y * width + x]
                val p = destRow + x * bytesPerPixel
                colorData[p] = pixel.toByte()
                colorData[p + 1] = (pixel shr 8).toByte()
                colorData[p + 2] = (pixel shr 16).toByte()
                if (bitCount == 32) colorData[p + 3] = (pixel shr 24).toByte()
            }
        }
        val maskRowStride = alignTo4((width + 7) / 8)
        val maskData = ByteArray(maskRowStride * height)
        if (bitCount != 32) {
            for (y in 0 until height) {
                val destRow = (height - 1 - y) * maskRowStride
                for (x in 0 until width) {
                    val alpha = (argb[y * width + x] shr 24) and 0xFF
                    if (alpha == 0) {
                        val byteIndex = destRow + x / 8
                        val bitIndex = 7 - (x % 8)
                        maskData[byteIndex] = (maskData[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                    }
                }
            }
        }
        val header =
            le32(40) + le32(width) + le32(height * 2) + le16(1) + le16(bitCount) +
                le32(compression) + le32(0) + le32(0) + le32(0) + le32(0) + le32(0)
        return header + colorData + maskData
    }

    private fun pngEntry(
        width: Int,
        height: Int,
        argb: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(argb)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
    }

    /** Assembles a full `.ico` container (ICONDIR + ICONDIRENTRY headers + image data) from [entries]. */
    private fun buildIco(entries: List<Triple<Int, Int, ByteArray>>): ByteArray {
        val dirHeader = le16(0) + le16(1) + le16(entries.size)
        var offset = 6 + entries.size * 16
        val entryHeaders = ByteArrayOutputStream()
        val images = ByteArrayOutputStream()
        for ((width, height, data) in entries) {
            entryHeaders.write(
                byteArrayOf(
                    if (width ==
                        256
                    ) {
                        0
                    } else {
                        width.toByte()
                    },
                    if (height == 256) 0 else height.toByte(),
                    0,
                    0,
                ),
            )
            entryHeaders.write(le16(1))
            entryHeaders.write(le16(32))
            entryHeaders.write(le32(data.size))
            entryHeaders.write(le32(offset))
            images.write(data)
            offset += data.size
        }
        return dirHeader + entryHeaders.toByteArray() + images.toByteArray()
    }

    @Test
    fun `decode returns null for bytes that are not an ico container`() {
        assertNull(IcoDecoder.decode(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `decode reconstructs a 32bpp raw DIB entry`() {
        val pixels = IntArray(4 * 4) { Color.RED }
        val ico = buildIco(listOf(Triple(4, 4, rawDibEntry(4, 4, 32, pixels))))

        val bitmap = IcoDecoder.decode(ico)

        assertNotNull(bitmap)
        assertEquals(4, bitmap!!.width)
        assertEquals(4, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(0, 0))
    }

    @Test
    fun `decode reconstructs a 24bpp raw DIB entry and applies the AND mask as transparency`() {
        val pixels =
            IntArray(4 * 4) { i -> if (i == 0) Color.TRANSPARENT else Color.BLUE }
        val ico = buildIco(listOf(Triple(4, 4, rawDibEntry(4, 4, 24, pixels))))

        val bitmap = IcoDecoder.decode(ico)

        assertNotNull(bitmap)
        assertEquals(0, Color.alpha(bitmap!!.getPixel(0, 0)))
        assertEquals(255, Color.alpha(bitmap.getPixel(1, 0)))
        assertEquals(Color.BLUE, bitmap.getPixel(1, 0) or (0xFF shl 24))
    }

    @Test
    fun `decode extracts an embedded PNG entry directly`() {
        val ico = buildIco(listOf(Triple(32, 32, pngEntry(32, 32, Color.GREEN))))

        val bitmap = IcoDecoder.decode(ico)

        assertNotNull(bitmap)
        assertEquals(32, bitmap!!.width)
        assertEquals(Color.GREEN, bitmap.getPixel(0, 0))
    }

    @Test
    fun `decode picks the largest of multiple entries`() {
        val small = rawDibEntry(8, 8, 32, IntArray(8 * 8) { Color.RED })
        val large = rawDibEntry(32, 32, 32, IntArray(32 * 32) { Color.BLUE })
        val ico = buildIco(listOf(Triple(8, 8, small), Triple(32, 32, large)))

        val bitmap = IcoDecoder.decode(ico)

        assertNotNull(bitmap)
        assertEquals(32, bitmap!!.width)
        assertEquals(Color.BLUE, bitmap.getPixel(0, 0))
    }

    @Test
    fun `decode treats a 256px dimension byte of zero correctly`() {
        val entry = rawDibEntry(16, 16, 32, IntArray(16 * 16) { Color.RED })
        // Declares its ICONDIRENTRY dimensions as 256x256 (the byte-0-means-256 convention) even
        // though the actual embedded DIB is 16x16 - the decoder should still pick this as "largest"
        // over a genuinely small entry, based on the declared (not actual) size.
        val ico =
            buildIco(
                listOf(
                    Triple(8, 8, rawDibEntry(8, 8, 32, IntArray(8 * 8) { Color.GREEN })),
                    Triple(256, 256, entry),
                ),
            )

        val bitmap = IcoDecoder.decode(ico)

        assertNotNull(bitmap)
        assertEquals(16, bitmap!!.width)
        assertEquals(Color.RED, bitmap.getPixel(0, 0))
    }

    @Test
    fun `decode returns null for an indexed 8bpp entry`() {
        val header = le32(40) + le32(8) + le32(16) + le16(1) + le16(8) + le32(0) + le32(0) + le32(0) + le32(0) + le32(0)
        val ico = buildIco(listOf(Triple(8, 8, header + ByteArray(64))))

        assertNull(IcoDecoder.decode(ico))
    }

    @Test
    fun `decode returns null for a compressed DIB entry`() {
        val pixels = IntArray(4 * 4) { Color.RED }
        val entry = rawDibEntry(4, 4, 32, pixels, compression = 3)
        val ico = buildIco(listOf(Triple(4, 4, entry)))

        assertNull(IcoDecoder.decode(ico))
    }

    @Test
    fun `decode returns null when a declared entry length overruns the buffer`() {
        val ico = buildIco(listOf(Triple(4, 4, rawDibEntry(4, 4, 32, IntArray(4 * 4) { Color.RED }))))
        val truncated = ico.copyOf(ico.size - 20)

        assertNull(IcoDecoder.decode(truncated))
    }
}
