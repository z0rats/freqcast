package com.urlradiodroid.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.urlradiodroid.R
import com.urlradiodroid.ui.PlaybackActivity
import com.urlradiodroid.ui.playback.WidgetStateStore
import com.urlradiodroid.util.EmojiGenerator

/**
 * Home screen widget: shows the last-played station with play/pause and skip controls, backed by
 * [WidgetStateStore] (pushed on every [com.urlradiodroid.ui.RadioPlaybackService] state change) so
 * it reflects live playback without polling. Button taps go through the `ActionCallback`s in
 * `WidgetActions.kt`, which start/stop [com.urlradiodroid.ui.RadioPlaybackService] the same way the
 * app's own UI does.
 */
class RadioWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val saved = WidgetStateStore(context).restore()
        val hasStation = saved != null
        val stationName = saved?.stationName ?: context.getString(R.string.widget_no_station)
        val statusText =
            when {
                saved == null -> context.getString(R.string.widget_tap_to_browse)
                saved.isPlaying -> context.getString(R.string.playing)
                else -> context.getString(R.string.paused)
            }
        val emoji = EmojiGenerator.getEmojiForStation(saved?.stationName ?: "", saved?.streamUrl ?: "")
        val iconBitmap = EmojiGenerator.getEmojiBitmap(emoji, 96)
        val openIntent =
            Intent(context, PlaybackActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (saved != null) {
                    putExtra(PlaybackActivity.EXTRA_STATION_NAME, saved.stationName)
                    putExtra(PlaybackActivity.EXTRA_STREAM_URL, saved.streamUrl)
                }
            }

        provideContent {
            RadioWidgetContent(
                stationName = stationName,
                statusText = statusText,
                isPlaying = saved?.isPlaying ?: false,
                hasStation = hasStation,
                iconBitmap = iconBitmap,
                openIntent = openIntent,
            )
        }
    }
}

/** Internal (not private) so [runGlanceAppWidgetUnitTest][androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest] can render it in isolation with fixed inputs. */
@Composable
internal fun RadioWidgetContent(
    stationName: String,
    statusText: String,
    isPlaying: Boolean,
    hasStation: Boolean,
    iconBitmap: Bitmap,
    openIntent: Intent,
) {
    val context = LocalContext.current
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF291E17)))
                .cornerRadius(24.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(openIntent)),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(iconBitmap),
                contentDescription = stationName,
                modifier = GlanceModifier.size(40.dp).cornerRadius(20.dp),
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = stationName,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold),
                )
                Text(
                    text = statusText,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Color(0xB3FFDFBF))),
                )
            }
            if (hasStation) {
                Spacer(modifier = GlanceModifier.width(4.dp))
                Image(
                    provider = ImageProvider(R.drawable.ic_skip_previous),
                    contentDescription = context.getString(R.string.widget_previous_station),
                    modifier =
                        GlanceModifier
                            .size(28.dp)
                            .clickable(actionRunCallback<PreviousStationAction>()),
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                Image(
                    provider = ImageProvider(if (isPlaying) R.drawable.ic_pause_circle else R.drawable.ic_play_circle),
                    contentDescription = context.getString(if (isPlaying) R.string.pause else R.string.play),
                    modifier =
                        GlanceModifier
                            .size(36.dp)
                            .clickable(actionRunCallback<TogglePlaybackAction>()),
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                Image(
                    provider = ImageProvider(R.drawable.ic_skip_next),
                    contentDescription = context.getString(R.string.widget_next_station),
                    modifier =
                        GlanceModifier
                            .size(28.dp)
                            .clickable(actionRunCallback<NextStationAction>()),
                )
            }
        }
    }
}
