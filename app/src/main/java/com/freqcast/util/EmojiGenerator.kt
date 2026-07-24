package com.freqcast.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.toBitmap

object EmojiGenerator {
    /** Curated emoji shown as picker choices in the station icon picker. */
    val pickerEmojis get() = radioEmojis

    private val radioEmojis =
        listOf(
            "📻",
            "🎵",
            "🎶",
            "🎧",
            "🎤",
            "🎸",
            "🎹",
            "🥁",
            "🎺",
            "🎷",
            "🎼",
            "🎯",
            "⭐",
            "🌟",
            "✨",
            "💫",
            "🔥",
            "💎",
            "🎪",
            "🎭",
        )

    /** Default icon used for stations without a user-chosen emoji. */
    private const val DEFAULT_EMOJI = "📻"

    fun getEmojiForStation(
        name: String,
        url: String = "",
    ): String = DEFAULT_EMOJI

    fun getEmojiBitmap(
        emoji: String,
        size: Int = 128,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * 0.7f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT
            }
        val x = size / 2f
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(emoji, x, y, paint)
        return bitmap
    }
}
