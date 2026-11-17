package com.freqcast.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Read-only `content://` view of `filesDir/station_icons/` for Android Auto (ADR-0061).
 *
 * Exported with no read permission. [openFile] is the access check — one UUID `.jpg` directly
 * inside that directory, read-only. [query] returns [OpenableColumns] because a loader that
 * follows the openable-URI contract treats a null cursor as "no image". [openAssetFile] reports
 * the file length; the default wrapper reports unknown length and image decoders then fail.
 * Backups and clips stay on the unexported FileProvider.
 */
class StationIconProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException(uri.toString())
        val file =
            context?.let { IconStorage.fileForIconUri(it, uri) }
                ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ): AssetFileDescriptor {
        val file =
            context?.let { IconStorage.fileForIconUri(it, uri) }
                ?: throw FileNotFoundException(uri.toString())
        return AssetFileDescriptor(openFile(uri, mode), 0, file.length())
    }

    override fun getType(uri: Uri): String? =
        context?.let { ctx ->
            IconStorage.fileForIconUri(ctx, uri)?.let { "image/jpeg" }
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = context?.let { IconStorage.fileForIconUri(it, uri) } ?: return null
        val columns =
            projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = arrayOfNulls<Any>(columns.size)
        columns.forEachIndexed { index, column ->
            row[index] =
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
