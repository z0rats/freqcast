package com.urlradiodroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.urlradiodroid.R
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.data.RadioStationRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class AddStationUiState(
    val name: String = "",
    val url: String = "",
    val nameErrorRes: Int? = null,
    val urlErrorRes: Int? = null,
    val isSaving: Boolean = false,
    val isEditing: Boolean = false,
)

sealed interface AddStationEvent {
    data class SaveSucceeded(
        val wasEditing: Boolean,
    ) : AddStationEvent

    data class SaveFailed(
        val message: String?,
    ) : AddStationEvent
}

class AddStationViewModel(
    private val repository: RadioStationRepository,
    private val editingStationId: Long?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddStationUiState(isEditing = editingStationId != null))
    val uiState: StateFlow<AddStationUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<AddStationEvent>(Channel.BUFFERED)
    val events: Flow<AddStationEvent> = eventChannel.receiveAsFlow()

    init {
        editingStationId?.let { id ->
            viewModelScope.launch {
                repository.getStationById(id)?.let { station ->
                    _uiState.value = _uiState.value.copy(name = station.name, url = station.streamUrl)
                }
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.value = _uiState.value.copy(name = value, nameErrorRes = null)
    }

    fun onUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(url = value, urlErrorRes = null)
    }

    fun save() {
        val nameTrimmed = _uiState.value.name.trim()
        val urlTrimmed = _uiState.value.url.trim()

        when {
            nameTrimmed.isEmpty() -> {
                _uiState.value = _uiState.value.copy(nameErrorRes = R.string.enter_name)
                return
            }

            urlTrimmed.isEmpty() -> {
                _uiState.value = _uiState.value.copy(urlErrorRes = R.string.enter_url)
                return
            }

            !AddStationActivity.isValidUrl(urlTrimmed) -> {
                _uiState.value = _uiState.value.copy(urlErrorRes = R.string.error_invalid_url)
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                val excludeId = editingStationId ?: 0L
                val nameTaken = repository.isNameTaken(nameTrimmed, excludeId)
                val urlTaken = repository.isUrlTaken(urlTrimmed, excludeId)

                when {
                    nameTaken -> {
                        _uiState.value =
                            _uiState.value.copy(isSaving = false, nameErrorRes = R.string.error_duplicate_name)
                    }

                    urlTaken -> {
                        _uiState.value =
                            _uiState.value.copy(isSaving = false, urlErrorRes = R.string.error_duplicate_url)
                    }

                    else -> {
                        val id = editingStationId
                        val station =
                            if (id != null) {
                                RadioStation(id = id, name = nameTrimmed, streamUrl = urlTrimmed, customIcon = null)
                            } else {
                                RadioStation(name = nameTrimmed, streamUrl = urlTrimmed, customIcon = null)
                            }
                        if (id != null) {
                            repository.updateStation(station)
                        } else {
                            repository.insertStation(station)
                        }
                        _uiState.value = _uiState.value.copy(isSaving = false)
                        eventChannel.send(AddStationEvent.SaveSucceeded(wasEditing = id != null))
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false)
                eventChannel.send(AddStationEvent.SaveFailed(e.message))
            }
        }
    }

    companion object {
        fun provideFactory(
            repository: RadioStationRepository,
            editingStationId: Long?,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AddStationViewModel(repository, editingStationId) as T
            }
    }
}
