package com.freqcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.freqcast.data.ImportResult
import com.freqcast.data.RadioStationRepository

class SettingsViewModel(
    private val repository: RadioStationRepository,
) : ViewModel() {
    /** Plain suspend function (not launched internally) so callers get the JSON back to write to a file. */
    suspend fun exportStationsJson(): String = repository.exportStationsToJson()

    /**
     * Plain suspend function so callers get the [ImportResult] back to show to the user. Accepts a
     * JSON stations backup or an OPML/M3U/PLS playlist — see [RadioStationRepository.importStations].
     */
    suspend fun importStations(content: String): ImportResult = repository.importStations(content)

    companion object {
        fun provideFactory(repository: RadioStationRepository): ViewModelProvider.Factory =
            viewModelFactory { SettingsViewModel(repository) }
    }
}
