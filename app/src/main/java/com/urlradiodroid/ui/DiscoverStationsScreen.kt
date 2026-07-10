package com.urlradiodroid.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.urlradiodroid.R
import com.urlradiodroid.data.RadioBrowserStation
import com.urlradiodroid.data.RadioStationRepository
import com.urlradiodroid.ui.theme.Spacing
import com.urlradiodroid.ui.theme.URLRadioDroidTheme
import com.urlradiodroid.ui.theme.background_gradient_end
import com.urlradiodroid.ui.theme.background_gradient_mid
import com.urlradiodroid.ui.theme.background_gradient_start
import com.urlradiodroid.ui.theme.card_border
import com.urlradiodroid.ui.theme.card_surface
import com.urlradiodroid.ui.theme.glass_accent
import com.urlradiodroid.ui.theme.text_hint
import com.urlradiodroid.ui.theme.text_primary
import com.urlradiodroid.ui.theme.text_secondary
import com.urlradiodroid.util.EmojiGenerator

class DiscoverStationsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = RadioStationRepository.create(this)
        val viewModelFactory = DiscoverStationsViewModel.provideFactory(repository)

        setContent {
            URLRadioDroidTheme {
                val viewModel: DiscoverStationsViewModel = viewModel(factory = viewModelFactory)
                DiscoverStationsScreen(
                    viewModel = viewModel,
                    onBackClick = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverStationsScreen(
    viewModel: DiscoverStationsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    background_gradient_start,
                                    background_gradient_mid,
                                    background_gradient_end,
                                ),
                        ),
                ),
    ) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.discover_stations)) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = card_surface,
                            titleContentColor = text_primary,
                            navigationIconContentColor = text_primary,
                        ),
                )
            },
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    SearchModeChip(
                        label = stringResource(R.string.discover_search_mode_name),
                        selected = uiState.mode == DiscoverSearchMode.NAME,
                        onClick = { viewModel.onModeChange(DiscoverSearchMode.NAME) },
                    )
                    SearchModeChip(
                        label = stringResource(R.string.discover_search_mode_genre),
                        selected = uiState.mode == DiscoverSearchMode.GENRE,
                        onClick = { viewModel.onModeChange(DiscoverSearchMode.GENRE) },
                    )
                    SearchModeChip(
                        label = stringResource(R.string.discover_search_mode_country),
                        selected = uiState.mode == DiscoverSearchMode.COUNTRY,
                        onClick = { viewModel.onModeChange(DiscoverSearchMode.COUNTRY) },
                    )
                }

                val hint =
                    when (uiState.mode) {
                        DiscoverSearchMode.NAME -> stringResource(R.string.discover_search_hint_name)
                        DiscoverSearchMode.GENRE -> stringResource(R.string.discover_search_hint_genre)
                        DiscoverSearchMode.COUNTRY -> stringResource(R.string.discover_search_hint_country)
                    }
                TextField(
                    value = uiState.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    placeholder = { Text(hint) },
                    singleLine = true,
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = card_surface,
                            unfocusedContainerColor = card_surface,
                            focusedTextColor = text_primary,
                            unfocusedTextColor = text_primary,
                            focusedPlaceholderColor = text_hint,
                            unfocusedPlaceholderColor = text_hint,
                        ),
                    shape = MaterialTheme.shapes.medium,
                )

                DiscoverResultsContent(
                    uiState = uiState,
                    onAddClick = viewModel::addStation,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = card_surface,
                labelColor = text_secondary,
                selectedContainerColor = glass_accent,
                selectedLabelColor = background_gradient_start,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = card_border,
                selectedBorderColor = glass_accent,
            ),
    )
}

@Composable
private fun DiscoverResultsContent(
    uiState: DiscoverStationsUiState,
    onAddClick: (RadioBrowserStation) -> Unit,
) {
    when {
        uiState.isSearching -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = glass_accent)
            }
        }

        uiState.errorRes != null -> {
            CenteredMessage(stringResource(uiState.errorRes))
        }

        !uiState.hasSearched -> {
            CenteredMessage(stringResource(R.string.discover_prompt))
        }

        uiState.results.isEmpty() -> {
            CenteredMessage(stringResource(R.string.discover_empty_results))
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(items = uiState.results, key = { it.uuid.ifBlank { it.url } }) { station ->
                    DiscoverResultCard(
                        station = station,
                        isAdded = uiState.addedUrls.contains(station.url),
                        onAddClick = { onAddClick(station) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = text_secondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DiscoverResultCard(
    station: RadioBrowserStation,
    isAdded: Boolean,
    onAddClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth().border(
                width = 1.dp,
                color = card_border,
                shape = MaterialTheme.shapes.large,
            ),
        colors = CardDefaults.cardColors(containerColor = card_surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = EmojiGenerator.getEmojiForStation(station.name, station.url),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.size(36.dp),
            )

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
                Text(
                    text = stationSubtitle(station),
                    style = MaterialTheme.typography.bodySmall,
                    color = text_hint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

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
    }
}

private fun stationSubtitle(station: RadioBrowserStation): String {
    val parts =
        listOfNotNull(
            station.country.takeIf { it.isNotBlank() },
            station.tags.takeIf { it.isNotBlank() },
            station.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
        )
    return if (parts.isEmpty()) station.url else parts.joinToString(" • ")
}
