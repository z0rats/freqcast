package com.urlradiodroid.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.urlradiodroid.R
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.ui.theme.Spacing
import com.urlradiodroid.ui.theme.card_border
import com.urlradiodroid.ui.theme.card_surface
import com.urlradiodroid.ui.theme.card_surface_active
import com.urlradiodroid.ui.theme.glass_accent
import com.urlradiodroid.ui.theme.text_hint
import com.urlradiodroid.ui.theme.text_primary
import com.urlradiodroid.util.EmojiGenerator
import com.urlradiodroid.util.IconStorage

@Composable
fun StationItem(
    station: RadioStation,
    isActive: Boolean,
    isPlaying: Boolean,
    isStarting: Boolean = false,
    isStartError: Boolean = false,
    trackTitle: String? = null,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isSwipeRevealed by remember { mutableStateOf(false) }
    // Wide enough to reveal 3 action buttons (Edit, Share, Delete): 3 * 48dp + 2 * 8dp spacing + 16dp end padding.
    val revealThreshold = 184.dp
    val cardSpacing = Spacing.sm
    val revealThresholdPx = with(density) { revealThreshold.toPx() }
    val cardSpacingPx = with(density) { cardSpacing.toPx() }

    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = tween(300),
        label = "swipeOffset",
    )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(80.dp)
                .clipToBounds(),
    ) {
        AnimatedVisibility(
            visible = isSwipeRevealed || dragOffset < 0f,
            modifier =
                Modifier
                    .zIndex(0f)
                    .fillMaxWidth()
                    .padding(end = cardSpacing),
        ) {
            SwipeActionsBackground(
                onEditClick = {
                    isSwipeRevealed = false
                    dragOffset = 0f
                    onEditClick()
                },
                onShareClick = {
                    isSwipeRevealed = false
                    dragOffset = 0f
                    onShareClick()
                },
                onDeleteClick = {
                    isSwipeRevealed = false
                    dragOffset = 0f
                    onDeleteClick()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        StationCard(
            station = station,
            isActive = isActive,
            isPlaying = isPlaying,
            isStarting = isStarting,
            isStartError = isStartError,
            trackTitle = trackTitle,
            onPlayClick = {
                if (isSwipeRevealed) {
                    isSwipeRevealed = false
                    dragOffset = 0f
                } else {
                    onPlayClick()
                }
            },
            onFavoriteClick = {
                if (isSwipeRevealed) {
                    isSwipeRevealed = false
                    dragOffset = 0f
                } else {
                    onFavoriteClick()
                }
            },
            onCardClick = {
                if (isSwipeRevealed) {
                    isSwipeRevealed = false
                    dragOffset = 0f
                }
            },
            modifier =
                Modifier
                    .zIndex(1f)
                    .fillMaxWidth()
                    .padding(end = cardSpacing)
                    .then(
                        with(density) {
                            Modifier.offset(x = animatedOffset.toDp())
                        },
                    ).pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val maxOffset = -(revealThresholdPx + cardSpacingPx)
                                val shouldReveal = dragOffset < maxOffset / 2
                                isSwipeRevealed = shouldReveal
                                if (shouldReveal) {
                                    dragOffset = maxOffset
                                } else {
                                    dragOffset = 0f
                                }
                            },
                        ) { _, dragAmount ->
                            val maxOffset = -(revealThresholdPx + cardSpacingPx)
                            val newOffset = (dragOffset + dragAmount).coerceIn(maxOffset, 0f)
                            dragOffset = newOffset
                            isSwipeRevealed = newOffset < maxOffset / 2
                        }
                    },
        )
    }
}

@Composable
private fun SwipeActionsBackground(
    onEditClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
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
                        .background(
                            color = MaterialTheme.colorScheme.error,
                            shape = MaterialTheme.shapes.medium,
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
    trackTitle: String?,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isActive) {
            card_surface_active
        } else {
            card_surface
        }

    Card(
        onClick = onCardClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.large,
        border =
            if (isActive) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            } else {
                BorderStroke(1.dp, card_border)
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
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    if (isActive && isPlaying) {
                        EqualizerBars()
                    }
                    Text(
                        text =
                            when {
                                isActive && isStartError -> stringResource(R.string.connection_failed)
                                isActive && isPlaying && trackTitle != null -> trackTitle
                                isActive && isPlaying -> stringResource(R.string.playing)
                                isActive && isStarting -> stringResource(R.string.starting)
                                else -> station.streamUrl
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = text_hint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            FavoriteIcon(
                isFavorite = station.isFavorite,
                onClick = onFavoriteClick,
            )

            PlayPauseIcon(
                isPlaying = isPlaying,
                onClick = onPlayClick,
            )
        }
    }
}

@Composable
private fun FavoriteIcon(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.size(36.dp)) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
            contentDescription =
                stringResource(if (isFavorite) R.string.unpin_station else R.string.pin_station),
            tint = if (isFavorite) glass_accent else text_hint,
        )
    }
}

@Composable
private fun PlayPauseIcon(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconRes =
        if (isPlaying) {
            R.drawable.ic_pause_circle
        } else {
            R.drawable.ic_play_circle
        }

    Box(
        modifier =
            modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
            modifier = Modifier.size(32.dp),
            colorFilter = ColorFilter.tint(glass_accent),
        )
    }
}
