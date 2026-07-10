package com.urlradiodroid.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Shares a stations JSON backup (bulk or single-station) as a file attachment (e.g. to Telegram, email, etc.). */
object StationShare {
    private const val BACKUP_DIR = "backups"

    // Must match the authority declared for the FileProvider in AndroidManifest.xml.
    private const val FILE_PROVIDER_AUTHORITY = "com.urlradiodroid.fileprovider"

    /** [fileName] is used as-is if it already ends in `.json`, otherwise `.json` is appended. */
    fun share(
        context: Context,
        json: String,
        chooserTitle: String,
        fileName: String,
    ) {
        // Canonicalize: FileProvider matches this file's path against file_paths.xml roots by
        // string prefix, which breaks if cacheDir resolves through a symlink (e.g. macOS /var -> /private/var).
        val dir = File(context.cacheDir.canonicalFile, BACKUP_DIR).apply { mkdirs() }
        val file = File(dir, sanitizeFileName(fileName))
        file.writeText(json)

        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val withExtension = if (cleaned.endsWith(".json", ignoreCase = true)) cleaned else "$cleaned.json"
        return withExtension.ifBlank { "station.json" }
    }
}
