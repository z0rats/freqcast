package com.freqcast.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes the subset of the `.ico` container format real-world favicons actually use - unlike
 * every other format this app downloads as a station icon, [BitmapFactory] has no support for
 * `.ico` at all, so a favicon served that way (still the default on most sites' plain
 * `<link rel="icon">`) would otherwise always fail and fall back to the auto-generated emoji.
 *
 * Parses the outer ICONDIR/ICONDIRENTRY structure (see the
 * [ICO spec](https://en.wikipedia.org/wiki/ICO_(file_format))) to find the largest embedded image,
 * then either:
 * - decodes it directly via [BitmapFactory] if it's itself a PNG (the modern convention for large
 *   entries, e.g. a 256x256 icon), or
 * - reconstructs an uncompressed 24/32bpp `BITMAPINFOHEADER` DIB by hand (the older raw-bitmap
 *   form most small icons still use).
 *
 * Anything else - a compressed DIB, or an indexed/1-8bpp palette image - returns null, same as any
 * other undecodable icon; this only needs to cover what's actually common, not the entire spec.
 * Every offset read is bounds-checked and every declared dimension capped, since this parses bytes
 * from an arbitrary external host this app doesn't control.
 */
object IcoDecoder {
    private const val ICO_TYPE = 1
    private const val ICON_DIR_ENTRY_SIZE = 16
    private const val BITMAPINFOHEADER_SIZE = 40
    private const val MAX_DIMENSION = 1024

    fun decode(bytes: ByteArray): Bitmap? {
        val best = largestEntry(bytes) ?: return null
        val (offset, length) = best
        val entry = bytes.copyOfRange(offset, offset + length)
        return if (looksLikePng(entry)) {
            BitmapFactory.decodeByteArray(entry, 0, entry.size)
        } else {
            decodeRawDib(entry)
        }
    }

    /** The image offset/length (within [bytes]) of the ICONDIRENTRY with the largest pixel area. */
    private fun largestEntry(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 6) return null
        val reserved = readU16LE(bytes, 0)
        val type = readU16LE(bytes, 2)
        val count = readU16LE(bytes, 4)
        if (reserved != 0 || type != ICO_TYPE || count <= 0) return null

        var bestOffset = -1
        var bestLength = -1
        var bestArea = -1
        for (i in 0 until count) {
            val entryStart = 6 + i * ICON_DIR_ENTRY_SIZE
            if (entryStart + ICON_DIR_ENTRY_SIZE > bytes.size) break
            val width = iconDimension(bytes[entryStart])
            val height = iconDimension(bytes[entryStart + 1])
            val length = readU32LE(bytes, entryStart + 8)
            val offset = readU32LE(bytes, entryStart + 12)
            if (length <= 0 || offset < 0 || offset + length > bytes.size) continue
            val area = width * height
            if (area > bestArea) {
                bestArea = area
                bestOffset = offset
                bestLength = length
            }
        }
        return if (bestOffset < 0) null else bestOffset to bestLength
    }

    /** ICONDIRENTRY width/height bytes use 0 to mean 256, since a byte alone can't hold that value. */
    private fun iconDimension(byte: Byte): Int {
        val value = byte.toInt() and 0xFF
        return if (value == 0) 256 else value
    }

    private fun looksLikePng(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()

    /**
     * Reconstructs an uncompressed 24/32bpp DIB: [entry] is `BITMAPINFOHEADER` followed by
     * bottom-up, row-padded-to-4-bytes pixel data, then (24bpp only) a 1bpp AND transparency mask
     * in the same row layout. A 32bpp icon carries its own alpha channel and needs no mask - the
     * height field is double the actual image height precisely to account for the mask's rows,
     * XOR data on top and AND mask below.
     */
    private fun decodeRawDib(entry: ByteArray): Bitmap? {
        if (entry.size < BITMAPINFOHEADER_SIZE) return null
        val headerSize = readU32LE(entry, 0)
        if (headerSize < BITMAPINFOHEADER_SIZE) return null
        val width = readU32LE(entry, 4)
        val rawHeight = readU32LE(entry, 8)
        val bitCount = readU16LE(entry, 14)
        val compression = readU32LE(entry, 16)
        if (compression != 0) return null
        if (bitCount != 32 && bitCount != 24) return null
        if (width <= 0 || width > MAX_DIMENSION || rawHeight <= 0) return null
        val height = rawHeight / 2
        if (height <= 0 || height > MAX_DIMENSION) return null

        val bytesPerPixel = bitCount / 8
        val colorDataOffset = headerSize
        val rowStride = alignTo4(width * bytesPerPixel)
        val colorDataSize = rowStride * height
        if (colorDataOffset + colorDataSize > entry.size) return null

        val maskRowStride = alignTo4((width + 7) / 8)
        val maskOffset = colorDataOffset + colorDataSize
        val hasMask = bitCount == 24 && maskOffset + maskRowStride * height <= entry.size

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            // Rows are stored bottom-up.
            val srcRow = colorDataOffset + (height - 1 - y) * rowStride
            val maskRow = maskOffset + (height - 1 - y) * maskRowStride
            for (x in 0 until width) {
                val p = srcRow + x * bytesPerPixel
                val b = entry[p].toInt() and 0xFF
                val g = entry[p + 1].toInt() and 0xFF
                val r = entry[p + 2].toInt() and 0xFF
                val a =
                    when {
                        bitCount == 32 -> entry[p + 3].toInt() and 0xFF
                        hasMask -> if (isMaskBitSet(entry, maskRow, x)) 0 else 255
                        else -> 255
                    }
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun isMaskBitSet(
        bytes: ByteArray,
        rowStart: Int,
        x: Int,
    ): Boolean {
        val byte = bytes[rowStart + x / 8].toInt() and 0xFF
        return (byte shr (7 - (x % 8))) and 1 == 1
    }

    private fun alignTo4(n: Int): Int = (n + 3) and 3.inv()

    private fun readU16LE(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** Read as a plain (signed) [Int] - every caller already rejects negative results as malformed rather than huge. */
    private fun readU32LE(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
