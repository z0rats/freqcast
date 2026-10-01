package com.freqcast.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freqcast.data.CuratedStations
import com.freqcast.data.ImportResult
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.StationBackupIO
import com.freqcast.data.UpdateChecker
import com.freqcast.data.isNewerVersion
import com.freqcast.ui.playback.SettingsStore
import com.freqcast.ui.playback.TimeshiftBufferSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class UpdateStatus { UNKNOWN, UP_TO_DATE, AVAILABLE }

data class SettingsUiState(
    val currentVersion: String = "",
    val updateStatus: UpdateStatus = UpdateStatus.UNKNOWN,
    val updateUrl: String? = null,
    val warnOnMeteredConnection: Boolean = false,
    val timeshiftBufferSizeMb: Int = TimeshiftBufferSize.DEFAULT_MB,
)

class SettingsViewModel(
    private val repository: RadioStationRepository,
    private val settingsStore: SettingsStore,
    currentVersion: String,
    private val updateChecker: UpdateChecker = UpdateChecker(),
    checkForUpdates: Boolean = true,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            SettingsUiState(
                currentVersion = currentVersion,
                warnOnMeteredConnection = settingsStore.warnOnMeteredConnection,
                timeshiftBufferSizeMb = settingsStore.timeshiftBufferSizeMb,
            ),
        )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val backupIO = StationBackupIO(repository)

    fun setWarnOnMeteredConnection(value: Boolean) {
        settingsStore.warnOnMeteredConnection = value
        _uiState.value = _uiState.value.copy(warnOnMeteredConnection = value)
    }

    fun setTimeshiftBufferSizeMb(value: Int) {
        settingsStore.timeshiftBufferSizeMb = value
        _uiState.value = _uiState.value.copy(timeshiftBufferSizeMb = value)
    }

    init {
        // Skipped when an app store (F-Droid) installed us — it handles updates itself, and the
        // GitHub request would be a needless network call from a store-managed install.
        if (checkForUpdates) {
            viewModelScope.launch {
                // Silent on failure (updateChecker returns null) — leaves updateStatus at UNKNOWN,
                // so the footer just shows the version with no claim about being up to date or not.
                val latest = updateChecker.latestRelease() ?: return@launch
                val isNewer = isNewerVersion(currentVersion, latest.version)
                val status = if (isNewer) UpdateStatus.AVAILABLE else UpdateStatus.UP_TO_DATE
                _uiState.value =
                    _uiState.value.copy(
                        updateStatus = status,
                        updateUrl = latest.url.takeIf { status == UpdateStatus.AVAILABLE },
                    )
            }
        }
    }

    /**
     * Plain suspend function (not launched internally) so callers get the JSON back to write to a
     * file. Returns `null` if there are no saved stations to export.
     */
    suspend fun exportStationsJson(): String? = backupIO.export()

    /**
     * Plain suspend function so callers get the [ImportResult] back to show to the user. Accepts a
     * JSON stations backup or an OPML/M3U/PLS playlist — see [StationBackupIO.import].
     */
    suspend fun importStations(
        context: Context,
        content: String,
    ): ImportResult = backupIO.import(context, content)

    /**
     * Re-inserts any [CuratedStations.pack] entry the user deleted, via
     * [RadioStationRepository.insertStationIfAbsent] (default [NameCollisionPolicy.SKIP]) so a
     * still-present curated station is never duplicated. [context] resolves each entry's bundled
     * icon, same as `MainViewModel.seedCuratedStationsIfNeeded`. Returns how many were restored.
     */
    suspend fun restoreCuratedStations(context: Context): Int =
        CuratedStations.pack.count { station ->
            repository.insertStationIfAbsent(CuratedStations.withResolvedIcon(context, station)) != null
        }

    companion object {
        fun provideFactory(
            repository: RadioStationRepository,
            settingsStore: SettingsStore,
            currentVersion: String,
            checkForUpdates: Boolean = true,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                SettingsViewModel(repository, settingsStore, currentVersion, checkForUpdates = checkForUpdates)
            }
    }
}
