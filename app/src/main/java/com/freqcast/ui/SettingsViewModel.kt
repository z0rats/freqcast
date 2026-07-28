package com.freqcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freqcast.data.ImportResult
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.UpdateChecker
import com.freqcast.data.isNewerVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class UpdateStatus { UNKNOWN, UP_TO_DATE, AVAILABLE }

data class SettingsUiState(
    val currentVersion: String = "",
    val updateStatus: UpdateStatus = UpdateStatus.UNKNOWN,
    val updateUrl: String? = null,
)

class SettingsViewModel(
    private val repository: RadioStationRepository,
    currentVersion: String,
    private val updateChecker: UpdateChecker = UpdateChecker(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState(currentVersion = currentVersion))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Silent on failure (updateChecker returns null) — leaves updateStatus at UNKNOWN,
            // so the footer just shows the version with no claim about being up to date or not.
            val latest = updateChecker.latestRelease() ?: return@launch
            val status =
                if (isNewerVersion(currentVersion, latest.version)) UpdateStatus.AVAILABLE else UpdateStatus.UP_TO_DATE
            _uiState.value =
                _uiState.value.copy(
                    updateStatus = status,
                    updateUrl = latest.url.takeIf { status == UpdateStatus.AVAILABLE },
                )
        }
    }

    /** Plain suspend function (not launched internally) so callers get the JSON back to write to a file. */
    suspend fun exportStationsJson(): String = repository.exportStationsToJson()

    /**
     * Plain suspend function so callers get the [ImportResult] back to show to the user. Accepts a
     * JSON stations backup or an OPML/M3U/PLS playlist — see [RadioStationRepository.importStations].
     */
    suspend fun importStations(content: String): ImportResult = repository.importStations(content)

    companion object {
        fun provideFactory(
            repository: RadioStationRepository,
            currentVersion: String,
        ): ViewModelProvider.Factory = viewModelFactory { SettingsViewModel(repository, currentVersion) }
    }
}
