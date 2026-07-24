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
import com.freqcast.ui.playback.PlaybackStateStore
import com.freqcast.ui.playback.RadioBrowseTree
import com.freqcast.ui.playback.SettingsStore
import com.freqcast.ui.playback.SleepTimerController
import com.freqcast.ui.playback.TimeshiftController
import com.freqcast.ui.playback.WidgetStateStore
import com.freqcast.util.EmojiGenerator
import com.freqcast.util.IconStorage
import com.freqcast.util.STREAM_USER_AGENT
import com.freqcast.util.isNetworkAvailable
import com.freqcast.widget.RadioWidget
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.io.File

/** Snapshot of playback state exposed reactively so the UI doesn't need to poll the service. */
data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentMediaId: String? = null,
    val hasTimeshift: Boolean = false,
    val isAtLive: Boolean = true,
    val trackTitle: String? = null,
    val sleepTimerEndAtMs: Long? = null,
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
    private var stationName: String? = null

    /**
     * The current station's [RadioStation.customIcon] (emoji string or image file path), used by
     * [getCurrentLargeIcon] for the notification's large icon instead of always falling back to
     * the auto-generated emoji. Set by whichever entry point resolves the full [RadioStation]
     * (`onStartCommand`'s direct-play/resume branches, [playFromBrowseTree]) before starting
     * playback — same "caller sets it before calling `applyPlayback`" contract as [stationName],
     * and likewise untouched across a network-loss retry of the same stream.
     */
    private var currentCustomIcon: String? = null

    private lateinit var timeshift: TimeshiftController
    private lateinit var playbackStateStore: PlaybackStateStore
    private lateinit var repository: RadioStationRepository
    private lateinit var browseTree: RadioBrowseTree
    private val radioBrowserApi = RadioBrowserApi()

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Current stream URL; kept so we can restart playback when network is restored. */
    private var currentStreamUrl: String? = null

    /** The known-HLS hint for [currentStreamUrl], reused so a network-loss retry doesn't lose it. */
    private var currentKnownHls: Boolean? = null

    /** True when playback failed due to network; we retry when network is back (e.g. VPN reconnect). */
    private var pendingRetry = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Consecutive automatic reconnect attempts for the current stream; reset on manual start or successful load. */
    private var retryCount = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sleepTimer = SleepTimerController(mainHandler) { stopPlayback() }
    private val binder = LocalBinder()

    private val _playbackSnapshot = MutableStateFlow(PlaybackSnapshot())
    val playbackSnapshot: StateFlow<PlaybackSnapshot> = _playbackSnapshot.asStateFlow()

    private fun refreshSnapshot() {
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
            )
        updateWidget(isPlaying)
    }

    /** Pushes the latest station/playing state to the home screen widget (see `widget/RadioWidget`). */
    private fun updateWidget(isPlaying: Boolean) {
        val streamUrl = currentStreamUrl ?: player?.currentMediaItem?.mediaId
        WidgetStateStore(this).save(stationName = stationName, streamUrl = streamUrl, isPlaying = isPlaying)
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
            stationName = intent.getStringExtra(EXTRA_STATION_NAME)
            // The caller only ever passes name/URL strings (Activities, widget, alarm, shortcuts),
            // never the full RadioStation, so the known-HLS hint and Radio Browser uuid are looked
            // up here by URL instead of threading them through every intent-creation call site.
            serviceScope.launch {
                val station = repository.getStationByUrl(streamUrl)
                currentCustomIcon = station?.customIcon
                startPlayback(streamUrl, knownHls = station?.isHls)
                // A genuine new play (as opposed to the process-restart resume branch below):
                // register it as a "click" with the directory if this station came from Discover.
                station?.radioBrowserUuid?.let { uuid -> radioBrowserApi.registerClick(uuid) }
            }
        } else {
            // Null intent means the system killed and restarted this service (START_STICKY);
            // resume whatever was last playing instead of just stopping silently.
            val saved = playbackStateStore.restore()
            if (saved != null) {
                Log.d(TAG, "onStartCommand: restoring last station after service restart")
                stationName = saved.stationName
                serviceScope.launch {
                    val station = repository.getStationByUrl(saved.streamUrl)
                    currentCustomIcon = station?.customIcon
                    startPlayback(saved.streamUrl, knownHls = station?.isHls)
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
                putExtra(PlaybackActivity.EXTRA_STATION_NAME, stationName)
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
            .setContentTitle(stationName ?: getString(R.string.unknown_station))
            .setContentText(getString(R.string.starting))
            .setSmallIcon(R.drawable.ic_play_circle)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun startForegroundWithNotification(notification: android.app.Notification) {
        startForegroundWithNotification(NOTIFICATION_ID, notification)
    }

    private fun startForegroundWithNotification(
        notificationId: Int,
        notification: android.app.Notification,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(notificationId, notification)
        }
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
                                    retryCount = 0
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
     * [PendingIntent] that reopens [PlaybackActivity], carrying [stationName]/[streamUrl] extras
     * together when [streamUrl] is known - shared by the session's own activity-open intent
     * ([buildMediaSession]) and its notification's tap target, which need identical extras.
     */
    private fun playbackActivityPendingIntent(streamUrl: String?): PendingIntent {
        val intent =
            Intent(this, PlaybackActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (streamUrl != null) {
                    putExtra(PlaybackActivity.EXTRA_STATION_NAME, stationName)
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
                            stationName ?: getString(R.string.unknown_station)

                        override fun getCurrentContentText(player: Player): CharSequence =
                            when {
                                pendingRetry -> getString(R.string.reconnecting)
                                else -> timeshift.currentTrackTitle() ?: getString(R.string.app_name)
                            }

                        override fun getCurrentLargeIcon(
                            player: Player,
                            callback: PlayerNotificationManager.BitmapCallback,
                        ): android.graphics.Bitmap? {
                            val customIcon = currentCustomIcon
                            if (customIcon != null && IconStorage.isImagePath(customIcon)) {
                                IconStorage.decodeBitmap(customIcon)?.let { return it }
                            }
                            val emoji =
                                customIcon?.takeUnless(IconStorage::isImagePath)
                                    ?: EmojiGenerator.getEmojiForStation(
                                        stationName ?: getString(R.string.unknown_station),
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
                            if (ongoing) {
                                startForegroundWithNotification(notificationId, notification)
                            }
                        }
                    },
                ).build()
                .apply {
                    setPlayer(notificationPlayer)
                }
    }

    private fun startPlayback(
        streamUrl: String,
        isRetry: Boolean = false,
        knownHls: Boolean? = null,
    ) {
        buildMediaSession(streamUrl)
        applyPlayback(streamUrl, isRetry, knownHls)
    }

    /**
     * Drives the player/notification/network-retry/timeshift pipeline for [streamUrl]. Split out
     * from [startPlayback] so [MediaLibrarySessionCallback.onAddMediaItems] (Android Auto tapping a
     * station in the browse tree) can start playback without rebuilding the session it's currently
     * being called from — see [buildMediaSession]'s doc for why that matters. [stationName] must
     * already be set by the caller before calling this. [knownHls] is the directory's own `hls`
     * flag for this station when known (Discover-added stations, or Android Auto's cached list);
     * null falls back to [isHlsUrl]'s URL heuristic.
     */
    private fun applyPlayback(
        streamUrl: String,
        isRetry: Boolean = false,
        knownHls: Boolean? = null,
    ) {
        val exoPlayer = player ?: return
        val isHls = isHlsUrl(streamUrl, knownHls)
        Log.d(TAG, "applyPlayback: isHls=$isHls, url=${streamUrl.take(60)}, isRetry=$isRetry")
        currentStreamUrl = streamUrl
        currentKnownHls = knownHls
        pendingRetry = false
        playbackStateStore.save(stationName, streamUrl)
        if (!isRetry) {
            retryCount = 0
        }
        timeshift.stop()
        registerNetworkCallback()

        // Start foreground immediately so notification and lock screen controls appear right away
        startForegroundWithNotification(createConnectingNotification())

        val mediaItemBuilder =
            MediaItem
                .Builder()
                .setMediaId(streamUrl)
                .setUri(streamUrl)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(stationName ?: getString(R.string.unknown_station))
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
                    onError = {
                        mainHandler.post {
                            markConnectionError()
                            pendingRetry = true
                            timeshift.stop()
                            notificationManager?.invalidate()
                            refreshSnapshot()
                        }
                    },
                    onMetadata = { title ->
                        mainHandler.post {
                            updateMediaItemMetadataForTrack(title)
                            refreshSnapshot()
                            notificationManager?.invalidate()
                        }
                    },
                )
            val bufferFile = timeshift.currentBufferFile()!!
            val mediaSource =
                ProgressiveMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(mediaItem.buildUpon().setUri(Uri.fromFile(bufferFile)).build())
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.play()
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
                .setTitle(stationName ?: getString(R.string.unknown_station))
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

    private fun tryResumePlaybackAfterNetworkRestored() {
        if (!isNetworkAvailable(this)) return
        val url = currentStreamUrl ?: player?.currentMediaItem?.mediaId ?: return
        val p = player ?: return
        // Retry when we had set pendingRetry (network error) or player is in IDLE (e.g. connection lost).
        val shouldRetry = pendingRetry || p.playbackState == Player.STATE_IDLE
        if (!shouldRetry) return
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.d(TAG, "tryResumePlaybackAfterNetworkRestored: retry limit ($MAX_RETRY_COUNT) reached, giving up")
            markConnectionError()
            stopPlayback()
            return
        }
        retryCount++
        Log.d(
            TAG,
            "tryResumePlaybackAfterNetworkRestored: state=${p.playbackState}, " +
                "restarting playback ($retryCount/$MAX_RETRY_COUNT)",
        )
        startPlayback(url, isRetry = true, knownHls = currentKnownHls)
    }

    /** Exponential backoff (2s, 4s, 8s, 16s, capped at 30s) for the given 1-based retry attempt. */
    internal fun retryDelayMs(attempt: Int): Long {
        val delay = BASE_RETRY_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, 4))
        return delay.coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    /** [knownHls] (the directory's own `hls` flag, when known) takes precedence over the URL heuristic. */
    internal fun isHlsUrl(
        url: String,
        knownHls: Boolean? = null,
    ): Boolean = knownHls ?: url.contains("m3u8", ignoreCase = true)

    fun stopPlayback() {
        cancelSleepTimer()
        currentStreamUrl = null
        currentKnownHls = null
        currentCustomIcon = null
        pendingRetry = false
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

    fun getCurrentStationName(): String? = stationName

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
        val mediaSource =
            ProgressiveMediaSource
                .Factory(factory)
                .createMediaSource(mediaItem)
        p.setMediaSource(mediaSource)
        p.prepare()
        p.play()
        refreshSnapshot()
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
                Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD -> true
                else -> super.isCommandAvailable(command)
            }

        override fun getAvailableCommands(): Player.Commands =
            super
                .getAvailableCommands()
                .buildUpon()
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .build()

        override fun seekBack() = seekBackward(TIMESHIFT_SEEK_BACK_MS)

        override fun seekForward() = seekToLive()
    }

    private fun handlePlayerError(error: PlaybackException) {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player?.seekToDefaultPosition()
                player?.prepare()
                player?.play()
            }

            else -> {
                // Transient network errors (e.g. VPN toggle): retry when network is back instead of stopping.
                if (isRetryableNetworkError(error)) {
                    if (retryCount >= MAX_RETRY_COUNT) {
                        Log.d(TAG, "handlePlayerError: retry limit ($MAX_RETRY_COUNT) reached, giving up")
                        markConnectionError()
                        stopPlayback()
                        return
                    }
                    retryCount++
                    val delayMs = retryDelayMs(retryCount)
                    Log.d(
                        TAG,
                        "handlePlayerError: retryable network error, retry $retryCount/$MAX_RETRY_COUNT in ${delayMs}ms",
                    )
                    pendingRetry = true
                    notificationManager?.invalidate()
                    val url = currentStreamUrl
                    if (url != null) {
                        mainHandler.postDelayed({ retryPlaybackIfPending(url) }, delayMs)
                    }
                } else {
                    markConnectionError()
                    stopPlayback()
                }
            }
        }
    }

    internal fun isRetryableNetworkError(error: PlaybackException): Boolean {
        // All IO/network error codes in media3 (2000–2010): timeout, connection failed, reset, unspecified, etc.
        val code = error.errorCode
        return code in 2000..2010
    }

    /** Only retries if nothing else (manual stop/switch, or a network-triggered retry) already handled it. */
    private fun retryPlaybackIfPending(streamUrl: String) {
        if (!pendingRetry || currentStreamUrl != streamUrl) return
        startPlayback(streamUrl, isRetry = true, knownHls = currentKnownHls)
    }

    private fun releasePlayer() {
        cancelSleepTimer()
        currentStreamUrl = null
        pendingRetry = false
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
     * / Assistant browse tree. Kept `internal` (not private), same as [isHlsUrl]/[retryDelayMs]
     * above, so tests can exercise the real browse-tree contents without going through a full
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
        stationName = station.name
        currentCustomIcon = station.customIcon
        applyPlayback(station.streamUrl, knownHls = station.isHls)
        station.radioBrowserUuid?.let { uuid -> serviceScope.launch { radioBrowserApi.registerClick(uuid) } }
        return true
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
                                .setTitle(stationName ?: getString(R.string.unknown_station))
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
        private const val MAX_RETRY_COUNT = 5
        private const val BASE_RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L

        @Volatile
        private var connectionErrorFlag = false

        fun markConnectionError() {
            connectionErrorFlag = true
        }

        fun getAndClearConnectionError(): Boolean = connectionErrorFlag.also { connectionErrorFlag = false }
    }
}
