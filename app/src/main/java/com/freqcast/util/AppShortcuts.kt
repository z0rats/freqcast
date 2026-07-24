package com.freqcast.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.freqcast.R
import com.freqcast.data.RadioStation
import com.freqcast.ui.PlaybackActivity
import com.freqcast.ui.playback.PlaybackStateStore

/**
 * Publishes up to 2 dynamic App Shortcuts (long-press the launcher icon) for one-tap playback:
 * the last-played station (from [PlaybackStateStore]) and the first station in manual list order
 * (same `sortOrder` ordering as [RadioStation]'s DB query) — a stand-in "quick access" slot that
 * replaced the old top-favorite shortcut once favorites/pinning was removed in favor of
 * drag-to-reorder. Both shortcuts always show the auto-generated emoji, not
 * [RadioStation.customIcon] — same scope boundary as the notification's large icon and
 * PlaybackScreen (see AGENTS.md), since the last-played entry only has a name/URL from
 * [PlaybackStateStore], not a full [RadioStation] to read a custom icon off of.
 */
object AppShortcuts {
    private const val ID_LAST_PLAYED = "last_played"
    private const val ID_FIRST_STATION = "first_station"

    /** Call whenever the station list changes; recomputes and replaces the whole shortcut set. */
    fun refresh(
        context: Context,
        stations: List<RadioStation>,
    ) {
        val lastPlayed = PlaybackStateStore(context).restore()
        val firstStation = stations.firstOrNull()

        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        if (lastPlayed != null) {
            shortcuts +=
                buildShortcut(
                    context = context,
                    id = ID_LAST_PLAYED,
                    stationName = lastPlayed.stationName ?: context.getString(R.string.unknown_station),
                    streamUrl = lastPlayed.streamUrl,
                    longLabel = context.getString(R.string.shortcut_last_played, lastPlayed.stationName ?: ""),
                    rank = 0,
                )
        }
        if (firstStation != null && firstStation.streamUrl != lastPlayed?.streamUrl) {
            shortcuts +=
                buildShortcut(
                    context = context,
                    id = ID_FIRST_STATION,
                    stationName = firstStation.name,
                    streamUrl = firstStation.streamUrl,
                    longLabel = context.getString(R.string.shortcut_first_station, firstStation.name),
                    rank = 1,
                )
        }

        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    private fun buildShortcut(
        context: Context,
        id: String,
        stationName: String,
        streamUrl: String,
        longLabel: String,
        rank: Int,
    ): ShortcutInfoCompat {
        val emoji = EmojiGenerator.getEmojiForStation(stationName, streamUrl)
        val intent =
            Intent(context, PlaybackActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(PlaybackActivity.EXTRA_STATION_NAME, stationName)
                putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
                putExtra(PlaybackActivity.EXTRA_AUTO_PLAY, true)
            }
        return ShortcutInfoCompat
            .Builder(context, id)
            .setShortLabel(stationName)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithBitmap(EmojiGenerator.getEmojiBitmap(emoji)))
            .setRank(rank)
            .setIntent(intent)
            .build()
    }
}
