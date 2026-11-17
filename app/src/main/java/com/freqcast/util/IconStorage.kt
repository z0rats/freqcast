package com.freqcast.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
    private const val TAG = "IconStorage"
    private const val ICONS_DIR = "station_icons"
    private const val ICONS_URI_SEGMENT = "icons"
    private const val MAX_DIMENSION_PX = 512

    /**
     * Largest side of the JPEG placed in [androidx.media3.common.MediaMetadata.artworkData].
     * Media3 turns those bytes into one bitmap per browse item; a 512px icon is about a megabyte
     * uncompressed and blows the binder transaction For you's child list travels in (ADR-0061).
     */
    internal const val AUTO_ARTWORK_MAX_PX = 256

    /**
     * Opaque pixels at least this bright are left on the JPEG's implicit black matte. Darker
     * marks on a transparent bitmap are composited onto white instead (ADR-0059).
     */
    private const val DARK_MARK_LUMINANCE = 128

    /** Below this share of pixels being transparent, the bitmap is treated as already opaque. */
    private const val MIN_TRANSPARENT_PERCENT = 5

    /**
     * Authority of [StationIconProvider]. Must match `${applicationId}.stationicons` in the manifest.
     * Not the backup/clip FileProvider — that one stays unexported (ADR-0061).
     */
    internal const val ICON_CONTENT_AUTHORITY = "com.freqcast.stationicons"

    /** `IconStorage.saveImage` writes `UUID.randomUUID().jpg`. Anything else is not a station icon. */
    private val ICON_FILE_NAME =
        Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.jpg""")

    fun isImagePath(customIcon: String): Boolean = customIcon.startsWith("/")

    /**
     * `content://` URI Android Auto can load for a stored icon (ADR-0061). Null for an emoji, a
     * missing file, a name that is not a UUID `.jpg`, or a path that is not a file directly
     * inside this app's [ICONS_DIR].
     */
    fun contentUri(
        context: Context,
        customIcon: String?,
    ): Uri? {
        val iconFile = storedIconFile(context, customIcon) ?: return null
        return iconContentUri(iconFile.name)
    }

    /** True for a URI [StationIconProvider] serves. */
    fun isOwnContentUri(uri: Uri): Boolean = uri.authority == ICON_CONTENT_AUTHORITY

    /**
     * JPEG bytes for [androidx.media3.common.MediaMetadata.artworkData] (ADR-0061). Same acceptance
     * rules as [contentUri]. A file already within [AUTO_ARTWORK_MAX_PX] is returned as stored;
     * anything larger is decoded and recompressed so the legacy browser's bitmap stays small.
     * Null when the file is missing or is not a decodable image.
     */
    fun artworkJpeg(
        context: Context,
        customIcon: String?,
    ): ByteArray? {
        val iconFile = storedIconFile(context, customIcon) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(iconFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth <= AUTO_ARTWORK_MAX_PX && bounds.outHeight <= AUTO_ARTWORK_MAX_PX) {
            return iconFile.readBytes()
        }
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > AUTO_ARTWORK_MAX_PX ||
            bounds.outHeight / sampleSize > AUTO_ARTWORK_MAX_PX
        ) {
            sampleSize *= 2
        }
        val decoded =
            BitmapFactory.decodeFile(
                iconFile.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: return null
        val scaled = scaleDown(decoded, AUTO_ARTWORK_MAX_PX)
        return try {
            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)) {
                    null
                } else {
                    out.toByteArray()
                }
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    internal fun iconContentUri(fileName: String): Uri =
        Uri
            .Builder()
            .scheme("content")
            .authority(ICON_CONTENT_AUTHORITY)
            .appendPath(ICONS_URI_SEGMENT)
            .appendPath(fileName)
            .build()

    /**
     * The JPEG [uri] may open, or null. Rejects every other authority, a path that is not
     * `icons/<uuid>.jpg`, and any file whose canonical path is not that name directly inside
     * [ICONS_DIR] — traversal, a symlink out of the directory, a missing file.
     */
    internal fun fileForIconUri(
        context: Context,
        uri: Uri,
    ): File? {
        if (uri.authority != ICON_CONTENT_AUTHORITY) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != ICONS_URI_SEGMENT) return null
        val name = segments[1]
        if (!ICON_FILE_NAME.matches(name)) return null
        val iconsDir = canonicalIconsDir(context) ?: return null
        val file =
            try {
                File(iconsDir, name).canonicalFile
            } catch (e: IOException) {
                return null
            }
        if (file.parentFile != iconsDir || !file.isFile) return null
        return file
    }

    private fun storedIconFile(
        context: Context,
        customIcon: String?,
    ): File? {
        if (customIcon == null || !isImagePath(customIcon)) return null
        val iconsDir = canonicalIconsDir(context) ?: return null
        val iconFile =
            try {
                File(customIcon).canonicalFile
            } catch (e: IOException) {
                return null
            }
        if (!iconFile.isFile || iconFile.parentFile != iconsDir) return null
        if (!ICON_FILE_NAME.matches(iconFile.name)) return null
        return iconFile
    }

    private fun canonicalIconsDir(context: Context): File? =
        try {
            File(context.filesDir, ICONS_DIR).canonicalFile
        } catch (e: IOException) {
            null
        }

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
                    ?: run {
                        Log.w(TAG, "saveImageBytes: IcoDecoder returned null for ${bytes.size} .ico bytes")
                        null
                    }
            } catch (e: Exception) {
                // Same broad-catch rationale as below: malformed bytes from an external host we
                // don't control should just mean "no icon", not a crash.
                Log.w(TAG, "saveImageBytes: .ico decode threw", e)
                null
            }
        }
        val tempFile = File.createTempFile("icon_src", null, context.cacheDir)
        return try {
            tempFile.outputStream().use { it.write(bytes) }
            persistDownscaled(context, tempFile) ?: run {
                Log.w(TAG, "saveImageBytes: BitmapFactory couldn't decode ${bytes.size} downloaded bytes")
                null
            }
        } catch (e: Exception) {
            // Same broad-catch rationale as saveImage: a favicon URL is an external host we don't
            // control, and any failure here should just mean "no icon", not a crash.
            Log.w(TAG, "saveImageBytes: failed", e)
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
     * A transparent dark mark is matted onto white first (ADR-0059) — the file stays a `.jpg`,
     * which is the only name [contentUri] will hand to Android Auto.
     */
    private fun persistBitmap(
        context: Context,
        bitmap: Bitmap,
    ): String {
        val scaled = downscaleIfNeeded(bitmap)
        val matted = matteDarkTransparentMark(scaled)
        val dir = File(context.filesDir, ICONS_DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { out -> matted.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        } finally {
            if (matted !== scaled) matted.recycle()
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
        return file.absolutePath
    }

    /**
     * JPEG has no alpha, and a decoded PNG stores transparent pixels as black. A dark mark on
     * that background (a sunburst favicon, a mask icon) compresses to a solid black disc, which
     * the station list then clips to a circle. Composite those onto white. A light mark already
     * reads against the black and is left as-is; a mostly opaque photo is left as-is too.
     */
    private fun matteDarkTransparentMark(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return bitmap
        var transparent = 0
        var opaque = 0
        var luminanceSum = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) < 16) {
                    transparent++
                } else {
                    opaque++
                    luminanceSum += luminance(color)
                }
            }
        }
        val samples = transparent + opaque
        if (opaque == 0 || transparent * 100 / samples < MIN_TRANSPARENT_PERCENT) return bitmap
        if (luminanceSum / opaque >= DARK_MARK_LUMINANCE) return bitmap
        val matted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(matted)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return matted
    }

    private fun luminance(color: Int): Int {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return (red * 30 + green * 59 + blue * 11) / 100
    }

    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap = scaleDown(bitmap, MAX_DIMENSION_PX)

    private fun scaleDown(
        bitmap: Bitmap,
        maxPx: Int,
    ): Bitmap {
        if (bitmap.width <= maxPx && bitmap.height <= maxPx) return bitmap
        val scale = maxPx.toFloat() / maxOf(bitmap.width, bitmap.height)
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

        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                // Default config can drop alpha. The matte in persistBitmap needs those pixels.
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }
}
