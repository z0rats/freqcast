package com.urlradiodroid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.urlradiodroid.R
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.ui.theme.card_border
import com.urlradiodroid.ui.theme.card_surface
import com.urlradiodroid.ui.theme.card_surface_active
import com.urlradiodroid.ui.theme.glass_accent
import com.urlradiodroid.ui.theme.text_hint
import com.urlradiodroid.ui.theme.text_primary
import com.urlradiodroid.util.EmojiGenerator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class PlaybackStatus { PLAYING, PAUSED, STARTING, ERROR }

@Composable
fun NowPlayingBottomBar(
    station: RadioStation?,
    stations: List<RadioStation>,
    playbackStatus: PlaybackStatus,
    hasTimeshift: Boolean,
    isAtLive: Boolean,
    onPlayPauseClick: () -> Unit,
    onCardClick: () -> Unit,
    onSwitchStation: (RadioStation) -> Unit,
    onRewind5s: () -> Unit,
    onReturnToLive: () -> Unit,
    modifier: Modifier = Modifier,
    trackTitle: String? = null,
) {
    if (station == null) return

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetPx = remember { Animatable(0f) }

    val currentIndex = stations.indexOfFirst { it.id == station.id }
    val prevStation = if (currentIndex > 0) stations[currentIndex - 1] else null
    val nextStation = if (currentIndex in 0 until stations.size - 1) stations[currentIndex + 1] else null

    val switchThresholdPx = with(density) { 72.dp.toPx() }
    val maxDragPx = with(density) { 180.dp.toPx() }
    val overscrollMaxPx = with(density) { 48.dp.toPx() }
    val resistanceFactor = 0.3f

    val bounceSpec =
        spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        )
    val slideSpec = tween<Float>(durationMillis = 280)

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(80.dp)
                .clipToBounds(),
    ) {
        val cardWidth = maxWidth
        val gap = 14.dp
        val cardWidthPx = with(density) { cardWidth.toPx() }.toInt()
        val gapPx = with(density) { gap.toPx() }.toInt()
        val targetForPrevPx = (cardWidthPx + gapPx).toFloat()
        val targetForNextPx = -(cardWidthPx + gapPx).toFloat()

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .pointerInput(station.id, prevStation?.id, nextStation?.id, cardWidthPx, gapPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val current = offsetPx.value
                                when {
                                    current > switchThresholdPx && prevStation != null -> {
                                        scope.launch {
                                            offsetPx.animateTo(targetForPrevPx, slideSpec)
                                            onSwitchStation(prevStation)
                                            offsetPx.snapTo(0f)
                                        }
                                    }

                                    current < -switchThresholdPx && nextStation != null -> {
                                        scope.launch {
                                            offsetPx.animateTo(targetForNextPx, slideSpec)
                                            onSwitchStation(nextStation)
                                            offsetPx.snapTo(0f)
                                        }
                                    }

                                    else -> {
                                        scope.launch {
                                            offsetPx.animateTo(0f, bounceSpec)
                                        }
                                    }
                                }
                            },
                        ) { _, dragAmount ->
                            val hasPrev = prevStation != null
                            val hasNext = nextStation != null
                            val effectiveAmount =
                                when {
                                    dragAmount > 0 -> if (hasPrev) dragAmount else dragAmount * resistanceFactor
                                    else -> if (hasNext) dragAmount else dragAmount * resistanceFactor
                                }
                            val newOffset = offsetPx.value + effectiveAmount
                            val clamped =
                                when {
                                    hasPrev && hasNext -> newOffset.coerceIn(-maxDragPx, maxDragPx)
                                    hasPrev -> newOffset.coerceIn(-overscrollMaxPx, maxDragPx)
                                    hasNext -> newOffset.coerceIn(-maxDragPx, overscrollMaxPx)
                                    else -> newOffset.coerceIn(-overscrollMaxPx, overscrollMaxPx)
                                }
                            scope.launch { offsetPx.snapTo(clamped) }
                        }
                    },
        ) {
            SubcomposeLayout(
                modifier = Modifier.clickable(onClick = onCardClick),
            ) { constraints ->
                val rowWidth = cardWidthPx * 3 + gapPx * 2
                val rowConstraints = Constraints.fixed(rowWidth, constraints.maxHeight)
                val rowPlaceable =
                    subcompose("row") {
                        Row(
                            modifier = Modifier.height(80.dp),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            Box(modifier = Modifier.width(cardWidth).height(80.dp)) {
                                if (prevStation != null) {
                                    MiniPlayerCardPreview(station = prevStation)
                                }
                            }
                            Box(
                                modifier =
                                    Modifier
                                        .width(cardWidth)
                                        .height(80.dp)
                                        .clickable(onClick = onCardClick),
                            ) {
                                MiniPlayerCardFull(
                                    station = station,
                                    playbackStatus = playbackStatus,
                                    hasTimeshift = hasTimeshift,
                                    isAtLive = isAtLive,
                                    trackTitle = trackTitle,
                                    onPlayPauseClick = onPlayPauseClick,
                                    onRewind5s = onRewind5s,
                                    onReturnToLive = onReturnToLive,
                                )
                            }
                            Box(modifier = Modifier.width(cardWidth).height(80.dp)) {
                                if (nextStation != null) {
                                    MiniPlayerCardPreview(station = nextStation)
                                }
                            }
                        }
                    }.map { it.measure(rowConstraints) }.first()

                val offsetX = (-(cardWidthPx + gapPx) + offsetPx.value).roundToInt()
                layout(constraints.maxWidth, rowPlaceable.height) {
                    rowPlaceable.placeRelative(offsetX, 0)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerCardPreview(station: RadioStation) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .border(width = 1.dp, color = card_border, shape = RoundedCornerShape(40.dp)),
        colors = CardDefaults.cardColors(containerColor = card_surface),
        shape = RoundedCornerShape(40.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(card_surface_active),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                        station.customIcon
                            ?: EmojiGenerator.getEmojiForStation(station.name, station.streamUrl),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(4.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = text_primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.paused),
                    style = MaterialTheme.typography.bodySmall,
                    color = text_hint,
                )
            }
            Box(modifier = Modifier.size(52.dp))
            Box(modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun MiniPlayerCardFull(
    station: RadioStation,
    playbackStatus: PlaybackStatus,
    hasTimeshift: Boolean,
    isAtLive: Boolean,
    onPlayPauseClick: () -> Unit,
    onRewind5s: () -> Unit,
    onReturnToLive: () -> Unit,
    trackTitle: String? = null,
) {
    val isPlaying = playbackStatus == PlaybackStatus.PLAYING
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .border(width = 1.dp, color = card_border, shape = RoundedCornerShape(40.dp)),
        colors = CardDefaults.cardColors(containerColor = card_surface),
        shape = RoundedCornerShape(40.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(card_surface_active),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                        station.customIcon
                            ?: EmojiGenerator.getEmojiForStation(station.name, station.streamUrl),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(4.dp),
                )
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = text_primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (playbackStatus == PlaybackStatus.PLAYING) {
                            EqualizerBars()
                        }
                        Text(
                            text =
                                when {
                                    playbackStatus == PlaybackStatus.PLAYING && trackTitle != null -> trackTitle
                                    playbackStatus == PlaybackStatus.PLAYING -> stringResource(R.string.playing)
                                    playbackStatus == PlaybackStatus.STARTING -> stringResource(R.string.starting)
                                    playbackStatus == PlaybackStatus.PAUSED -> stringResource(R.string.paused)
                                    else -> stringResource(R.string.connection_failed)
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = text_hint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (hasTimeshift) {
                Box(
                    modifier =
                        Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(card_border),
                )
                IconButton(
                    onClick = onRewind5s,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(card_surface_active),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay,
                                contentDescription = stringResource(R.string.rewind_5s),
                                modifier = Modifier.size(18.dp),
                                tint = text_primary,
                            )
                            Text(
                                text = "5",
                                style = MaterialTheme.typography.labelSmall,
                                color = text_primary,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onReturnToLive,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isAtLive) glass_accent.copy(alpha = 0.22f) else card_surface_active,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = stringResource(R.string.live),
                            modifier = Modifier.size(16.dp),
                            tint = if (isAtLive) glass_accent else text_hint,
                        )
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        .clickable(onClick = onPlayPauseClick),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter =
                        painterResource(
                            if (isPlaying) R.drawable.ic_pause_circle else R.drawable.ic_play_circle,
                        ),
                    contentDescription =
                        if (isPlaying) {
                            stringResource(
                                R.string.pause,
                            )
                        } else {
                            stringResource(R.string.play)
                        },
                    modifier = Modifier.size(44.dp),
                    colorFilter = ColorFilter.tint(glass_accent),
                )
            }
        }
    }
}
