package com.urlradiodroid.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.urlradiodroid.R
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.data.RadioStationRepository
import com.urlradiodroid.data.StationBackupJson
import com.urlradiodroid.ui.components.NowPlayingBottomBar
import com.urlradiodroid.ui.components.PlaybackStatus
import com.urlradiodroid.ui.components.StationItem
import com.urlradiodroid.ui.theme.Spacing
import com.urlradiodroid.ui.theme.URLRadioDroidTheme
import com.urlradiodroid.ui.theme.background_gradient_end
import com.urlradiodroid.ui.theme.background_gradient_mid
import com.urlradiodroid.ui.theme.background_gradient_start
import com.urlradiodroid.ui.theme.card_border
import com.urlradiodroid.ui.theme.card_surface
import com.urlradiodroid.ui.theme.card_surface_active
import com.urlradiodroid.ui.theme.glass_accent
import com.urlradiodroid.ui.theme.text_primary
import com.urlradiodroid.ui.theme.text_secondary
import com.urlradiodroid.util.StationShare
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val playbackServiceState = mutableStateOf<RadioPlaybackService?>(null)
    private var isBound = false

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                val binder = service as? RadioPlaybackService.LocalBinder
                playbackServiceState.value = binder?.getService()
                isBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackServiceState.value = null
                isBound = false
            }
        }

    private var viewModelRef: MainViewModel? = null

    private val addStationLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                // Reload stations when returning from AddStationActivity
                viewModelRef?.loadStations()
            }
        }

    private val discoverStationsLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            // DiscoverStationsActivity saves stations as the user browses rather than on a single
            // confirm/result, so always reload rather than gating on a result code.
            viewModelRef?.loadStations()
        }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { _ ->
            // Result handled by system; notification will show if granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()

        val repository = RadioStationRepository.create(this)
        val viewModelFactory = MainViewModel.provideFactory(repository)

        setContent {
            val playbackService by playbackServiceState
            URLRadioDroidTheme {
                val viewModel: MainViewModel = viewModel(factory = viewModelFactory)
                viewModelRef = viewModel

                // Load stations on start
                LaunchedEffect(Unit) {
                    viewModel.loadStations()
                }

                MainScreen(
                    viewModel = viewModel,
                    onAddStationClick = {
                        addStationLauncher.launch(Intent(this, AddStationActivity::class.java))
                    },
                    onDiscoverStationsClick = {
                        discoverStationsLauncher.launch(Intent(this, DiscoverStationsActivity::class.java))
                    },
                    onStationEdit = { station ->
                        val intent =
                            Intent(this, AddStationActivity::class.java).apply {
                                putExtra(AddStationActivity.EXTRA_STATION_ID, station.id)
                            }
                        addStationLauncher.launch(intent)
                    },
                    onResume = {
                        viewModel.loadStations()
                    },
                    onStationDelete = { station ->
                        if (viewModel.getCurrentPlayingStationId() == station.id) {
                            playbackServiceState.value?.stopPlayback()
                            viewModel.updateCurrentPlayingStation(null)
                        }
                        viewModel.deleteStation(station)
                    },
                    onPlayStation = { station ->
                        playStation(station, viewModel)
                    },
                    onNowPlayingClick = { station ->
                        val intent =
                            Intent(this, PlaybackActivity::class.java).apply {
                                putExtra(PlaybackActivity.EXTRA_STATION_ID, station.id)
                                putExtra(PlaybackActivity.EXTRA_STATION_NAME, station.name)
                                putExtra(PlaybackActivity.EXTRA_STREAM_URL, station.streamUrl)
                            }
                        startActivity(intent)
                    },
                    playbackService = playbackService,
                    onStopPlayback = {
                        playbackServiceState.value?.stopPlayback()
                    },
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, RadioPlaybackService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload stations when returning from AddStationActivity
        viewModelRef?.loadStations()
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun playStation(
        station: RadioStation,
        viewModel: MainViewModel,
    ) {
        val isCurrentlyPlayingThis =
            viewModelRef?.getCurrentPlayingStationId() == station.id &&
                playbackServiceState.value?.isPlaying() == true
        if (isCurrentlyPlayingThis) {
            playbackServiceState.value?.stopPlayback()
            return
        }

        if (!isNetworkAvailable()) {
            android.widget.Toast
                .makeText(
                    this,
                    getString(R.string.error_network),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            return
        }

        viewModel.updateCurrentPlayingStation(station.id)

        Intent(this, RadioPlaybackService::class.java).apply {
            putExtra(RadioPlaybackService.EXTRA_STATION_NAME, station.name)
            putExtra(RadioPlaybackService.EXTRA_STREAM_URL, station.streamUrl)
            startForegroundService(this)
        }

        if (!isBound) {
            Intent(this, RadioPlaybackService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onAddStationClick: () -> Unit,
    onDiscoverStationsClick: () -> Unit,
    onStationEdit: (RadioStation) -> Unit,
    onStationDelete: (RadioStation) -> Unit,
    onPlayStation: (RadioStation) -> Unit,
    onNowPlayingClick: (RadioStation) -> Unit,
    onStopPlayback: () -> Unit,
    playbackService: RadioPlaybackService?,
    onResume: () -> Unit,
) {
    val stations by viewModel.filteredStations.collectAsState(initial = emptyList())
    val allStations by viewModel.stations.collectAsState(initial = emptyList())
    val searchQuery by viewModel.searchQuery.collectAsState()
    val currentPlayingStationId by viewModel.currentPlayingStationId.collectAsState()

    // Find current playing station by ID
    val currentPlayingStation =
        remember(currentPlayingStationId, allStations) {
            currentPlayingStationId?.let { id ->
                allStations.find { it.id == id }
            }
        }

    // Update playing state and timeshift/live state periodically
    var isPlaying by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf(false) }
    var hasTimeshift by remember { mutableStateOf(false) }
    var isAtLive by remember { mutableStateOf(true) }
    var isBufferingCurrentStation by remember { mutableStateOf(false) }
    var trackTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentPlayingStationId) {
        isStarting = true
        startError = false
    }
    LaunchedEffect(isStarting) {
        if (!isStarting) return@LaunchedEffect
        kotlinx.coroutines.delay(10_000)
        if (isStarting && !isPlaying) {
            startError = true
            isStarting = false
        }
    }
    // Reactively mirror the service's playback state instead of polling it on a timer.
    LaunchedEffect(playbackService, currentPlayingStation) {
        val svc = playbackService
        if (svc == null) {
            isPlaying = false
            hasTimeshift = false
            isAtLive = true
            isBufferingCurrentStation = false
            trackTitle = null
            return@LaunchedEffect
        }
        svc.playbackSnapshot.collect { snapshot ->
            val isCurrentStationPlaying =
                currentPlayingStation != null &&
                    snapshot.currentMediaId == currentPlayingStation.streamUrl &&
                    snapshot.isPlaying
            isPlaying = isCurrentStationPlaying
            hasTimeshift = snapshot.hasTimeshift
            isAtLive = snapshot.isAtLive
            isBufferingCurrentStation =
                snapshot.isBuffering && snapshot.currentMediaId == currentPlayingStation?.streamUrl
            trackTitle = snapshot.trackTitle
            if (isCurrentStationPlaying) {
                isStarting = false
                startError = false
            }
        }
    }

    val playbackStatus =
        when {
            startError -> PlaybackStatus.ERROR
            isPlaying -> PlaybackStatus.PLAYING
            isStarting -> PlaybackStatus.STARTING
            isBufferingCurrentStation -> PlaybackStatus.STARTING
            else -> PlaybackStatus.PAUSED
        }

    val onPlayStationWithState: (RadioStation) -> Unit = { station ->
        startError = false
        isStarting = true
        onPlayStation(station)
    }

    // Reload stations when screen is resumed
    LaunchedEffect(Unit) {
        onResume()
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val stationDeletedMessage = stringResource(R.string.station_deleted)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MainScreenEvent.StationDeleted -> {
                    val result =
                        snackbarHostState.showSnackbar(
                            message = stationDeletedMessage,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Short,
                        )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoDelete(event.station)
                    }
                }
            }
        }
    }

    val exportChooserTitle = stringResource(R.string.export_stations)
    val onExportClick: () -> Unit = {
        coroutineScope.launch {
            try {
                val json = viewModel.exportStationsJson()
                StationShare.share(context, json, exportChooserTitle, "url-radio-droid-stations")
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.export_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val shareStationTitle = stringResource(R.string.share_station)
    val onStationShareClick: (RadioStation) -> Unit = { station ->
        coroutineScope.launch {
            try {
                val json = StationBackupJson.toJsonArray(listOf(station))
                StationShare.share(context, json, shareStationTitle, station.name)
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.export_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                try {
                    val json =
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                            ?: throw java.io.IOException("Cannot open file")
                    val result = viewModel.importStationsJson(json)
                    Toast
                        .makeText(
                            context,
                            context.getString(R.string.import_result, result.imported, result.skipped),
                            Toast.LENGTH_LONG,
                        ).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.import_error), Toast.LENGTH_SHORT).show()
                }
            }
        }

    // Sync current playing station from service (single source of truth: ViewModel)
    LaunchedEffect(playbackService, allStations) {
        val currentMediaId = playbackService?.getPlayer()?.currentMediaItem?.mediaId
        val serviceIsPlaying = playbackService?.isPlaying() ?: false
        if (currentMediaId != null && serviceIsPlaying) {
            val foundStation = allStations.find { it.streamUrl == currentMediaId }
            if (foundStation != null && currentPlayingStationId != foundStation.id) {
                viewModel.updateCurrentPlayingStation(foundStation.id)
            }
        }
    }

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
            modifier = Modifier.fillMaxSize(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onAddStationClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_station),
                    )
                }
            },
            bottomBar = {
                AnimatedVisibility(visible = currentPlayingStation != null) {
                    currentPlayingStation?.let { station ->
                        val isStationPlaying = currentPlayingStationId == station.id && isPlaying
                        NowPlayingBottomBar(
                            station = station,
                            stations = stations,
                            playbackStatus = playbackStatus,
                            hasTimeshift = hasTimeshift,
                            isAtLive = isAtLive,
                            trackTitle = if (isStationPlaying) trackTitle else null,
                            onPlayPauseClick = {
                                if (isStationPlaying) onStopPlayback() else onPlayStationWithState(station)
                            },
                            onCardClick = { onNowPlayingClick(station) },
                            onSwitchStation = onPlayStationWithState,
                            onRewind5s = { playbackService?.seekBackward(5000L) },
                            onReturnToLive = {
                                isStarting = true
                                playbackService?.seekToLive()
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.lg),
                        )
                    }
                }
            },
        ) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(paddingValues),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = onDiscoverStationsClick,
                        label = { Text(stringResource(R.string.discover_stations)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.TravelExplore,
                                contentDescription = null,
                                tint = glass_accent,
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                        colors =
                            AssistChipDefaults.assistChipColors(
                                containerColor = card_surface,
                                labelColor = text_primary,
                            ),
                        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = card_border),
                    )
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                                tint = text_secondary,
                            )
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_stations)) },
                                onClick = {
                                    showMenu = false
                                    onExportClick()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_stations)) },
                                onClick = {
                                    showMenu = false
                                    importLauncher.launch(arrayOf("application/json"))
                                },
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = viewModel.stations.value.size > 4) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        placeholder = { Text(stringResource(R.string.search_stations)) },
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = card_surface,
                                unfocusedContainerColor = card_surface,
                                focusedTextColor = text_secondary,
                                unfocusedTextColor = text_secondary,
                            ),
                        shape = MaterialTheme.shapes.medium,
                    )
                }

                if (stations.isEmpty() && searchQuery.isBlank()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Weighted spacers (rather than Box centering) keep this truly centered
                        // regardless of the bottom inset Scaffold reserves for the FAB.
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier =
                                Modifier
                                    .size(96.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(card_surface_active),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "📻",
                                style = MaterialTheme.typography.displayMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = text_secondary,
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.no_stations),
                            style = MaterialTheme.typography.bodyLarge,
                            color = text_secondary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                } else {
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier.fillMaxSize(),
                        // Scaffold reserves space for bottomBar but not for the floating action
                        // button, which just overlays content - extra bottom padding here keeps
                        // the last station's play button clear of the FAB when the list is long.
                        contentPadding =
                            PaddingValues(
                                start = Spacing.md,
                                end = Spacing.md,
                                top = Spacing.sm,
                                bottom = 88.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        items(
                            items = stations,
                            key = { it.id },
                        ) { station ->
                            val isActive = currentPlayingStationId == station.id
                            val isStationPlaying = isActive && isPlaying
                            val isStationStarting = isActive && isStarting
                            val isStationStartError = isActive && startError
                            StationItem(
                                station = station,
                                isActive = isActive,
                                isPlaying = isStationPlaying,
                                isStarting = isStationStarting,
                                isStartError = isStationStartError,
                                trackTitle = if (isStationPlaying) trackTitle else null,
                                onPlayClick = {
                                    if (isStationPlaying) onStopPlayback() else onPlayStationWithState(station)
                                },
                                onFavoriteClick = { viewModel.toggleFavorite(station) },
                                onEditClick = { onStationEdit(station) },
                                onDeleteClick = { onStationDelete(station) },
                                onShareClick = { onStationShareClick(station) },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 600.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
