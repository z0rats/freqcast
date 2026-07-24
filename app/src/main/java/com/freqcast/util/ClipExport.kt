package com.freqcast.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.freqcast.ui.RadioPlaybackService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the last [durationMs] of a station's live timeshift buffer and shares it as an audio
 * file attachment (e.g. to Telegram, email, etc.), mirroring [StationShare]'s pattern. Scoped to
 * MP3/AAC streams - see [RadioPlaybackService.currentClipFormat] and
 * `TimeshiftController.exportClip`'s docs for why Ogg/HLS aren't supported.
 */
object ClipExport {
    private const val CLIP_DIR = "clips"

    // Must match the authority declared for the FileProvider in AndroidManifest.xml.
    private const val FILE_PROVIDER_AUTHORITY = "com.freqcast.fileprovider"

    fun export(
        context: Context,
        service: RadioPlaybackService,
        stationName: String,
        durationMs: Long,
        chooserTitle: String,
        onResult: (Boolean) -> Unit,
    ) {
        val format = service.currentClipFormat()
        if (format == null) {
            onResult(false)
            return
        }

        // Canonicalize: FileProvider matches this file's path against file_paths.xml roots by
        // string prefix, which breaks if cacheDir resolves through a symlink (e.g. macOS /var -> /private/var).
        val dir = File(context.cacheDir.canonicalFile, CLIP_DIR).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val destination = File(dir, "${sanitizeFileName(stationName)}_$timestamp.${format.extension}")

        service.exportClip(durationMs, destination) { success ->
            if (!success) {
                onResult(false)
                return@exportClip
            }
            val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, destination)
            val sendIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = format.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
            onResult(true)
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifBlank { "station" }
    }
}
