package com.freqcast.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.freqcast.R
import com.freqcast.ui.theme.FreqcastTheme
import com.freqcast.ui.theme.Spacing
import com.freqcast.ui.theme.card_border
import com.freqcast.ui.theme.card_surface
import com.freqcast.ui.theme.card_surface_active
import com.freqcast.ui.theme.freqcastGradientBackground
import com.freqcast.ui.theme.glass_accent
import com.freqcast.ui.theme.glass_primary
import com.freqcast.ui.theme.isLandscape
import com.freqcast.ui.theme.text_hint
import com.freqcast.ui.theme.text_primary
import com.freqcast.util.ClipExport
import com.freqcast.util.EmojiGenerator
import com.freqcast.util.formatOffsetFromLive
import com.freqcast.util.isNetworkAvailable

class PlaybackActivity : ComponentActivity() {
    // Must be Compose-observable state (not a plain var): onServiceConnected fires asynchronously
    // after the first composition, and a plain field mutation wouldn't trigger recomposition,
    // leaving PlaybackScreen's playbackService parameter (and everything derived from it -
    // isPlaying, track title, sleep timer countdown) permanently stuck at its initial null value.
    private val playbackServiceState = mutableStateOf<RadioPlaybackService?>(null)
    private var isBound = false
    private var stationName: String? = null
    private var streamUrl: String? = null

    companion object {
        const val EXTRA_STATION_ID = "station_id"
        const val EXTRA_STATION_NAME = "station_name"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_AUTO_PLAY = "auto_play"
    }

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                val binder = service as RadioPlaybackService.LocalBinder
                playbackServiceState.value = binder.getService()
                isBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackServiceState.value = null
                isBound = false
                Handler(Looper.getMainLooper()).post {
                    if (RadioPlaybackService.getAndClearConnectionError()) {
                        Toast
                            .makeText(
                                this@PlaybackActivity.applicationContext,
                                getString(R.string.connection_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                }
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted: Boolean ->
            if (!isGranted) {
                Toast
                    .makeText(
                        this,
                        "Notification permission is required for background playback",
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            FreqcastTheme {
                val playbackService by playbackServiceState
                PlaybackScreen(
                    stationName = stationName,
                    streamUrl = streamUrl,
                    playbackService = playbackService,
                    onBackClick = { finish() },
                    onPlayStopClick = { togglePlayback() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val intentStationName = intent.getStringExtra(EXTRA_STATION_NAME)
        val intentStreamUrl = intent.getStringExtra(EXTRA_STREAM_URL)

        if (intentStationName != null && intentStreamUrl != null) {
            stationName = intentStationName
            streamUrl = intentStreamUrl
            // Launched from an App Shortcut (long-press launcher icon): start playing immediately
            // instead of just showing the screen and waiting for the user to tap Play. At this
            // point the service isn't bound yet, so togglePlayback() takes its "service == null"
            // branch and starts it directly, same as a cold-start play from the station list.
            if (intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)) {
                togglePlayback()
            }
            return
        }

        if (playbackServiceState.value != null) {
            val currentMediaId =
                playbackServiceState.value
                    ?.getPlayer()
                    ?.currentMediaItem
                    ?.mediaId
            if (currentMediaId != null) {
                streamUrl = currentMediaId
                val serviceStationName = playbackServiceState.value?.getCurrentStationName()
                if (serviceStationName != null) {
                    stationName = serviceStationName
                }
                return
            }
        }

        if (intentStationName != null) {
            stationName = intentStationName
        }
        if (intentStreamUrl != null) {
            streamUrl = intentStreamUrl
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
        if (playbackServiceState.value != null) {
            val currentMediaId =
                playbackServiceState.value
                    ?.getPlayer()
                    ?.currentMediaItem
                    ?.mediaId
            if (currentMediaId != null && currentMediaId != streamUrl) {
                streamUrl = currentMediaId
                val serviceStationName = playbackServiceState.value?.getCurrentStationName()
                if (serviceStationName != null) {
                    stationName = serviceStationName
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    // Mirrors the slide-up entrance MainScreen's onNowPlayingClick sets up (see there): slides
    // this activity back down out of view on any close path — the composable back arrow's
    // onBackClick, the system back gesture/button, or a programmatic finish() — since they all
    // funnel through this one override.
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.stay, R.anim.slide_down_out)
    }

    private fun togglePlayback() {
        if (!isNetworkAvailable(this)) {
            Toast.makeText(this, getString(R.string.error_network), Toast.LENGTH_SHORT).show()
            return
        }

        val url = streamUrl ?: return

        if (playbackServiceState.value == null) {
            startService(url)
            return
        }

        val service = playbackServiceState.value ?: return

        if (service.isPlaying()) {
            service.stopPlayback()
        } else {
            startService(url)
        }
    }

    private fun startService(url: String) {
        Intent(this, RadioPlaybackService::class.java).apply {
            putExtra(RadioPlaybackService.EXTRA_STATION_NAME, stationName)
            putExtra(RadioPlaybackService.EXTRA_STREAM_URL, url)
            startForegroundService(this)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
    stationName: String?,
    streamUrl: String?,
    playbackService: RadioPlaybackService?,
    onBackClick: () -> Unit,
    onPlayStopClick: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().freqcastGradientBackground(),
    ) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stationName ?: stringResource(R.string.unknown_station)) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    colors =
                        androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                        ),
                )
            },
        ) { paddingValues ->
            NowPlayingContent(
                stationName = stationName,
                streamUrl = streamUrl,
                playbackService = playbackService,
                onPlayStopClick = onPlayStopClick,
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )
        }
    }
}

// Shared by PlaybackScreen (phone-sized full-screen, wrapped in the Scaffold/TopAppBar above) and
// MainScreen's wide-screen two-pane detail slot (embedded directly, no back button of its own).
@Composable
fun NowPlayingContent(
    stationName: String?,
    streamUrl: String?,
    playbackService: RadioPlaybackService?,
    onPlayStopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val displayName = stationName ?: stringResource(R.string.unknown_station)
    var isPlaying by remember { mutableStateOf(playbackService?.isPlaying() ?: false) }
    var trackTitle by remember { mutableStateOf<String?>(null) }
    var sleepTimerEndAtMs by remember { mutableStateOf<Long?>(null) }
    var hasTimeshift by remember { mutableStateOf(false) }
    var isAtLive by remember { mutableStateOf(true) }
    LaunchedEffect(playbackService) {
        val svc = playbackService ?: return@LaunchedEffect
        svc.playbackSnapshot.collect { snapshot ->
            val isCurrentStream = snapshot.currentMediaId == streamUrl
            isPlaying = snapshot.isPlaying && isCurrentStream
            trackTitle = if (isCurrentStream) snapshot.trackTitle else null
            sleepTimerEndAtMs = snapshot.sleepTimerEndAtMs
            hasTimeshift = snapshot.hasTimeshift && isCurrentStream
            isAtLive = snapshot.isAtLive
        }
    }

    // playbackSnapshot only fires on discrete events, but the buffer/offset grow every second,
    // so they need their own ticker rather than piggybacking on the collector above.
    var bufferedDurationMs by remember { mutableStateOf(0L) }
    var offsetFromLiveMs by remember { mutableStateOf(0L) }
    var clipFormatAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(playbackService, hasTimeshift) {
        val svc = playbackService
        if (svc == null || !hasTimeshift) {
            bufferedDurationMs = 0L
            offsetFromLiveMs = 0L
            clipFormatAvailable = false
            return@LaunchedEffect
        }
        while (true) {
            bufferedDurationMs = svc.bufferedDurationMs()
            offsetFromLiveMs = svc.offsetFromLiveMs()
            clipFormatAvailable = svc.currentClipFormat() != null
            kotlinx.coroutines.delay(1_000)
        }
    }

    var sleepTimerRemainingMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(sleepTimerEndAtMs) {
        val endAt = sleepTimerEndAtMs
        if (endAt == null) {
            sleepTimerRemainingMs = null
            return@LaunchedEffect
        }
        while (true) {
            val remaining = endAt - System.currentTimeMillis()
            if (remaining <= 0) {
                sleepTimerRemainingMs = null
                break
            }
            sleepTimerRemainingMs = remaining
            kotlinx.coroutines.delay(1_000)
        }
    }

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val landscape = isLandscape()
        val emoji = EmojiGenerator.getEmojiForStation(displayName, streamUrl ?: "")
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = if (landscape) 760.dp else 520.dp)
                    .border(width = 1.dp, color = card_border, shape = MaterialTheme.shapes.large),
            colors =
                CardDefaults.cardColors(
                    containerColor = glass_primary,
                ),
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            // Landscape mirrors the "art pane + controls pane" layout of Spotify/Apple
            // Music's now-playing screen: side-by-side instead of stacked, so nothing
            // needs scrolling on a phone's limited landscape height.
            if (landscape) {
                Row(
                    modifier = Modifier.padding(Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        StationIcon(emoji = emoji, size = 88.dp)
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = text_primary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1.2f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        if (isPlaying && trackTitle != null) {
                            TrackTitleRow(trackTitle = trackTitle ?: "", context = context)
                        }
                        PlayStopButton(isPlaying = isPlaying, onClick = onPlayStopClick, height = 64.dp)
                        if (hasTimeshift) {
                            TimeshiftControls(
                                isAtLive = isAtLive,
                                bufferedDurationMs = bufferedDurationMs,
                                offsetFromLiveMs = offsetFromLiveMs,
                                onSeekToOffset = { playbackService?.seekToOffsetFromLive(it) },
                                onRewind = { playbackService?.seekBackward(it) },
                                onSeekToLive = { playbackService?.seekToLive() },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            SleepTimerControl(
                                sleepTimerRemainingMs = sleepTimerRemainingMs,
                                playbackService = playbackService,
                                context = context,
                            )
                            if (hasTimeshift && clipFormatAvailable) {
                                ClipExportControl(
                                    stationName = displayName,
                                    bufferedDurationMs = bufferedDurationMs,
                                    playbackService = playbackService,
                                    context = context,
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    StationIcon(emoji = emoji, size = 64.dp)

                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = text_primary,
                        textAlign = TextAlign.Center,
                    )

                    if (isPlaying && trackTitle != null) {
                        TrackTitleRow(trackTitle = trackTitle ?: "", context = context)
                    }

                    PlayStopButton(isPlaying = isPlaying, onClick = onPlayStopClick, height = 72.dp)

                    if (hasTimeshift) {
                        TimeshiftControls(
                            isAtLive = isAtLive,
                            bufferedDurationMs = bufferedDurationMs,
                            offsetFromLiveMs = offsetFromLiveMs,
                            onSeekToOffset = { playbackService?.seekToOffsetFromLive(it) },
                            onRewind = { playbackService?.seekBackward(it) },
                            onSeekToLive = { playbackService?.seekToLive() },
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SleepTimerControl(
                            sleepTimerRemainingMs = sleepTimerRemainingMs,
                            playbackService = playbackService,
                            context = context,
                        )
                        if (hasTimeshift && clipFormatAvailable) {
                            ClipExportControl(
                                stationName = displayName,
                                bufferedDurationMs = bufferedDurationMs,
                                playbackService = playbackService,
                                context = context,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Buffered-window seek bar for timeshift (rewind) playback: drag the slider to scrub anywhere in
 * the buffer, or tap a step button for a quick jump. Replaces the old fixed "−5s" button with a
 * visual sense of how much buffer is available and where playback currently sits within it.
 */
@Composable
private fun TimeshiftControls(
    isAtLive: Boolean,
    bufferedDurationMs: Long,
    offsetFromLiveMs: Long,
    onSeekToOffset: (Long) -> Unit,
    onRewind: (Long) -> Unit,
    onSeekToLive: () -> Unit,
) {
    val bufferedSeconds = (bufferedDurationMs / 1000f).coerceAtLeast(1f)
    val livePositionSeconds = ((bufferedDurationMs - offsetFromLiveMs) / 1000f).coerceIn(0f, bufferedSeconds)
    // While the user is actively dragging, show their in-progress position instead of the
    // ticker-driven value so the thumb doesn't jump; committed to a real seek on release.
    var draggingSeconds by remember { mutableStateOf<Float?>(null) }
    val sliderDescription = stringResource(R.string.timeshift_slider)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Slider(
            value = draggingSeconds ?: livePositionSeconds,
            onValueChange = { draggingSeconds = it },
            onValueChangeFinished = {
                draggingSeconds?.let { target ->
                    val offsetMs = ((bufferedSeconds - target) * 1000).toLong().coerceAtLeast(0L)
                    onSeekToOffset(offsetMs)
                }
                draggingSeconds = null
            },
            valueRange = 0f..bufferedSeconds,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = sliderDescription },
        )
        Text(
            text = if (isAtLive) stringResource(R.string.live).uppercase() else formatOffsetFromLive(offsetFromLiveMs),
            style = MaterialTheme.typography.labelMedium,
            color = if (isAtLive) glass_accent else text_hint,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(15, 30, 60).forEach { seconds ->
                val description = stringResource(R.string.rewind_seconds, seconds)
                OutlinedButton(
                    onClick = { onRewind(seconds * 1000L) },
                    modifier = Modifier.semantics { contentDescription = description },
                ) {
                    Text("−${seconds}s")
                }
            }
            Button(onClick = onSeekToLive, enabled = !isAtLive) {
                Text(stringResource(R.string.go_live))
            }
        }
    }
}

@Composable
private fun StationIcon(
    emoji: String,
    size: Dp,
) {
    Text(
        text = emoji,
        style = MaterialTheme.typography.displayMedium,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun TrackTitleRow(
    trackTitle: String,
    context: Context,
) {
    val clipboardManager = LocalClipboardManager.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = trackTitle,
            style = MaterialTheme.typography.bodyLarge,
            color = text_primary.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false).basicMarquee(iterations = Int.MAX_VALUE),
        )
        IconButton(
            onClick = {
                clipboardManager.setText(AnnotatedString(trackTitle))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast
                        .makeText(
                            context,
                            context.getString(R.string.track_title_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.copy_track_title),
                modifier = Modifier.size(16.dp),
                tint = text_hint,
            )
        }
    }
}

@Composable
private fun PlayStopButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    height: Dp,
) {
    Button(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = if (isPlaying) stringResource(R.string.stop) else stringResource(R.string.play),
        )
    }
}

@Composable
private fun SleepTimerControl(
    sleepTimerRemainingMs: Long?,
    playbackService: RadioPlaybackService?,
    context: Context,
) {
    var sleepTimerDialogOpen by remember { mutableStateOf(false) }
    val sleepTimerActive = sleepTimerRemainingMs != null
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(
                    if (sleepTimerActive) glass_accent.copy(alpha = 0.22f) else card_surface_active,
                ).clickable { sleepTimerDialogOpen = true }
                .padding(horizontal = Spacing.md, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Bedtime,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (sleepTimerActive) glass_accent else text_hint,
        )
        Text(
            text =
                sleepTimerRemainingMs?.let { ms ->
                    val totalSeconds = (ms / 1000).toInt()
                    stringResource(
                        R.string.sleep_timer_active,
                        totalSeconds / 60,
                        totalSeconds % 60,
                    )
                } ?: stringResource(R.string.sleep_timer),
            color = if (sleepTimerActive) glass_accent else text_hint,
        )
    }

    if (sleepTimerDialogOpen) {
        AlertDialog(
            onDismissRequest = { sleepTimerDialogOpen = false },
            containerColor = card_surface,
            titleContentColor = text_primary,
            textContentColor = text_primary,
            title = { Text(stringResource(R.string.sleep_timer)) },
            text = {
                Column {
                    listOf(0, 15, 30, 45, 60).forEach { minutes ->
                        Text(
                            text =
                                if (minutes == 0) {
                                    stringResource(R.string.sleep_timer_off)
                                } else {
                                    stringResource(R.string.sleep_timer_minutes, minutes)
                                },
                            color = text_primary,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (minutes == 0) {
                                            playbackService?.cancelSleepTimer()
                                            Toast
                                                .makeText(
                                                    context,
                                                    context.getString(
                                                        R.string.sleep_timer_cancelled_toast,
                                                    ),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                        } else {
                                            playbackService?.setSleepTimer(minutes)
                                            Toast
                                                .makeText(
                                                    context,
                                                    context.getString(
                                                        R.string.sleep_timer_set_toast,
                                                        minutes,
                                                    ),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                        }
                                        sleepTimerDialogOpen = false
                                    }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sleepTimerDialogOpen = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

/**
 * Chip + preset-picker dialog for exporting the last N minutes of the live timeshift buffer as a
 * shareable audio file. Mirrors [SleepTimerControl]'s chip-opens-dialog shape. Only rendered by the
 * caller once [RadioPlaybackService.currentClipFormat] is non-null (MP3/AAC known - see
 * `TimeshiftController.exportClip`'s docs for why Ogg/HLS never reach here), so [bufferedDurationMs]
 * is the only thing gating which presets are offered: the buffer isn't a rolling window, so a
 * preset longer than what's actually been recorded so far is hidden rather than silently clamped.
 */
@Composable
private fun ClipExportControl(
    stationName: String,
    bufferedDurationMs: Long,
    playbackService: RadioPlaybackService?,
    context: Context,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(card_surface_active)
                .clickable(enabled = !exporting) { dialogOpen = true }
                .padding(horizontal = Spacing.md, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = text_hint,
        )
        Text(text = stringResource(R.string.export_clip), color = text_hint)
    }

    if (dialogOpen) {
        val availableMinutes = CLIP_EXPORT_PRESET_MINUTES.filter { it * 60_000L <= bufferedDurationMs }
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            containerColor = card_surface,
            titleContentColor = text_primary,
            textContentColor = text_primary,
            title = { Text(stringResource(R.string.export_clip)) },
            text = {
                if (availableMinutes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.export_clip_not_enough_buffer),
                        color = text_hint,
                    )
                } else {
                    Column {
                        availableMinutes.forEach { minutes ->
                            Text(
                                text = stringResource(R.string.export_clip_last_minutes, minutes),
                                color = text_primary,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            dialogOpen = false
                                            val svc = playbackService ?: return@clickable
                                            exporting = true
                                            ClipExport.export(
                                                context = context,
                                                service = svc,
                                                stationName = stationName,
                                                durationMs = minutes * 60_000L,
                                                chooserTitle = context.getString(R.string.export_clip),
                                            ) { success ->
                                                exporting = false
                                                if (!success) {
                                                    Toast
                                                        .makeText(
                                                            context,
                                                            context.getString(R.string.export_clip_failed_toast),
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                }
                                            }
                                        }.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

private val CLIP_EXPORT_PRESET_MINUTES = listOf(1, 5, 10, 30)
