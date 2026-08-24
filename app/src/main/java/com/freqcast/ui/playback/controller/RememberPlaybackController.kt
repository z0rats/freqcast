package com.freqcast.ui.playback.controller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.freqcast.ui.RadioPlaybackService

/**
 * Binds to [RadioPlaybackService] across the host Activity's ON_START/ON_STOP window - mirroring
 * the bindService/unbindService pair MainActivity and PlaybackActivity used to hand-roll
 * independently - and exposes the connection as a [PlaybackController] instead of the raw service,
 * so nothing downstream (MainScreen, PlaybackScreen, NowPlayingBottomBar) needs to know the service
 * exists. Must be read via Compose state (not a plain var): onServiceConnected fires asynchronously,
 * after first composition.
 *
 * [externalState], if supplied, is written into directly instead of an internally-`remember`ed
 * state - lets a host Activity keep its own field (e.g. `PlaybackActivity`'s `onResume`/`onNewIntent`,
 * which run outside Compose and need the current controller synchronously) without duplicating any
 * of the bind/unbind logic below.
 */
@Composable
fun rememberPlaybackController(externalState: MutableState<PlaybackController?>? = null): State<PlaybackController?> {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val lifecycleOwner = LocalLifecycleOwner.current
    val controllerState = externalState ?: remember { mutableStateOf<PlaybackController?>(null) }

    DisposableEffect(lifecycleOwner) {
        var bound = false
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    val service = (binder as? RadioPlaybackService.LocalBinder)?.getService() ?: return
                    controllerState.value = ServiceBackedPlaybackController(appContext, service)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    controllerState.value = null
                }
            }

        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        if (!bound) {
                            val intent = Intent(appContext, RadioPlaybackService::class.java)
                            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                        }
                    }

                    Lifecycle.Event.ON_STOP -> {
                        if (bound) {
                            appContext.unbindService(connection)
                            bound = false
                            controllerState.value = null
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (bound) {
                appContext.unbindService(connection)
                bound = false
            }
        }
    }

    return controllerState
}
