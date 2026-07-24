package com.freqcast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * Persists station icon images — picked via the system photo picker ([saveImage]) or downloaded
 * (a Discover station's favicon, [saveImageBytes]) — as downscaled JPEGs under
 * `filesDir/station_icons/`, so [com.freqcast.data.RadioStation.customIcon] can hold either
 * a short emoji string or an absolute path to one of these files (see the field's doc comment).
 * A leading "/" is what distinguishes a stored image path from an emoji string, since no emoji
 * starts with that character.
 */
object IconStorage {
    private const val ICONS_DIR = "station_icons"
    private const val MAX_DIMENSION_PX = 512

    fun isImagePath(customIcon: String): Boolean = customIcon.startsWith("/")

    /** Downscales and copies the picked image into app-private storage; returns its absolute path, or null on failure. */
    fun saveImage(
        context: Context,
        uri: Uri,
    ): String? {
        // Copied to a real file first (rather than decoding the ContentResolver stream directly)
        // since a picker Uri's stream isn't guaranteed to support the mark/reset BitmapFactory
        // needs for a two-pass (bounds then full) decode — e.g. a cloud-backed picker result.
        val tempFile = File.createTempFile("icon_src", null, context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            persistDownscaled(context, tempFile)
        } catch (e: Exception) {
            // Broad catch is deliberate: uri comes from the system photo picker, a boundary we
            // don't control, and any failure along this path (unresolvable uri, corrupt bytes,
            // an unreadable/stubbed stream) should just mean "no icon", not a crash.
            null
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Downscales and copies already-downloaded image bytes (e.g. a station's favicon fetched via
     * [com.freqcast.data.RadioBrowserApi.downloadFavicon]) into app-private storage; returns
     * its absolute path, or null on failure. Shares [persistDownscaled] with [saveImage] — the
     * only difference is where the source bytes came from (a picker `Uri` vs. already-downloaded
     * bytes). A `.ico` favicon (still the default on plenty of sites' plain `<link rel="icon">`)
     * is routed through [IcoDecoder] first, since [BitmapFactory] can't decode that container
     * format at all — everything else goes straight through the same file-based decode as
     * [saveImage].
     */
    fun saveImageBytes(
        context: Context,
        bytes: ByteArray,
    ): String? {
        if (isIco(bytes)) {
            return try {
                IcoDecoder.decode(bytes)?.let { persistBitmap(context, it) }
            } catch (e: Exception) {
                // Same broad-catch rationale as below: malformed bytes from an external host we
                // don't control should just mean "no icon", not a crash.
                null
            }
        }
        val tempFile = File.createTempFile("icon_src", null, context.cacheDir)
        return try {
            tempFile.outputStream().use { it.write(bytes) }
            persistDownscaled(context, tempFile)
        } catch (e: Exception) {
            // Same broad-catch rationale as saveImage: a favicon URL is an external host we don't
            // control, and any failure here should just mean "no icon", not a crash.
            null
        } finally {
            tempFile.delete()
        }
    }

    /** The `.ico` magic bytes: reserved=0, type=1 (icon), as a little-endian `ICONDIR` header. */
    private fun isIco(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0.toByte() &&
            bytes[1] == 0.toByte() &&
            bytes[2] == 1.toByte() &&
            bytes[3] == 0.toByte()

    /** Decodes [tempFile], downscales it, and writes it as a JPEG under [ICONS_DIR]; returns its absolute path, or null on failure. */
    private fun persistDownscaled(
        context: Context,
        tempFile: File,
    ): String? {
        val bitmap = decodeSampledBitmap(tempFile) ?: return null
        return persistBitmap(context, bitmap)
    }

    /**
     * Downscales [bitmap] if needed and writes it as a JPEG under [ICONS_DIR]; returns its
     * absolute path. Shared by [persistDownscaled] (whose [decodeSampledBitmap] already downscaled
     * during decode, so this is a no-op there) and [saveImageBytes]' `.ico` path, whose
     * [IcoDecoder] output is decoded at full size and hasn't been downscaled yet.
     */
    private fun persistBitmap(
        context: Context,
        bitmap: Bitmap,
    ): String {
        val scaled = downscaleIfNeeded(bitmap)
        val dir = File(context.filesDir, ICONS_DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        } finally {
            if (scaled !== bitmap) bitmap.recycle()
            scaled.recycle()
        }
        return file.absolutePath
    }

    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= MAX_DIMENSION_PX && bitmap.height <= MAX_DIMENSION_PX) return bitmap
        val scale = MAX_DIMENSION_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /** Deletes a previously saved icon file. No-op for anything that isn't one of our own files (e.g. an emoji string). */
    fun delete(customIcon: String?) {
        if (customIcon == null || !isImagePath(customIcon)) return
        val file = File(customIcon)
        if (file.parentFile?.name == ICONS_DIR) {
            file.delete()
        }
    }

    fun decodeBitmap(path: String): Bitmap? =
        if (File(path).exists()) {
            BitmapFactory.decodeFile(path)
        } else {
            null
        }

    private fun decodeSampledBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DIMENSION_PX || bounds.outHeight / sampleSize > MAX_DIMENSION_PX) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }
}
