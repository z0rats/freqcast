package com.freqcast.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freqcast.R
import com.freqcast.data.RadioBrowserStation
import com.freqcast.ui.stationSubtitle
import com.freqcast.ui.theme.Spacing
import com.freqcast.ui.theme.card_border
import com.freqcast.ui.theme.card_surface
import com.freqcast.ui.theme.glass_accent
import com.freqcast.ui.theme.text_hint
import com.freqcast.ui.theme.text_primary
import com.freqcast.util.EmojiGenerator
import com.freqcast.util.VoteCountFormatter

/**
 * A single directory search result, shared by the "Найти станцию" (Discover) screen and the main
 * screen's search-catalog fallback (see `MainViewModel.CatalogFallbackState`) — same card, same
 * add/added affordance, in both places.
 */
@Composable
fun RadioBrowserResultCard(
    station: RadioBrowserStation,
    isAdded: Boolean,
    onAddClick: () -> Unit,
) {
    val context = LocalContext.current

    Card(
        modifier =
            Modifier.fillMaxWidth().widthIn(max = 600.dp).border(
                width = 1.dp,
                color = card_border,
                shape = MaterialTheme.shapes.large,
            ),
        colors = CardDefaults.cardColors(containerColor = card_surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = EmojiGenerator.getEmojiForStation(station.name, station.url),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.size(36.dp),
                )

                MarqueeText(
                    text = station.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = text_primary,
                    modifier = Modifier.weight(1f),
                )

                if (isAdded) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.discover_added),
                        tint = glass_accent,
                    )
                } else {
                    TextButton(onClick = onAddClick) {
                        Text(stringResource(R.string.discover_add), color = glass_accent)
                    }
                }
            }

            Row(
                modifier = Modifier.padding(start = 36.dp + Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stationSubtitle(station),
                    style = MaterialTheme.typography.bodySmall,
                    color = text_hint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (station.votes > 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = text_hint,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = VoteCountFormatter.format(station.votes),
                            style = MaterialTheme.typography.labelSmall,
                            color = text_hint,
                        )
                    }
                }

                if (station.sslError) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = stringResource(R.string.discover_ssl_warning),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }

                if (station.homepage.isNotBlank()) {
                    IconButton(
                        onClick = { openWebsite(context, station.homepage) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.visit_website),
                            tint = text_hint,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun openWebsite(
    context: Context,
    url: String,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // No app can handle the link (e.g. a malformed homepage URL); nothing sensible to do here.
    }
}
