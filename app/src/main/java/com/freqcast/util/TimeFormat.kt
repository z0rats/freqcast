package com.freqcast.util

/** Formats a duration as "−m:ss", e.g. 135_000L -> "−2:15". Used for timeshift offset-from-live displays. */
fun formatOffsetFromLive(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "−%d:%02d".format(minutes, seconds)
}
