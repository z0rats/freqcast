package com.freqcast.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.freqcast.R
import com.freqcast.data.RadioStation
import com.freqcast.ui.theme.Spacing
import com.freqcast.ui.theme.card_border
import com.freqcast.ui.theme.card_surface
import com.freqcast.ui.theme.card_surface_active
import com.freqcast.ui.theme.glass_accent
import com.freqcast.ui.theme.text_hint
import com.freqcast.ui.theme.text_primary
import com.freqcast.util.EmojiGenerator
import com.freqcast.util.IconStorage
import kotlinx.coroutines.delay

@Composable
fun StationItem(
    station: RadioStation,
    isActive: Boolean,
    isPlaying: Boolean,
    isStarting: Boolean = false,
    isStartError: Boolean = false,
    isDragging: Boolean = false,
    trackTitle: String? = null,
    // True for exactly one station right after the curated pack is seeded on a fresh install -
    // plays a one-time peek-and-highlight animation demonstrating the swipe-to-reveal gesture,
    // then calls onSwipeHintConsumed(). See MainViewModel.swipeHintStationId.
    playSwipeHint: Boolean = false,
    onSwipeHintConsumed: () -> Unit = {},
    onPlayClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // Border/elevation glow driving the swipe hint - 0f the rest of the time, so it never affects
    // normal StationCard styling (see StationCard's isDragging/isActive border precedence).
    val highlightAnim = remember { Animatable(0f) }
    // Curated stations are read-only (ADR-0054): no Edit action, only Share and Delete.
    val canEdit = !station.isCurated
    // Wide enough to reveal the action buttons: n * 48dp + (n - 1) * 8dp spacing + 16dp end padding.
    val revealThreshold = if (canEdit) 184.dp else 120.dp
    val cardSpacing = Spacing.sm
    val revealThresholdPx = with(density) { revealThreshold.toPx() }
    val cardSpacingPx = with(density) { cardSpacing.toPx() }
    val maxOffsetPx = -(revealThresholdPx + cardSpacingPx)
    val swipeReveal = rememberSwipeRevealState(maxOffsetPx)

    LaunchedEffect(playSwipeHint) {
        if (!playSwipeHint) return@LaunchedEffect
        // Long initial delay: on a fresh install this plays right alongside the notification
        // permission system dialog (MainActivity.requestNotificationPermissionIfNeeded), which
        // would otherwise cover the very moment this is trying to demonstrate. Doesn't guarantee
        // the dialog is gone, but gives a real install-then-glance-at-the-list gap a chance to land
        // after it, rather than firing the instant the screen composes.
        delay(1800)
        highlightAnim.animateTo(1f, tween(300))
        // Reveals the full swipe threshold (every action), not just a peek - the point is to
        // show Delete specifically (rightmost, so first exposed) exists at all, not merely hint at it.
        swipeReveal.revealAnimated()
        delay(1600)
        swipeReveal.closeAnimated()
        highlightAnim.animateTo(0f, tween(300))
        onSwipeHintConsumed()
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(80.dp)
                .clipToBounds(),
    ) {
        AnimatedVisibility(
            visible = swipeReveal.isRevealed || swipeReveal.offsetPx < 0f,
            modifier =
                Modifier
                    .zIndex(0f)
                    .fillMaxWidth()
                    .padding(end = cardSpacing),
        ) {
            SwipeActionsBackground(
                onEditClick =
                    if (canEdit) {
                        {
                            swipeReveal.close()
                            onEditClick()
                        }
                    } else {
                        null
                    },
                onShareClick = {
                    swipeReveal.close()
                    onShareClick()
                },
                onDeleteClick = {
                    swipeReveal.close()
                    onDeleteClick()
                },
                deleteHighlightAlpha = highlightAnim.value,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        StationCard(
            station = station,
            isActive = isActive,
            isPlaying = isPlaying,
            isStarting = isStarting,
            isStartError = isStartError,
            isDragging = isDragging,
            highlightAlpha = highlightAnim.value,
            trackTitle = trackTitle,
            // A revealed row only closes. Otherwise the card tap toggles playback
            // (ADR-0063): pause when this station is playing, start otherwise.
            onToggleClick = {
                if (swipeReveal.isRevealed) swipeReveal.close() else onPlayClick()
            },
            isRevealed = swipeReveal.isRevealed,
            modifier =
                Modifier
                    .zIndex(1f)
                    .fillMaxWidth()
                    .padding(end = cardSpacing)
                    .then(
                        with(density) {
                            Modifier.offset(x = swipeReveal.offsetPx.toDp())
                        },
                    ).then(
                        // Swiping to reveal edit/share/delete is suppressed while this item is
                        // being drag-reordered (isDragging), rather than fighting the reorder
                        // gesture for the same pointer stream.
                        if (isDragging) Modifier else Modifier.swipeRevealTarget(swipeReveal),
                    ),
        )
    }
}

@Composable
private fun SwipeActionsBackground(
    // null hides the Edit button (curated stations, ADR-0054).
    onEditClick: (() -> Unit)?,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
    // Drives the pulsing ring/scale on the Delete button during the swipe hint (see StationItem's
    // playSwipeHint) - 0f the rest of the time, so it never affects the button's normal look.
    deleteHighlightAlpha: Float = 0f,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(
                    color = card_surface,
                    shape = MaterialTheme.shapes.large,
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onEditClick != null) {
                IconButton(
                    onClick = onEditClick,
                    modifier =
                        Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = MaterialTheme.shapes.medium,
                            ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            IconButton(
                onClick = onShareClick,
                modifier =
                    Modifier
                        .background(
                            color = MaterialTheme.colorScheme.secondary,
                            shape = MaterialTheme.shapes.medium,
                        ),
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.share_station),
                    tint = MaterialTheme.colorScheme.onSecondary,
                )
            }
            IconButton(
                onClick = onDeleteClick,
                modifier =
                    Modifier
                        .scale(1f + 0.18f * deleteHighlightAlpha)
                        .background(
                            color = MaterialTheme.colorScheme.error,
                            shape = MaterialTheme.shapes.medium,
                        ).border(
                            BorderStroke(2.dp, glass_accent.copy(alpha = deleteHighlightAlpha)),
                            MaterialTheme.shapes.medium,
                        ),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        }
    }
}

@Composable
private fun StationCard(
    station: RadioStation,
    isActive: Boolean,
    isPlaying: Boolean,
    isStarting: Boolean,
    isStartError: Boolean,
    isDragging: Boolean,
    highlightAlpha: Float,
    trackTitle: String?,
    onToggleClick: () -> Unit,
    isRevealed: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isActive) {
            card_surface_active
        } else {
            card_surface
        }
    // Spoken label for the row tap: Pause when it would pause, Play when it would start.
    // A revealed row only closes, so it gets no Play/Pause label (ADR-0063).
    val rowActionLabel = stringResource(if (isPlaying) R.string.pause else R.string.play)
    val rowTogglesPlayback = !isRevealed

    Card(
        onClick = onToggleClick,
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (rowTogglesPlayback) {
                        // Labels the card's existing click. The name column below merges only
                        // itself so TalkBack still reads name+status as one stop (ADR-0053).
                        Modifier.semantics {
                            onClick(label = rowActionLabel) {
                                onToggleClick()
                                true
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation =
                    if (isDragging ||
                        highlightAlpha > 0f
                    ) {
                        8.dp
                    } else {
                        0.dp
                    },
            ),
        shape = MaterialTheme.shapes.large,
        border =
            when {
                highlightAlpha > 0f -> BorderStroke(2.dp, glass_accent.copy(alpha = highlightAlpha))
                isDragging -> BorderStroke(2.dp, glass_accent)
                isActive -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                else -> BorderStroke(1.dp, card_border)
            },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconBitmap = rememberStationIconBitmap(station.customIcon)
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text =
                        station.customIcon?.takeUnless(IconStorage::isImagePath)
                            ?: EmojiGenerator.getEmojiForStation(station.name, station.streamUrl),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.size(36.dp),
                )
            }

            Column(
                // mergeDescendants: TalkBack reads the name and status/subtitle below as one
                // combined stop instead of two separate swipes. There is no play/pause sibling
                // on the row; this column's click is labeled Pause or Play, or unlabeled
                // close while swipe-reveal is open (ADR-0053, ADR-0063).
                modifier =
                    Modifier.weight(1f).semantics(mergeDescendants = true) {
                        if (isRevealed) {
                            onClick {
                                onToggleClick()
                                true
                            }
                        } else {
                            onClick(label = rowActionLabel) {
                                onToggleClick()
                                true
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = text_primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (station.isCurated) {
                        CuratedBadge()
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isActive && isPlaying) {
                        EqualizerBars()
                    }
                    // Reuses PlaybackPresentation's shared status-to-text mapping
                    // (playbackStateDescriptionRes) instead of a second copy of it - the same one
                    // NowPlayingBottomBar's status text and PlaybackScreen's transport button read.
                    // PAUSED (i.e. not active, or active with nothing interesting going on) isn't
                    // shown as a word here, unlike those two surfaces - a list row shows the
                    // station's own description/URL instead.
                    val status =
                        computePlaybackStatus(
                            isPlaying = isActive && isPlaying,
                            isBuffering = false,
                            isRetryPending = isActive && isStarting,
                            isConnectionBroken = isActive && isStartError,
                        )
                    Text(
                        text =
                            when {
                                status == PlaybackStatus.PLAYING && trackTitle != null -> trackTitle
                                status != PlaybackStatus.PAUSED -> stringResource(playbackStateDescriptionRes(status))
                                else -> station.description?.takeIf { it.isNotBlank() } ?: station.streamUrl
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = text_hint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Small pill marking a [RadioStation.isCurated] row, next to its name in [StationCard]. */
@Composable
private fun CuratedBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.station_curated_badge),
        style = MaterialTheme.typography.labelSmall,
        color = glass_accent,
        maxLines = 1,
        modifier =
            modifier
                .border(BorderStroke(1.dp, glass_accent.copy(alpha = 0.5f)), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
