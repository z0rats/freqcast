package com.freqcast.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.freqcast.R
import com.freqcast.data.RadioStationRepository
import com.freqcast.ui.components.PlaybackPresentation
import com.freqcast.ui.components.rememberPlaybackPresentation
import com.freqcast.ui.components.rememberStationIconBitmap
import com.freqcast.ui.theme.FreqcastTheme
import com.freqcast.ui.theme.Spacing
import com.freqcast.ui.theme.background_gradient_end
import com.freqcast.ui.theme.background_gradient_mid
import com.freqcast.ui.theme.background_gradient_start
import com.freqcast.ui.theme.card_border
import com.freqcast.ui.theme.card_surface
import com.freqcast.ui.theme.card_surface_active
import com.freqcast.ui.theme.freqcastGradientBackground
import com.freqcast.ui.theme.glass_accent
import com.freqcast.ui.theme.isLandscape
import com.freqcast.ui.theme.text_hint
import com.freqcast.ui.theme.text_primary
import com.freqcast.util.ClipExport
import com.freqcast.util.EmojiGenerator
import com.freqcast.util.IconStorage
import com.freqcast.util.formatOffsetFromLive
import com.freqcast.util.isNetworkAvailable
import kotlinx.coroutines.launch

class PlaybackActivity : AppCompatActivity() {
    // Must be Compose-observable state (not a plain var): onServiceConnected fires asynchronously
    // after the first composition, and a plain field mutation wouldn't trigger recomposition,
    // leaving PlaybackScreen's playbackService parameter (and everything derived from it -
    // isPlaying, track title, sleep timer countdown) permanently stuck at its initial null value.
    private val playbackServiceState = mutableStateOf<RadioPlaybackService?>(null)
    private val customIconState = mutableStateOf<String?>(null)
    private var isBound = false
    private var stationName: String? = null
    private var streamUrl: String? = null
    private val repository by lazy { RadioStationRepository.create(this) }

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
                val customIcon by customIconState
                PlaybackScreen(
                    stationName = stationName,
                    streamUrl = streamUrl,
                    customIcon = customIcon,
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
            loadCustomIcon(intentStreamUrl)
            // Launched from an App Shortcut (long-press launcher icon): start playing immediately
            // instead of just showing the screen and waiting for the user to tap Play. At this
            // point the service isn't bound yet, so togglePlayback() takes its "service == null"
            // branch and starts it directly, same as a cold-start play from the station list.
            if (intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)) {
                togglePlayback()
            }
            return
        }

        if (syncFromService()) return

        if (intentStationName != null) {
            stationName = intentStationName
        }
        if (intentStreamUrl != null) {
            streamUrl = intentStreamUrl
            loadCustomIcon(intentStreamUrl)
        }
    }

    private fun loadCustomIcon(url: String?) {
        val target = url ?: return
        lifecycleScope.launch {
            customIconState.value = repository.getStationByUrl(target)?.customIcon
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
        syncFromService()
    }

    private fun syncFromService(): Boolean {
        val currentMediaId =
            playbackServiceState.value
                ?.getPlayer()
                ?.currentMediaItem
                ?.mediaId ?: return false
        if (currentMediaId == streamUrl) return false
        streamUrl = currentMediaId
        playbackServiceState.value?.getCurrentStationName()?.let { stationName = it }
        loadCustomIcon(currentMediaId)
        return true
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
    customIcon: String?,
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
            val presentation = rememberPlaybackPresentation(playbackService, streamUrl)

            val context = LocalContext.current
            var lastShownConnectionErrorAt by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(presentation.connectionErrorAt) {
                val errorAt = presentation.connectionErrorAt
                if (errorAt != null && errorAt != lastShownConnectionErrorAt) {
                    lastShownConnectionErrorAt = errorAt
                    Toast.makeText(context, context.getString(R.string.connection_failed), Toast.LENGTH_LONG).show()
                }
            }

            NowPlayingContent(
                stationName = stationName,
                streamUrl = streamUrl,
                customIcon = customIcon,
                playbackService = playbackService,
                presentation = presentation,
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
    customIcon: String?,
    playbackService: RadioPlaybackService?,
    presentation: PlaybackPresentation,
    onPlayStopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val displayName = stationName ?: stringResource(R.string.unknown_station)
    val isPlaying = presentation.isPlaying
    val trackTitle = presentation.trackTitle
    val hasTimeshift = presentation.hasTimeshift
    val isAtLive = presentation.isAtLive
    val bufferedDurationMs = presentation.bufferedDurationMs
    val offsetFromLiveMs = presentation.offsetFromLiveMs
    val clipFormatAvailable = presentation.clipFormatAvailable

    var sleepTimerRemainingMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(presentation.sleepTimerEndAtMs) {
        val endAt = presentation.sleepTimerEndAtMs
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

    val landscape = isLandscape()
    val emoji = EmojiGenerator.getEmojiForStation(displayName, streamUrl ?: "")

    // No card: art bleeds straight into the gradient background (Spotify/Apple Music-style
    // "full-bleed hero" rather than a boxed panel) - see the playback-redesign artifact for why.
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Landscape mirrors the "art pane + controls pane" layout of Spotify/Apple Music's
        // now-playing screen: side-by-side instead of stacked, so nothing needs scrolling on a
        // phone's limited landscape height.
        if (landscape) {
            Row(
                modifier = Modifier.widthIn(max = 760.dp).padding(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    StationArt(
                        customIcon = customIcon,
                        fallbackEmoji = emoji,
                        tileSize = 148.dp,
                        showLiveBadge = isPlaying && isAtLive,
                    )
                    CopyableLabel(
                        text = displayName,
                        valueToCopy = displayName,
                        copiedMessage = stringResource(R.string.station_name_copied),
                        longClickLabel = stringResource(R.string.copy_station_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = text_primary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
                Column(
                    modifier = Modifier.weight(1.2f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (isPlaying && trackTitle != null) {
                        TrackTitleRow(trackTitle = trackTitle ?: "")
                    }
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
                    PlayPauseButton(isPlaying = isPlaying, onClick = onPlayStopClick, size = 60.dp)
                    PlayerDock {
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
            HeroArtBand(
                customIcon = customIcon,
                fallbackEmoji = emoji,
                showLiveBadge = isPlaying && isAtLive,
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .padding(horizontal = Spacing.lg)
                        .padding(top = Spacing.sm, bottom = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                CopyableLabel(
                    text = displayName,
                    valueToCopy = displayName,
                    copiedMessage = stringResource(R.string.station_name_copied),
                    longClickLabel = stringResource(R.string.copy_station_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = text_primary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )

                if (isPlaying && trackTitle != null) {
                    TrackTitleRow(trackTitle = trackTitle ?: "")
                }

                if (hasTimeshift) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    TimeshiftControls(
                        isAtLive = isAtLive,
                        bufferedDurationMs = bufferedDurationMs,
                        offsetFromLiveMs = offsetFromLiveMs,
                        onSeekToOffset = { playbackService?.seekToOffsetFromLive(it) },
                        onRewind = { playbackService?.seekBackward(it) },
                        onSeekToLive = { playbackService?.seekToLive() },
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xs))
                PlayPauseButton(isPlaying = isPlaying, onClick = onPlayStopClick, size = 64.dp)
                Spacer(modifier = Modifier.height(Spacing.xs))

                PlayerDock {
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

/**
 * Full-bleed hero art band for the portrait now-playing layout (replaces the old boxed glass
 * card - see the playback-redesign artifact): a large radial-gradient tile with
 * the station's icon, blending into the screen's own background gradient via a bottom scrim
 * instead of sitting inside a separate panel.
 */
@Composable
private fun HeroArtBand(
    customIcon: String?,
    fallbackEmoji: String,
    showLiveBadge: Boolean,
) {
    val iconBitmap = rememberStationIconBitmap(customIcon)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(background_gradient_end, background_gradient_mid, background_gradient_start),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(132.dp).clip(RoundedCornerShape(28.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = customIcon?.takeUnless(IconStorage::isImagePath) ?: fallbackEmoji,
                fontSize = 80.sp,
            )
        }
        if (showLiveBadge) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = Spacing.sm, vertical = 4.dp),
            ) {
                LiveTag(active = true)
            }
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, background_gradient_start))),
        )
    }
}

/**
 * Buffered-window seek bar for timeshift (rewind) playback: drag the slider to scrub anywhere in
 * the buffer, or tap a step button for a quick jump. Replaces the old fixed "−5s" button with a
 * visual sense of how much buffer is available and where playback currently sits within it.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
            colors =
                SliderDefaults.colors(
                    thumbColor = glass_accent,
                    activeTrackColor = glass_accent,
                    inactiveTrackColor = card_surface_active,
                ),
            thumb = { ScrubThumb() },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = sliderDescription },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isAtLive) "" else formatOffsetFromLive(offsetFromLiveMs),
                style = MaterialTheme.typography.labelMedium,
                color = text_hint,
            )
            LiveTag(active = isAtLive)
        }
        SegmentedRewindRow(
            isAtLive = isAtLive,
            onRewind = onRewind,
            onSeekToLive = onSeekToLive,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** Glowing scrub-bar thumb: a solid amber dot with a soft halo, replacing the barely-visible default. */
@Composable
private fun ScrubThumb() {
    Box(contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(20.dp).background(glass_accent.copy(alpha = 0.22f), CircleShape))
        Box(modifier = Modifier.size(12.dp).background(glass_accent, CircleShape))
    }
}

/**
 * −15s/−30s/−60s/Go-live as one continuous segmented control (single background, hairline
 * dividers between segments) instead of four separate chip boxes - reads as one control for one
 * job (jump within the timeshift buffer), not four unrelated buttons.
 */
@Composable
private fun SegmentedRewindRow(
    isAtLive: Boolean,
    onRewind: (Long) -> Unit,
    onSeekToLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(card_surface_active)
                .border(width = 1.dp, color = card_border, shape = MaterialTheme.shapes.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(15, 30, 60).forEachIndexed { index, seconds ->
            if (index > 0) SegmentDivider()
            SegmentButton(
                label = "−${seconds}s",
                highlighted = false,
                contentDescription = stringResource(R.string.rewind_seconds, seconds),
                onClick = { onRewind(seconds * 1000L) },
                modifier = Modifier.weight(1f),
            )
        }
        SegmentDivider()
        val goLiveLabel = stringResource(R.string.go_live)
        SegmentButton(
            label = goLiveLabel,
            highlighted = !isAtLive,
            enabled = !isAtLive,
            contentDescription = goLiveLabel,
            onClick = onSeekToLive,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SegmentDivider() {
    Box(modifier = Modifier.height(28.dp).width(1.dp).background(card_border))
}

@Composable
private fun SegmentButton(
    label: String,
    highlighted: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .background(if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { this.contentDescription = contentDescription }
                .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (highlighted) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    text_primary.copy(alpha = if (enabled) 1f else 0.4f)
                },
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Small dot + "LIVE" label, used both on [StationArt]'s badge and the timeshift scrubber's trailing edge. */
@Composable
private fun LiveTag(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (active) glass_accent else text_hint
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.FiberManualRecord,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(8.dp),
        )
        Text(
            text = stringResource(R.string.live).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Station artwork tile: the station's custom icon image if it has one, an emoji tile otherwise -
 * mirrors [com.freqcast.ui.components.StationItem]'s icon-vs-emoji fallback so the full player
 * shows the same artwork as the station list instead of always falling back to the generated emoji.
 */
@Composable
private fun StationArt(
    customIcon: String?,
    fallbackEmoji: String,
    tileSize: Dp,
    showLiveBadge: Boolean,
) {
    val iconBitmap = rememberStationIconBitmap(customIcon)
    Box(
        modifier =
            Modifier
                .size(tileSize)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.radialGradient(colors = listOf(background_gradient_end, background_gradient_start)),
                ).border(width = 1.dp, color = card_border, shape = RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(tileSize).clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = customIcon?.takeUnless(IconStorage::isImagePath) ?: fallbackEmoji,
                fontSize = (tileSize.value * 0.4f).sp,
            )
        }
        if (showLiveBadge) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = Spacing.sm, vertical = 4.dp),
            ) {
                LiveTag(active = true)
            }
        }
    }
}

/**
 * Bottom row for Sleep timer / Export clip - pill buttons in the same rounded-segment language as
 * [SegmentedRewindRow], rather than a bare icon-over-caption pair that reads as a different
 * control language from the rest of the screen.
 */
@Composable
private fun PlayerDock(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
        content = content,
    )
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tint = (if (highlighted) glass_accent else text_hint).copy(alpha = if (enabled) 1f else 0.4f)
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(if (highlighted) glass_accent.copy(alpha = 0.16f) else card_surface_active)
                .border(width = 1.dp, color = card_border, shape = RoundedCornerShape(50))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = Spacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TrackTitleRow(trackTitle: String) {
    CopyableLabel(
        text = trackTitle,
        valueToCopy = trackTitle,
        copiedMessage = stringResource(R.string.track_title_copied),
        longClickLabel = stringResource(R.string.copy_track_title),
        style = MaterialTheme.typography.bodyLarge,
        color = text_primary.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/**
 * Text label that copies [valueToCopy] to the clipboard on long-press (no visible copy button -
 * long-press is the whole affordance, same gesture as everywhere else in the OS for "copy this
 * text"). Tap is a no-op (indication disabled) since these labels have no other click action.
 */
@Composable
private fun CopyableLabel(
    text: String,
    valueToCopy: String,
    copiedMessage: String,
    longClickLabel: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier.combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onLongClickLabel = longClickLabel,
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(valueToCopy))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    }
                },
                onClick = {},
            ),
    )
}

/**
 * Circular transport control — the app's one primary action, so it gets the theme's primary
 * color plus a slow breathing glow (the "signature" touch of the full-bleed redesign - see the
 * playback-redesign artifact) while actually playing. The glow only animates when [isPlaying],
 * both because a paused/stopped station has nothing to signal "on air" about, and so this screen
 * doesn't run an infinite animation for as long as it's simply left open.
 *
 * Shows a pause icon while playing (not stop) even though tapping it actually calls
 * [RadioPlaybackService.stopPlayback] under the hood - matches [com.freqcast.ui.components.StationItem]
 * and [com.freqcast.ui.components.NowPlayingBottomBar], which both use the same pause glyph/label
 * for the identical action, so all three transport controls read as one consistent affordance.
 */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    size: Dp,
) {
    val description = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play)
    Box(contentAlignment = Alignment.Center) {
        if (isPlaying) {
            val glowTransition = rememberInfiniteTransition(label = "playGlow")
            val glowScale by
                glowTransition.animateFloat(
                    initialValue = 0.92f,
                    targetValue = 1.1f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    label = "playGlowScale",
                )
            Box(
                modifier =
                    Modifier
                        .size(size * 1.5f)
                        .background(
                            Brush.radialGradient(listOf(glass_accent.copy(alpha = 0.35f), Color.Transparent)),
                            CircleShape,
                        ).graphicsLayer {
                            scaleX = glowScale
                            scaleY = glowScale
                        },
            )
        }
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size / 2.2f),
            )
        }
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

    DockButton(
        icon = Icons.Default.Bedtime,
        label =
            sleepTimerRemainingMs?.let { ms ->
                val totalSeconds = (ms / 1000).toInt()
                stringResource(
                    R.string.sleep_timer_active,
                    totalSeconds / 60,
                    totalSeconds % 60,
                )
            } ?: stringResource(R.string.sleep_short),
        highlighted = sleepTimerActive,
        onClick = { sleepTimerDialogOpen = true },
    )

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

    DockButton(
        icon = Icons.Default.Share,
        label = stringResource(R.string.clip_short),
        highlighted = false,
        enabled = !exporting,
        onClick = { dialogOpen = true },
    )

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
