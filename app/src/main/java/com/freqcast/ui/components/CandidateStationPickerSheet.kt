package com.freqcast.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freqcast.R
import com.freqcast.data.RadioBrowserStation
import com.freqcast.ui.stationSubtitle
import com.freqcast.ui.theme.Spacing
import com.freqcast.ui.theme.card_border
import com.freqcast.ui.theme.card_surface
import com.freqcast.ui.theme.text_hint
import com.freqcast.ui.theme.text_primary
import com.freqcast.util.EmojiGenerator
import com.freqcast.util.VoteCountFormatter

/**
 * Shown when [com.freqcast.data.StationUrlResolver.resolve]'s directory search finds several
 * stations sharing the pasted homepage and can't tell which one the user meant -
 * [AddStationUiState.candidateStations][com.freqcast.ui.AddStationUiState.candidateStations].
 * Each row is its own tap target (unlike a list with a separate "Add" button per row); dismissing
 * via the close icon, a scrim tap, or swiping down all route through [onDismiss] the same way
 * [ModalBottomSheet]'s own `onDismissRequest` does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CandidateStationPickerSheet(
    candidates: List<RadioBrowserStation>,
    onSelect: (RadioBrowserStation) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = card_surface,
        contentColor = text_primary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = card_border) },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(R.string.candidate_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = text_primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = text_hint,
                    )
                }
            }
            Text(
                text = stringResource(R.string.candidate_picker_hint),
                style = MaterialTheme.typography.bodySmall,
                color = text_hint,
                modifier = Modifier.padding(bottom = Spacing.sm),
            )
            // The sheet's own content slot already scrolls the whole column, but that would let a
            // long list push the header itself off-screen - capping just the list keeps the title/
            // hint pinned above it instead, same rationale AlertDialog's text slot would have for a
            // fixed max-height list.
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                items(candidates, key = { it.uuid.ifBlank { it.url } }) { candidate ->
                    CandidateStationRow(candidate) { onSelect(candidate) }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun CandidateStationRow(
    candidate: RadioBrowserStation,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onClick)
                    .padding(vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = EmojiGenerator.getEmojiForStation(candidate.name, candidate.url),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = text_primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stationSubtitle(candidate),
                        style = MaterialTheme.typography.bodySmall,
                        color = text_hint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (candidate.votes > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = text_hint,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = VoteCountFormatter.format(candidate.votes),
                                style = MaterialTheme.typography.labelSmall,
                                color = text_hint,
                            )
                        }
                    }
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = text_hint,
            )
        }
        HorizontalDivider(color = card_border)
    }
}
