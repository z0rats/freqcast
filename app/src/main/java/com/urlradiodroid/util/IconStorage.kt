package com.urlradiodroid.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * Persists station icon images picked via the system photo picker as downscaled JPEGs under
 * `filesDir/station_icons/`, so [com.urlradiodroid.data.RadioStation.customIcon] can hold either
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

            val bitmap = decodeSampledBitmap(tempFile) ?: return null
            val dir = File(context.filesDir, ICONS_DIR).apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            try {
                file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
                file.absolutePath
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            // Broad catch is deliberate: uri comes from the system photo picker, a boundary we
            // don't control, and any failure along this path (unresolvable uri, corrupt bytes,
            // an unreadable/stubbed stream) should just mean "no icon", not a crash.
            null
        } finally {
            tempFile.delete()
        }
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
