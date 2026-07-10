package com.urlradiodroid.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.urlradiodroid.util.IconStorage

/** Decodes [customIcon] as an image file, if it is one; null for an emoji string or a missing/corrupt file. */
@Composable
fun rememberStationIconBitmap(customIcon: String?): Bitmap? {
    val path = customIcon?.takeIf(IconStorage::isImagePath)
    return path?.let { remember(it) { IconStorage.decodeBitmap(it) } }
}
