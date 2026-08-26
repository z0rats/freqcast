package com.freqcast.appfunctions

import android.content.Intent
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.core.content.ContextCompat
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.ui.RadioPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The station that was started, so the calling agent can confirm which one it acted on. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PlaybackActionResult(
    /** The station's display name. */
    val stationName: String,
    /** The station's stream URL. */
    val streamUrl: String,
)

/**
 * Looks up [name] case-insensitively against [stations]' display names. Pulled out as a pure,
 * unit-testable function rather than inlined in [BaseRadioAppFunctionService] - same "pure
 * decision, real adapter elsewhere" shape as [RadioPlaybackService]'s isHlsUrl.
 */
internal fun findStationByName(
    stations: List<RadioStation>,
    name: String,
): RadioStation? = stations.firstOrNull { it.name.equals(name, ignoreCase = true) }

/**
 * Plays or stops one of the user's saved internet radio stations without opening the app UI.
 * Only stations already in the user's list can be played; this doesn't add new stations or
 * resolve arbitrary URLs. No dependency injection framework is used elsewhere in this app (see
 * AGENTS.md's "minimize dependencies" convention), so [repository] is resolved directly from
 * [getApplicationContext] instead of via Hilt - the same pattern `WidgetActions`/`AlarmReceiver`
 * already use for a DI-less seam onto [RadioStationRepository].
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "RadioAppFunctionService",
    appFunctionXmlFileName = "radio_app_function_service",
)
abstract class BaseRadioAppFunctionService : AppFunctionService() {
    private val repository by lazy { RadioStationRepository.create(applicationContext) }

    /**
     * Starts playing the saved station whose name matches [stationName].
     *
     * @param stationName The station's display name, matched case-insensitively against the
     *   user's saved station list.
     * @return The matched station's name and stream URL.
     * @throws AppFunctionElementNotFoundException If no saved station's name matches. Suggest the
     *   user check the spelling, or add the station in the app first.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun playStation(stationName: String): PlaybackActionResult =
        withContext(Dispatchers.IO) {
            val station =
                findStationByName(repository.getAllStations(), stationName)
                    ?: throw AppFunctionElementNotFoundException(
                        "No saved station named \"$stationName\". Check the spelling, or add it in the app first.",
                    )
            val intent =
                Intent(applicationContext, RadioPlaybackService::class.java).apply {
                    putExtra(RadioPlaybackService.EXTRA_STATION_NAME, station.name)
                    putExtra(RadioPlaybackService.EXTRA_STREAM_URL, station.streamUrl)
                }
            ContextCompat.startForegroundService(applicationContext, intent)
            PlaybackActionResult(stationName = station.name, streamUrl = station.streamUrl)
        }

    /** Stops whatever station is currently playing. A no-op if nothing is playing. */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun stopPlayback() {
        withContext(Dispatchers.IO) {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, RadioPlaybackService::class.java)
                    .setAction(RadioPlaybackService.ACTION_STOP),
            )
        }
    }
}
