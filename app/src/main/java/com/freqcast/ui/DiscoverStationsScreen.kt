package com.freqcast.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freqcast.R
import com.freqcast.data.RadioBrowserStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.ui.theme.FreqcastTheme
import com.freqcast.ui.theme.Spacing
import com.freqcast.ui.theme.background_gradient_start
import com.freqcast.ui.theme.card_border
import com.freqcast.ui.theme.card_surface
import com.freqcast.ui.theme.freqcastGradientBackground
import com.freqcast.ui.theme.glass_accent
import com.freqcast.ui.theme.text_hint
import com.freqcast.ui.theme.text_primary
import com.freqcast.ui.theme.text_secondary
import com.freqcast.util.CountryFlagEmoji
import com.freqcast.util.EmojiGenerator
import com.freqcast.util.VoteCountFormatter
import java.util.Locale

class DiscoverStationsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = RadioStationRepository.create(this)
        val viewModelFactory = DiscoverStationsViewModel.provideFactory(repository, this)

        setContent {
            FreqcastTheme {
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
    val context = LocalContext.current
    var showLocationRationale by remember { mutableStateOf(false) }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.searchNearby() else viewModel.onLocationPermissionDenied()
        }

    fun requestNearbySearch() {
        val hasPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            viewModel.searchNearby()
        } else {
            showLocationRationale = true
        }
    }

    // Location services (GPS) being off isn't something this screen can observe directly — the
    // user has to leave to the system settings to turn it on. Retrying on every resume (e.g.
    // switching back from Settings, or just from the app switcher) means they don't have to
    // manually re-tap the Nearby chip afterwards for it to notice.
    val currentUiState = rememberUpdatedState(uiState)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME &&
                    currentUiState.value.mode == DiscoverSearchMode.NEARBY &&
                    currentUiState.value.errorRes == R.string.discover_location_unavailable
                ) {
                    requestNearbySearch()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showLocationRationale) {
        AlertDialog(
            onDismissRequest = { showLocationRationale = false },
            title = { Text(stringResource(R.string.discover_location_permission_title)) },
            text = { Text(stringResource(R.string.discover_location_permission_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    showLocationRationale = false
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }) {
                    Text(stringResource(R.string.discover_location_permission_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLocationRationale = false
                    viewModel.onLocationPermissionDenied()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Box(
        modifier = Modifier.fillMaxSize().freqcastGradientBackground(),
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
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    SearchModeChip(
                        label = stringResource(R.string.discover_search_mode_name),
                        selected = uiState.mode == DiscoverSearchMode.NAME,
                        onClick = { viewModel.onModeChange(DiscoverSearchMode.NAME) },
                    )
                    SearchModeChip(
                        label = stringResource(R.string.discover_search_mode_nearby),
                        selected = uiState.mode == DiscoverSearchMode.NEARBY,
                        icon = Icons.Default.LocationOn,
                        onClick = {
                            if (uiState.mode != DiscoverSearchMode.NEARBY) {
                                viewModel.onModeChange(DiscoverSearchMode.NEARBY)
                            }
                            requestNearbySearch()
                        },
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
                    SearchModeChip(
                        label = stringResource(R.string.discover_search_mode_language),
                        selected = uiState.mode == DiscoverSearchMode.LANGUAGE,
                        onClick = { viewModel.onModeChange(DiscoverSearchMode.LANGUAGE) },
                    )
                }

                if (uiState.mode == DiscoverSearchMode.NEARBY) {
                    if (uiState.locationPermissionDenied) {
                        CenteredHint(
                            stringResource(R.string.discover_location_permission_denied),
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        )
                    }
                } else {
                    val hint =
                        when (uiState.mode) {
                            DiscoverSearchMode.NAME -> stringResource(R.string.discover_search_hint_name)
                            DiscoverSearchMode.GENRE -> stringResource(R.string.discover_search_hint_genre)
                            DiscoverSearchMode.COUNTRY -> stringResource(R.string.discover_search_hint_country)
                            DiscoverSearchMode.LANGUAGE -> stringResource(R.string.discover_search_hint_language)
                            DiscoverSearchMode.NEARBY -> ""
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
                }

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
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon =
            icon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                }
            },
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
private fun CenteredHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = text_hint,
        modifier = modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
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
                horizontalAlignment = Alignment.CenterHorizontally,
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
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
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                Text(
                    text = stationSubtitle(station),
                    style = MaterialTheme.typography.bodySmall,
                    color = text_hint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

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
                    modifier = Modifier.size(20.dp),
                )
            }

            if (station.homepage.isNotBlank()) {
                IconButton(onClick = { openWebsite(context, station.homepage) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.visit_website),
                        tint = text_hint,
                    )
                }
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

private fun stationSubtitle(station: RadioBrowserStation): String {
    val distancePart =
        station.distanceKm?.let { km -> "📍 " + String.format(Locale.getDefault(), "%.1f km", km) }
    val countryPart = CountryFlagEmoji.from(station.countryCode)
    val bitratePart =
        listOfNotNull(
            station.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
            station.codec.takeIf { it.isNotBlank() }?.uppercase(),
        ).joinToString(" ")
            .takeIf { it.isNotBlank() }
    val parts =
        listOfNotNull(
            distancePart,
            countryPart,
            station.tags.takeIf { it.isNotBlank() },
            bitratePart,
        )
    return if (parts.isEmpty()) station.url else parts.joinToString(" • ")
}
