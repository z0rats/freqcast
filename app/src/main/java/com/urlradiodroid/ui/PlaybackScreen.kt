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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.urlradiodroid.R
import com.urlradiodroid.ui.theme.URLRadioDroidTheme
import com.urlradiodroid.ui.theme.background_gradient_end
import com.urlradiodroid.ui.theme.background_gradient_mid
import com.urlradiodroid.ui.theme.background_gradient_start
import com.urlradiodroid.ui.theme.card_border
import com.urlradiodroid.ui.theme.card_surface_active
import com.urlradiodroid.ui.theme.glass_accent
import com.urlradiodroid.ui.theme.glass_primary
import com.urlradiodroid.ui.theme.text_hint
import com.urlradiodroid.ui.theme.text_primary
import com.urlradiodroid.util.EmojiGenerator

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
            URLRadioDroidTheme {
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

    private fun togglePlayback() {
        if (!isNetworkAvailable()) {
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
fun PlaybackScreen(
    stationName: String?,
    streamUrl: String?,
    playbackService: RadioPlaybackService?,
    onBackClick: () -> Unit,
    onPlayStopClick: () -> Unit,
) {
    val context = LocalContext.current
    val displayName = stationName ?: stringResource(R.string.unknown_station)
    var isPlaying by remember { mutableStateOf(playbackService?.isPlaying() ?: false) }
    var trackTitle by remember { mutableStateOf<String?>(null) }
    var sleepTimerEndAtMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(playbackService) {
        val svc = playbackService ?: return@LaunchedEffect
        svc.playbackSnapshot.collect { snapshot ->
            val isCurrentStream = snapshot.currentMediaId == streamUrl
            isPlaying = snapshot.isPlaying && isCurrentStream
            trackTitle = if (isCurrentStream) snapshot.trackTitle else null
            sleepTimerEndAtMs = snapshot.sleepTimerEndAtMs
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
                    title = { Text(displayName) },
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
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp)
                            .border(width = 1.dp, color = card_border, shape = RoundedCornerShape(24.dp)),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = glass_primary,
                        ),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement =
                            androidx.compose.foundation.layout.Arrangement
                                .spacedBy(16.dp),
                    ) {
                        Text(
                            text = EmojiGenerator.getEmojiForStation(displayName, streamUrl ?: ""),
                            style = MaterialTheme.typography.displayMedium,
                            modifier = Modifier.size(64.dp),
                        )

                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = text_primary,
                            textAlign = TextAlign.Center,
                        )

                        if (isPlaying && trackTitle != null) {
                            val clipboardManager = LocalClipboardManager.current
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = trackTitle ?: "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = text_primary.copy(alpha = 0.8f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(trackTitle ?: ""))
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

                        Button(
                            onClick = onPlayStopClick,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(72.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(
                                text =
                                    if (isPlaying) {
                                        stringResource(R.string.stop)
                                    } else {
                                        stringResource(R.string.play)
                                    },
                            )
                        }

                        var sleepTimerDialogOpen by remember { mutableStateOf(false) }
                        val sleepTimerActive = sleepTimerRemainingMs != null
                        Row(
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (sleepTimerActive) glass_accent.copy(alpha = 0.22f) else card_surface_active,
                                    ).clickable { sleepTimerDialogOpen = true }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                }
            }
        }
    }
}
