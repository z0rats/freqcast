package com.freqcast.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import com.freqcast.R
import com.freqcast.data.RadioBrowserApi
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.ui.playback.ClipFormat
import com.freqcast.ui.playback.ConnectionRetryPolicy
import com.freqcast.ui.playback.PlaybackStateStore
import com.freqcast.ui.playback.RadioBrowseTree
import com.freqcast.ui.playback.RetryDecision
import com.freqcast.ui.playback.SettingsStore
import com.freqcast.ui.playback.SleepTimerController
import com.freqcast.ui.playback.TimeshiftController
import com.freqcast.ui.playback.WidgetStateStore
import com.freqcast.util.EmojiGenerator
import com.freqcast.util.IconStorage
import com.freqcast.util.STREAM_USER_AGENT
import com.freqcast.util.StationNavigator
import com.freqcast.util.isNetworkAvailable
import com.freqcast.widget.RadioWidget
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Everything [RadioPlaybackService] needs to know to start playing a station - bundles what used to
 * be two separately-set mutable fields ([RadioPlaybackService]'s old `stationName`/`currentCustomIcon`)
 * into one value passed to [RadioPlaybackService.applyPlayback] in a single call, so there's no
 * longer an invisible "set these fields in the right order before calling" precondition a new call
 * site could get wrong - the same class of bug AGENTS.md's `isFavorite` full-row `@Update` trap
 * describes for `RadioStation`.
 */
data class PlaybackRequest(
    val stationName: String?,
    val streamUrl: String,
    val customIcon: String?,
    val knownHls: Boolean?,
)

/** Snapshot of playback state exposed reactively so the UI doesn't need to poll the service. */
data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentMediaId: String? = null,
    val hasTimeshift: Boolean = false,
    val isAtLive: Boolean = true,
    val trackTitle: String? = null,
    val sleepTimerEndAtMs: Long? = null,
    val connectionErrorAt: Long? = null,
    val isRetryPending: Boolean = false,
    val isConnectionBroken: Boolean = false,
    val bufferedDurationMs: Long = 0L,
    val offsetFromLiveMs: Long = 0L,
    val clipFormatAvailable: Boolean = false,
)

@UnstableApi
class RadioPlaybackService : MediaLibraryService() {
    private var mediaSession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null

    /**
     * Wraps [player] so seek commands issued from outside the app (notification, lock screen,
     * headset buttons, Android Auto) route through [seekBackward]/[seekToLive] instead of
     * [ExoPlayer]'s own `seekBack()`/`seekForward()`, which don't work reliably against the
     * growing timeshift buffer file (see [TimeshiftController.seekBackward]'s doc). Handed to both
     * [mediaSession] and [notificationManager] so every entry point behaves the same way.
     */
    private var sessionPlayer: Player? = null
    private var notificationManager: PlayerNotificationManager? = null

    /**
     * The station currently playing (or last played), set by [applyPlayback] itself from the
     * [PlaybackRequest] it's given - the single source for station name/icon everywhere else in
     * this class reads them (notification, `PendingIntent` extras, widget, [getCurrentStationName]).
     * Deliberately untouched by [stopPlayback] (matches the old `stationName` field's behavior, not
     * the old `currentCustomIcon` field's - see git history): [getCurrentStationName] and the widget
     * both rely on it still naming the last-played station after an explicit stop, and nothing reads
     * [currentRequest] post-stop in a way that clearing it would fix, so there's nothing to gain by
     * asymmetrically nulling it out.
     */
    private var currentRequest: PlaybackRequest? = null

    private lateinit var timeshift: TimeshiftController
    private lateinit var playbackStateStore: PlaybackStateStore
    private lateinit var repository: RadioStationRepository
    private lateinit var browseTree: RadioBrowseTree
    private val radioBrowserApi = RadioBrowserApi()

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Serializes [switchToAdjacentStation] calls - see its doc for why. */
    private val stationSwitchMutex = Mutex()

    private val retryPolicy = ConnectionRetryPolicy()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sleepTimer = SleepTimerController(mainHandler) { stopPlayback() }
    private val binder = LocalBinder()

    private val _playbackSnapshot = MutableStateFlow(PlaybackSnapshot())
    val playbackSnapshot: StateFlow<PlaybackSnapshot> = _playbackSnapshot.asStateFlow()

    /**
     * Monotonic timestamp of the last connection failure (timeshift recorder I/O error, or retry
     * exhaustion after a network error/loss), or null if none has happened yet in this process.
     * Never reset back to null - consumers react to the value *changing*, not to its
     * null/not-null-ness, since a new failure simply overwrites it.
     */
    private var lastConnectionErrorAt: Long? = null

    /**
     * Whether the current stream is in a give-up state (retries exhausted or a fatal error), for
     * [PlaybackStatus.ERROR][com.freqcast.ui.components.PlaybackStatus] in the UI. Unlike
     * [lastConnectionErrorAt] (which never resets - it only drives a one-shot Toast on change),
     * this must flip back to false on the next attempt, or the mini player would show ERROR
     * forever after the first failure of the session - see [applyPlayback].
     */
    private var isConnectionBroken = false

    /**
     * [updateWidgetToo] is false for the once-a-second timeshift ticker ([onCreate]): the widget
     * doesn't render buffer/offset, so writing it on every tick would be pure I/O waste (see
     * [updateWidget]'s SharedPreferences write + [RadioWidget.updateAll]). Every other call site
     * is an actual event (play/pause/seek/error/etc.) and keeps the default.
     */
    private fun refreshSnapshot(updateWidgetToo: Boolean = true) {
        val isPlaying = player?.isPlaying ?: false
        _playbackSnapshot.value =
            PlaybackSnapshot(
                isPlaying = isPlaying,
                isBuffering = player?.playbackState == Player.STATE_BUFFERING,
                currentMediaId = player?.currentMediaItem?.mediaId,
                hasTimeshift = timeshift.hasTimeshift(),
                isAtLive = timeshift.isAtLive(),
                trackTitle = timeshift.currentTrackTitle(),
                sleepTimerEndAtMs = sleepTimer.endAtMsOrNull(),
                connectionErrorAt = lastConnectionErrorAt,
                isRetryPending = retryPolicy.isPendingRetry(),
                isConnectionBroken = isConnectionBroken,
                bufferedDurationMs = timeshift.bufferedDurationMs(),
                offsetFromLiveMs = timeshift.offsetFromLiveMs(),
                clipFormatAvailable = timeshift.currentClipFormat() != null,
            )
        if (updateWidgetToo) updateWidget(isPlaying)
    }

    /** Pushes the latest station/playing state to the home screen widget (see `widget/RadioWidget`). */
    private fun updateWidget(isPlaying: Boolean) {
        val streamUrl = retryPolicy.currentStreamUrlOrNull() ?: player?.currentMediaItem?.mediaId
        WidgetStateStore(
            this,
        ).save(stationName = currentRequest?.stationName, streamUrl = streamUrl, isPlaying = isPlaying)
        serviceScope.launch { RadioWidget().updateAll(this@RadioPlaybackService) }
    }

    /** Stops playback automatically after [minutes]; replaces any previously scheduled timer. */
    fun setSleepTimer(minutes: Int) {
        sleepTimer.start(minutes)
        refreshSnapshot()
    }

    fun cancelSleepTimer() {
        sleepTimer.cancel()
        refreshSnapshot()
    }

    inner class LocalBinder : Binder() {
        fun getService(): RadioPlaybackService = this@RadioPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder {
        // MediaLibraryService.onBind() returns the real session binder when the incoming intent's
        // action matches its media-session interface (external clients: Android Auto, Assistant,
        // legacy MediaBrowserServiceCompat clients) and null for anything else — including our own
        // Activities' plain explicit-component bind (no action set), which is when we want to hand
        // out LocalBinder instead. Discarding the super binder unconditionally (as this used to do)
        // would hand Auto/Assistant our unrelated LocalBinder and break their connection outright.
        return super.onBind(intent) ?: binder
    }

    override fun onCreate() {
        super.onCreate()
        timeshift = TimeshiftController(cacheDir)
        playbackStateStore = PlaybackStateStore(this)
        repository = RadioStationRepository.create(this)
        browseTree = RadioBrowseTree(repository, getString(R.string.app_name))
        createNotificationChannel()
        initializePlayer()
        // Built immediately (not lazily on first startPlayback) so a MediaLibrarySession always
        // exists for onGetSession() to return — Android Auto needs to browse the station list even
        // before any playback has ever started in this process.
        buildMediaSession(streamUrl = null)
        setupNotificationManager()
        startTimeshiftTicker()
    }

    /**
     * Ticks [PlaybackSnapshot.bufferedDurationMs]/[PlaybackSnapshot.offsetFromLiveMs] once a second
     * while timeshift is recording, so screens can `collect` a growing value instead of polling the
     * service directly (see [com.freqcast.ui.components.rememberPlaybackPresentation], which used to
     * do exactly that). One ticker for the whole process instead of one per open screen, and it never
     * starts/stops itself around the various timeshift start/stop call sites - it just checks
     * [TimeshiftController.hasTimeshift] each second, which is simpler than tracking lifecycle here.
     */
    private fun startTimeshiftTicker() {
        serviceScope.launch {
            while (true) {
                delay(1_000)
                if (timeshift.hasTimeshift()) refreshSnapshot(updateWidgetToo = false)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            return START_NOT_STICKY
        }

        val streamUrl = intent?.getStringExtra(EXTRA_STREAM_URL)

        if (streamUrl != null) {
            val name = intent.getStringExtra(EXTRA_STATION_NAME)
            // The caller only ever passes name/URL strings (Activities, widget, alarm, shortcuts),
            // never the full RadioStation, so the known-HLS hint and Radio Browser uuid are looked
            // up here by URL instead of threading them through every intent-creation call site.
            serviceScope.launch {
                val station = repository.getStationByUrl(streamUrl)
                startPlayback(
                    PlaybackRequest(
                        stationName = name,
                        streamUrl = streamUrl,
                        customIcon = station?.customIcon,
                        knownHls = station?.isHls,
                    ),
                )
                // A genuine new play (as opposed to the process-restart resume branch below):
                // register it as a "click" with the directory if this station came from Discover.
                station?.radioBrowserUuid?.let { uuid -> radioBrowserApi.registerClick(uuid) }
            }
        } else if (player?.playbackState != Player.STATE_IDLE) {
            // A genuine process-death restart always finds the player IDLE (onCreate() just built a
            // fresh one). A non-IDLE player here means something else delivered this null/extra-less
            // intent while playback was already active - blindly "restoring" would tear down and
            // re-request the stream that's already buffering/playing. Log (with what triggered it,
            // since the actual source of these calls isn't confirmed yet) and ignore instead.
            Log.d(
                TAG,
                "onStartCommand: ignoring stray intent=$intent action=${intent?.action}, playback already active",
            )
        } else {
            // Null intent means the system killed and restarted this service (START_STICKY);
            // resume whatever was last playing instead of just stopping silently.
            val saved = playbackStateStore.restore()
            if (saved != null) {
                Log.d(TAG, "onStartCommand: restoring last station after service restart")
                serviceScope.launch {
                    val station = repository.getStationByUrl(saved.streamUrl)
                    startPlayback(
                        PlaybackRequest(
                            stationName = saved.stationName,
                            streamUrl = saved.streamUrl,
                            customIcon = station?.customIcon,
                            knownHls = station?.isHls,
                        ),
                    )
                }
            } else {
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        releasePlayer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.app_name)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createConnectingNotification(): android.app.Notification {
        val openIntent =
            Intent(this, PlaybackActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(PlaybackActivity.EXTRA_STATION_NAME, currentRequest?.stationName)
            }
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(currentRequest?.stationName ?: getString(R.string.unknown_station))
            .setContentText(getString(R.string.starting))
            .setSmallIcon(R.drawable.ic_play_circle)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun startForegroundWithNotification(notification: android.app.Notification): Boolean =
        startForegroundWithNotification(NOTIFICATION_ID, notification)

    /**
     * Android 12+ can refuse this promotion outright (`ForegroundServiceStartNotAllowedException`,
     * API 31+) when the call isn't backed by a currently-valid background-start exemption - hit in
     * practice from both of this service's own background-triggered restarts: [applyPlayback]'s
     * network-recovery retry (a `Handler`/`NetworkCallback` callback, not a user-visible action) and
     * `onStartCommand`'s null-intent process-death resume. Caught as a plain `Exception` rather than
     * that specific type - referencing an API-31-only exception class in a `catch` can trip ART's
     * eager verifier below minSdk 31 (this app's minSdk is 29) even on a code path that never
     * actually runs there. Returns false instead of crashing so callers can fail the attempt safely
     * (see call sites) rather than proceed as if playback had actually started.
     */
    private fun startForegroundWithNotification(
        notificationId: Int,
        notification: android.app.Notification,
    ): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(notificationId, notification)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundWithNotification: system refused the foreground promotion", e)
            false
        }

    private fun initializePlayer() {
        val audioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
        player =
            ExoPlayer
                .Builder(this)
                // Duck/pause automatically for calls & other apps' audio instead of playing over them
                // (second param is handleAudioFocus).
                .setAudioAttributes(audioAttributes, true)
                // Pause instead of blasting through the speaker when headphones/Bluetooth disconnect.
                .setHandleAudioBecomingNoisy(true)
                // Hold a wake lock while playing so Doze/App Standby doesn't kill network mid-stream
                // with the screen off (this app's whole point is background listening).
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
                .apply {
                    addListener(
                        object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                Log.d(TAG, "onPlayerError: code=${error.errorCode}, message=${error.message}")
                                handlePlayerError(error)
                            }

                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                val p = this@RadioPlaybackService.player
                                val mediaId = p?.currentMediaItem?.mediaId?.take(50)
                                val state = p?.playbackState
                                Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying, state=$state, mediaId=$mediaId")
                                notificationManager?.invalidate()
                                refreshSnapshot()
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                val stateStr =
                                    when (playbackState) {
                                        Player.STATE_IDLE -> "IDLE"
                                        Player.STATE_BUFFERING -> "BUFFERING"
                                        Player.STATE_READY -> "READY"
                                        Player.STATE_ENDED -> "ENDED"
                                        else -> "?($playbackState)"
                                    }
                                Log.d(TAG, "onPlaybackStateChanged: $stateStr")
                                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                                    notificationManager?.invalidate()
                                }
                                if (playbackState == Player.STATE_READY) {
                                    // Stream loaded successfully: give future failures a fresh retry budget.
                                    retryPolicy.onPlaybackSucceeded()
                                }
                                refreshSnapshot()
                            }
                        },
                    )
                }
        sessionPlayer = TimeshiftSeekPlayer(player!!)

        // MediaSession is created in startPlayback() when we have station info for lock screen session activity
    }

    /**
     * [PendingIntent] that reopens [PlaybackActivity], carrying [currentRequest]'s station name and
     * [streamUrl] extras together when [streamUrl] is known - shared by the session's own
     * activity-open intent ([buildMediaSession]) and its notification's tap target, which need
     * identical extras.
     */
    private fun playbackActivityPendingIntent(streamUrl: String?): PendingIntent {
        val intent =
            Intent(this, PlaybackActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (streamUrl != null) {
                    putExtra(PlaybackActivity.EXTRA_STATION_NAME, currentRequest?.stationName)
                    putExtra(PlaybackActivity.EXTRA_STREAM_URL, streamUrl)
                }
            }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Builds (or rebuilds) the [MediaLibrarySession]. [streamUrl] is null only for the initial
     * onCreate() build, before any station has ever played in this process — the session's
     * activity-open intent then carries no station extras and relies on [PlaybackActivity] reading
     * the live station from the bound service once it connects.
     *
     * Only called from top-level entry points (onCreate, startPlayback), never from within the
     * session's own [MediaLibrarySessionCallback] — media3 does not support a session releasing
     * itself mid-callback-dispatch, which is why browse-tree-initiated playback goes through
     * [applyPlayback] directly instead of routing back through here.
     */
    private fun buildMediaSession(streamUrl: String?) {
        val p = sessionPlayer ?: return
        mediaSession?.release()
        val sessionActivity = playbackActivityPendingIntent(streamUrl)
        mediaSession =
            MediaLibrarySession
                .Builder(this, p, MediaLibrarySessionCallback())
                .setSessionActivity(sessionActivity)
                .build()
        // Links the notification's MediaStyle to this session's platform token so the system
        // recognizes it as a media notification and renders lock screen media controls.
        notificationManager?.setMediaSessionToken(mediaSession!!.platformToken)
    }

    private fun setupNotificationManager() {
        val notificationPlayer = sessionPlayer ?: return

        notificationManager =
            PlayerNotificationManager
                .Builder(
                    this,
                    NOTIFICATION_ID,
                    CHANNEL_ID,
                ).setMediaDescriptionAdapter(
                    object : PlayerNotificationManager.MediaDescriptionAdapter {
                        override fun getCurrentContentTitle(player: Player): CharSequence =
                            currentRequest?.stationName ?: getString(R.string.unknown_station)

                        override fun getCurrentContentText(player: Player): CharSequence =
                            when {
                                retryPolicy.isPendingRetry() -> getString(R.string.reconnecting)
                                else -> timeshift.currentTrackTitle() ?: getString(R.string.app_name)
                            }

                        override fun getCurrentLargeIcon(
                            player: Player,
                            callback: PlayerNotificationManager.BitmapCallback,
                        ): android.graphics.Bitmap? {
                            val customIcon = currentRequest?.customIcon
                            if (customIcon != null && IconStorage.isImagePath(customIcon)) {
                                IconStorage.decodeBitmap(customIcon)?.let { return it }
                            }
                            val emoji =
                                customIcon?.takeUnless(IconStorage::isImagePath)
                                    ?: EmojiGenerator.getEmojiForStation(
                                        currentRequest?.stationName ?: getString(R.string.unknown_station),
                                        player.currentMediaItem?.mediaId ?: "",
                                    )
                            return EmojiGenerator.getEmojiBitmap(emoji, 128)
                        }

                        override fun createCurrentContentIntent(player: Player): PendingIntent? =
                            playbackActivityPendingIntent(player.currentMediaItem?.mediaId)
                    },
                ).setNotificationListener(
                    object : PlayerNotificationManager.NotificationListener {
                        override fun onNotificationCancelled(
                            notificationId: Int,
                            dismissedByUser: Boolean,
                        ) {
                            stopSelf()
                        }

                        override fun onNotificationPosted(
                            notificationId: Int,
                            notification: android.app.Notification,
                            ongoing: Boolean,
                        ) {
                            if (ongoing && !startForegroundWithNotification(notificationId, notification)) {
                                stopPlayback()
                            }
                        }
                    },
                ).build()
                .apply {
                    setPlayer(notificationPlayer)
                    // Previous/next station buttons in the collapsed notification-shade view -
                    // instance methods, not Builder ones. Shown whenever TimeshiftSeekPlayer reports
                    // COMMAND_SEEK_TO_PREVIOUS/COMMAND_SEEK_TO_NEXT available (always, since it
                    // force-enables them). Play/pause is compact-view-visible unconditionally
                    // regardless of this call.
                    setUsePreviousActionInCompactView(true)
                    setUseNextActionInCompactView(true)
                }
    }

    private fun startPlayback(
        request: PlaybackRequest,
        isRetry: Boolean = false,
    ) {
        buildMediaSession(request.streamUrl)
        applyPlayback(request, isRetry)
    }

    /**
     * Drives the player/notification/network-retry/timeshift pipeline for [request]. Split out
     * from [startPlayback] so [MediaLibrarySessionCallback.onAddMediaItems] (Android Auto tapping a
     * station in the browse tree) can start playback without rebuilding the session it's currently
     * being called from — see [buildMediaSession]'s doc for why that matters. Sets [currentRequest]
     * itself, so every field this class needs about the playing station arrives in this one call
     * instead of a caller pre-setting mutable fields in the right order beforehand.
     */
    private fun applyPlayback(
        request: PlaybackRequest,
        isRetry: Boolean = false,
    ) {
        currentRequest = request
        val streamUrl = request.streamUrl
        val exoPlayer = player ?: return
        val isHls = isHlsUrl(streamUrl, request.knownHls)
        Log.d(TAG, "applyPlayback: isHls=$isHls, url=${streamUrl.take(60)}, isRetry=$isRetry")
        retryPolicy.onPlaybackStarted(streamUrl, request.knownHls, isRetry)
        isConnectionBroken = false
        playbackStateStore.save(request.stationName, streamUrl)
        timeshift.stop()
        registerNetworkCallback()

        // Start foreground immediately so notification and lock screen controls appear right away.
        // The system can refuse this (see startForegroundWithNotification's doc) when this call
        // came from a background trigger (network-recovery retry, process-death resume) - bail out
        // via the same cleanup a give-up retry decision uses, rather than build out a player/media
        // session pipeline with no foreground promotion behind it.
        if (!startForegroundWithNotification(createConnectingNotification())) {
            stopPlayback()
            return
        }

        val mediaItemBuilder =
            MediaItem
                .Builder()
                .setMediaId(streamUrl)
                .setUri(streamUrl)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(request.stationName ?: getString(R.string.unknown_station))
                        .setArtist(getString(R.string.app_name))
                        .build(),
                )
        // Explicit HLS type when URL doesn't end with .m3u8 (per ExoPlayer HLS guide)
        if (isHls && !streamUrl.lowercase().endsWith(".m3u8")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        val mediaItem = mediaItemBuilder.build()

        if (isHls) {
            // HLS: use HlsMediaSource + DefaultHttpDataSource per Android HLS guide (live segments, timeouts).
            val dataSourceFactory =
                DefaultHttpDataSource
                    .Factory()
                    .setConnectTimeoutMs(8_000)
                    .setReadTimeoutMs(8_000)
                    .setUserAgent(STREAM_USER_AGENT)
            val hlsMediaSource =
                HlsMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            exoPlayer.setMediaSource(hlsMediaSource)
            exoPlayer.prepare()
            exoPlayer.play()
        } else {
            // Single URL stream: record to buffer file and play with timeshift.
            val dataSourceFactory =
                timeshift.start(
                    streamUrl = streamUrl,
                    maxBufferBytes = SettingsStore(this).timeshiftBufferSizeMb * 1024L * 1024L,
                    onError = { mainHandler.post { onTimeshiftError() } },
                    onMetadata = { title ->
                        mainHandler.post {
                            updateMediaItemMetadataForTrack(title)
                            refreshSnapshot()
                            notificationManager?.invalidate()
                        }
                    },
                )
            val bufferFile = timeshift.currentBufferFile()!!
            attachProgressiveMediaSource(
                mediaItem.buildUpon().setUri(Uri.fromFile(bufferFile)).build(),
                dataSourceFactory,
            )
        }

        notificationManager?.invalidate()
        refreshSnapshot()
    }

    /**
     * Sets the ICY track title as the media item's artist (title stays the station name, matching
     * the notification's title=station/text=track convention) so the lock screen / Android Auto /
     * media button apps show it too. Uses [Player.replaceMediaItem] so playback position/state is
     * untouched.
     */
    private fun updateMediaItemMetadataForTrack(trackTitle: String) {
        val p = player ?: return
        val currentItem = p.currentMediaItem ?: return
        val updatedMetadata =
            currentItem.mediaMetadata
                .buildUpon()
                .setTitle(currentRequest?.stationName ?: getString(R.string.unknown_station))
                .setArtist(trackTitle)
                .build()
        p.replaceMediaItem(p.currentMediaItemIndex, currentItem.buildUpon().setMediaMetadata(updatedMetadata).build())
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.post { tryResumePlaybackAfterNetworkRestored() }
                }
            }
        cm.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { callback ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
                // already unregistered
            }
            networkCallback = null
        }
    }

    /** Internal (not private) so Robolectric tests can drive it directly - see [handlePlayerError]'s doc. */
    internal fun tryResumePlaybackAfterNetworkRestored() {
        if (!isNetworkAvailable(this)) return
        val p = player ?: return
        handleRetryDecision(retryPolicy.onNetworkAvailable(isPlayerIdle = p.playbackState == Player.STATE_IDLE))
    }

    /**
     * Single dispatch point for every [RetryDecision] the policy can produce, regardless of which
     * caller ([tryResumePlaybackAfterNetworkRestored] or [handlePlayerError]) triggered it - each
     * caller only ever receives a subset of this sealed type, but funneling both through one `when`
     * keeps the exhaustive match (and the GiveUp/NoAction handling) in a single place instead of
     * duplicated per call site.
     */
    private fun handleRetryDecision(decision: RetryDecision) {
        when (decision) {
            is RetryDecision.RetryNow -> {
                attemptScheduledRetry(decision.attemptId)
            }

            is RetryDecision.RetryAfter -> {
                Log.d(TAG, "handleRetryDecision: retryable network error, retry in ${decision.delayMs}ms")
                notificationManager?.invalidate()
                mainHandler.postDelayed({ attemptScheduledRetry(decision.attemptId) }, decision.delayMs)
            }

            RetryDecision.GiveUp -> {
                Log.d(TAG, "handleRetryDecision: retry limit reached, giving up")
                lastConnectionErrorAt = System.currentTimeMillis()
                isConnectionBroken = true
                stopPlayback()
            }

            RetryDecision.NoAction -> {}
        }
    }

    /** [knownHls] (the directory's own `hls` flag, when known) takes precedence over the URL heuristic. */
    internal fun isHlsUrl(
        url: String,
        knownHls: Boolean? = null,
    ): Boolean = knownHls ?: url.contains("m3u8", ignoreCase = true)

    fun stopPlayback() {
        cancelSleepTimer()
        retryPolicy.reset()
        // currentRequest deliberately survives a stop - see its doc.
        playbackStateStore.clear()
        unregisterNetworkCallback()
        timeshift.stop()
        player?.pause()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        refreshSnapshot()
    }

    fun isPlaying(): Boolean = player?.isPlaying ?: false

    fun isBuffering(): Boolean = player?.playbackState == Player.STATE_BUFFERING

    fun getPlayer(): ExoPlayer? = player

    fun getCurrentStationName(): String? = currentRequest?.stationName

    fun seekBackward(ms: Long) = applyTimeshiftSeek(timeshift.seekBackward(ms))

    fun seekToLive() = applyTimeshiftSeek(timeshift.seekToLive())

    fun seekToOffsetFromLive(offsetMs: Long) = applyTimeshiftSeek(timeshift.seekToOffsetFromLive(offsetMs))

    /** Total duration currently held in the timeshift buffer, in ms. 0 if not recording. */
    fun bufferedDurationMs(): Long = timeshift.bufferedDurationMs()

    /** How far behind the live edge playback currently sits, in ms. 0 when at live. */
    fun offsetFromLiveMs(): Long = timeshift.offsetFromLiveMs()

    /** MP3/AAC or null - see [TimeshiftController.currentClipFormat]. Drives clip-export's UI gating. */
    fun currentClipFormat(): ClipFormat? = timeshift.currentClipFormat()

    /** Exports the last [durationMs] of the timeshift buffer to [destination] - see [TimeshiftController.exportClip]. */
    fun exportClip(
        durationMs: Long,
        destination: File,
        onResult: (Boolean) -> Unit,
    ) = timeshift.exportClip(durationMs, destination, onResult)

    /** Rebuilds the media source from a timeshift seek's buffer-file [dataSourceFactory] and resumes playback. */
    private fun applyTimeshiftSeek(dataSourceFactory: DataSource.Factory?) {
        val p = player ?: return
        val mediaItem = p.currentMediaItem ?: return
        val factory = dataSourceFactory ?: return
        attachProgressiveMediaSource(mediaItem, factory)
        refreshSnapshot()
    }

    /** Attaches [mediaItem] via a fresh ProgressiveMediaSource built from [dataSourceFactory] and starts playback. */
    private fun attachProgressiveMediaSource(
        mediaItem: MediaItem,
        dataSourceFactory: DataSource.Factory,
    ) {
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        player?.setMediaSource(mediaSource)
        player?.prepare()
        player?.play()
    }

    fun isAtLive(): Boolean = timeshift.isAtLive()

    fun hasTimeshift(): Boolean = timeshift.hasTimeshift()

    /**
     * Forces `COMMAND_SEEK_BACK`/`COMMAND_SEEK_FORWARD` on so external surfaces (notification,
     * lock screen, headset buttons, Android Auto) always offer rewind/return-to-live controls, and
     * routes both through [seekBackward]/[seekToLive] instead of the wrapped player's own seek —
     * see [sessionPlayer]'s doc for why the latter doesn't work against the timeshift buffer.
     */
    private inner class TimeshiftSeekPlayer(
        player: ExoPlayer,
    ) : ForwardingPlayer(player) {
        override fun isCommandAvailable(command: Int): Boolean =
            when (command) {
                Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                -> true

                else -> super.isCommandAvailable(command)
            }

        override fun getAvailableCommands(): Player.Commands =
            super
                .getAvailableCommands()
                .buildUpon()
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

        override fun seekBack() = seekBackward(TIMESHIFT_SEEK_BACK_MS)

        override fun seekForward() = seekToLive()

        // The player only ever has one MediaItem loaded (the live stream, not a real playlist), so
        // hasNextMediaItem()/hasPreviousMediaItem() are always false and the plain ExoPlayer
        // next/previous commands would otherwise report unavailable - rerouted to station-list
        // navigation instead, same trick as seekBack/seekForward above for timeshift.
        override fun seekToNext() = switchToAdjacentStation(StationNavigator::next)

        override fun seekToPrevious() = switchToAdjacentStation(StationNavigator::previous)
    }

    /**
     * Internal (not private), like [isHlsUrl]/[playFromBrowseTree] above, so Robolectric tests can
     * drive the retry-exhaustion ("give up") path directly with a synthetic [PlaybackException]
     * instead of needing a real failing stream connection.
     */
    internal fun handlePlayerError(error: PlaybackException) {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player?.seekToDefaultPosition()
                player?.prepare()
                player?.play()
            }

            // Transient network errors (e.g. VPN toggle): retry when network is back instead of stopping.
            else -> {
                handleRetryDecision(retryPolicy.onPlaybackError(error))
            }
        }
    }

    /**
     * Only retries if [attemptId] is still current — a manual stop/switch or a network-triggered
     * retry in the meantime invalidates it. Reuses [currentRequest]'s station name/icon (a retry
     * always replays the same stream a request was already set for - see [ConnectionRetryPolicy],
     * whose own tracked [target]'s `streamUrl` is always that same URL) rather than requiring the
     * caller to somehow already know them again.
     */
    private fun attemptScheduledRetry(attemptId: Long) {
        val target = retryPolicy.attemptRetry(attemptId) ?: return
        val base = currentRequest ?: return
        startPlayback(base.copy(streamUrl = target.streamUrl, knownHls = target.knownHls), isRetry = true)
    }

    /**
     * Reacts to a timeshift recorder I/O failure (see [TimeshiftController.start]'s `onError`):
     * marks the connection error and leaves recovery to the network-restored callback, same as a
     * player-level error would. Internal (not private), same test-seam reason as [handlePlayerError].
     */
    internal fun onTimeshiftError() {
        lastConnectionErrorAt = System.currentTimeMillis()
        retryPolicy.markPendingRetry()
        timeshift.stop()
        notificationManager?.invalidate()
        refreshSnapshot()
    }

    private fun releasePlayer() {
        cancelSleepTimer()
        retryPolicy.reset()
        unregisterNetworkCallback()
        timeshift.stop()
        notificationManager?.setPlayer(null)
        mediaSession?.release()
        player?.let {
            it.stop()
            it.release()
            player = null
        }
        sessionPlayer = null
        mediaSession = null
        notificationManager = null
        refreshSnapshot()
    }

    /**
     * Loads the current station list and returns it as browsable media items for the Android Auto
     * / Assistant browse tree. Kept `internal` (not private), same as [isHlsUrl] above, so tests
     * can exercise the real browse-tree contents without going through a full
     * [MediaLibrarySession]/[MediaSession.ControllerInfo] Binder round trip — media3's own team
     * tests that round trip with instrumentation, not Robolectric, which this project deliberately
     * doesn't have (see Testing in AGENTS.md).
     */
    internal suspend fun loadBrowsableStations(): List<MediaItem> = browseTree.loadStations()

    /**
     * Starts playback of the cached station whose stream URL is [mediaId], as if it had been
     * tapped in the Android Auto / Assistant browse tree. Returns false without side effects if
     * [mediaId] isn't a station from the most recent [loadBrowsableStations] call.
     */
    internal fun playFromBrowseTree(mediaId: String): Boolean {
        val station = browseTree.findStation(mediaId) ?: return false
        applyPlayback(
            PlaybackRequest(
                stationName = station.name,
                streamUrl = station.streamUrl,
                customIcon = station.customIcon,
                knownHls = station.isHls,
            ),
        )
        station.radioBrowserUuid?.let { uuid -> serviceScope.launch { radioBrowserApi.registerClick(uuid) } }
        return true
    }

    /**
     * Switches to the next/previous station in the plain station list (`sortOrder ASC, id ASC` -
     * there's no separate "favorites" concept, see AGENTS.md), wrapping at either end via
     * [StationNavigator]. Wired to the media notification's skip-next/skip-previous buttons through
     * [TimeshiftSeekPlayer]'s `seekToNext`/`seekToPrevious` overrides below. Calls [applyPlayback]
     * directly, not [startPlayback] - same reasoning as [playFromBrowseTree]: this fires from inside
     * a player-command callback (`PlayerNotificationManager`'s button click handler), so rebuilding
     * the session mid-dispatch isn't the safe path here either. Internal (not private), same
     * test-seam reason as [playFromBrowseTree].
     *
     * [stationSwitchMutex]-serialized: [repository.getAllStations] is a real suspend Room query, so
     * a second rapid tap (e.g. double-tapping "previous" in the notification before the first tap's
     * [applyPlayback] has landed) would otherwise read the same not-yet-updated [currentRequest] as
     * the first and compute the same target twice - one of the two taps silently doing nothing. The
     * mutex makes each call wait for the previous one's [currentRequest] write before reading it,
     * so N rapid taps walk N stations, not fewer.
     */
    internal fun switchToAdjacentStation(pick: (List<RadioStation>, String?) -> RadioStation?) {
        serviceScope.launch {
            stationSwitchMutex.withLock {
                val currentStreamUrl = currentRequest?.streamUrl ?: return@withLock
                val target = pick(repository.getAllStations(), currentStreamUrl) ?: return@withLock
                applyPlayback(
                    PlaybackRequest(
                        stationName = target.name,
                        streamUrl = target.streamUrl,
                        customIcon = target.customIcon,
                        knownHls = target.isHls,
                    ),
                )
                target.radioBrowserUuid?.let { uuid -> radioBrowserApi.registerClick(uuid) }
            }
        }
    }

    private inner class MediaLibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browseTree.rootItem, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!browseTree.isRoot(parentId)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
            return serviceScope.future { LibraryResult.ofItemList(loadBrowsableStations(), params) }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (browseTree.isRoot(mediaId)) {
                return Futures.immediateFuture(LibraryResult.ofItem(browseTree.rootItem, null))
            }
            val item = browseTree.mediaItemFor(mediaId)
            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(item, null))
            } else {
                Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
        }

        /**
         * Backs voice search (e.g. "Hey Google, play [station] on Freqcast"): per the
         * [MediaLibrarySession.Callback] contract, the caller reports the query here first — we
         * refresh the station cache and report the match count via [notifySearchResultChanged] —
         * then pages through the actual items via [onGetSearchResult].
         */
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> =
            serviceScope.future {
                loadBrowsableStations()
                session.notifySearchResultChanged(browser, query, browseTree.search(query).size, params)
                LibraryResult.ofVoid()
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            serviceScope.future {
                LibraryResult.ofItemList(browseTree.search(query), params)
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // A tap on a station in the Android Auto / Assistant browse tree: play it through our
            // own pipeline (HLS/timeshift handling, notification, retry) instead of letting media3
            // hand the bare item straight to the player, and don't touch the session itself (see
            // buildMediaSession's doc).
            val mediaId = mediaItems.firstOrNull()?.mediaId
            if (mediaId != null && playFromBrowseTree(mediaId)) {
                return Futures.immediateFuture(mutableListOf())
            }

            val updatedItems =
                mediaItems.map { item ->
                    item
                        .buildUpon()
                        .setMediaMetadata(
                            item.mediaMetadata
                                .buildUpon()
                                .setTitle(currentRequest?.stationName ?: getString(R.string.unknown_station))
                                .setArtist(getString(R.string.app_name))
                                .build(),
                        ).build()
                }
            return Futures.immediateFuture(updatedItems.toMutableList())
        }
    }

    companion object {
        private const val TAG = "RadioPlayback"
        const val EXTRA_STATION_NAME = "station_name"
        const val EXTRA_STREAM_URL = "stream_url"
        const val ACTION_STOP = "com.freqcast.action.STOP"

        /** Rewind amount for both the in-app button and the [TimeshiftSeekPlayer]-routed system seek controls. */
        const val TIMESHIFT_SEEK_BACK_MS = 5000L
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "radio_playback_channel"
    }
}
