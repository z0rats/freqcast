package com.freqcast.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freqcast.R
import com.freqcast.data.RadioBrowserApi
import com.freqcast.data.RadioBrowserStation
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import com.freqcast.data.ResolveStage
import com.freqcast.data.ResolvedStation
import com.freqcast.data.SniffedRequest
import com.freqcast.data.StationUrlResolver
import com.freqcast.util.IconStorage
import com.freqcast.util.StreamValidator
import com.freqcast.util.WebViewStreamSniffer
import com.freqcast.util.isVpnActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class AddStationUiState(
    val name: String = "",
    val url: String = "",
    val customIcon: String? = null,
    val description: String = "",
    val nameErrorRes: Int? = null,
    val urlErrorRes: Int? = null,
    val isSaving: Boolean = false,
    /** What [AddStationViewModel.save] is currently doing, shown on the save button while [isSaving]. */
    val savingStageRes: Int? = null,
    val isEditing: Boolean = false,
    /**
     * Carried through unedited from the loaded station (this form has no way to reorder) so
     * save()'s full-row @Update doesn't reset the station's manual list position back to 0. Only
     * meaningful when editing — a new station's sortOrder is always assigned by
     * RadioStationRepository.insertStation() regardless of this field's value.
     */
    val sortOrder: Int = 0,
    /** Carried through unedited from the loaded station (this form has no HLS toggle) so save() doesn't clear it. */
    val isHls: Boolean = false,
    /** Carried through unedited from the loaded station (this form has no way to set it) so save() doesn't clear it. */
    val radioBrowserUuid: String? = null,
    /**
     * Non-null while [CandidateStationPickerSheet][com.freqcast.ui.components.CandidateStationPickerSheet]
     * is showing - set when [StationUrlResolver.resolve][com.freqcast.data.StationUrlResolver.resolve]'s
     * directory search finds several stations sharing the pasted homepage and can't pick one on its
     * own. Cleared by [AddStationViewModel.selectCandidate] or [AddStationViewModel.dismissCandidates].
     */
    val candidateStations: List<RadioBrowserStation>? = null,
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
    private val appContext: Context,
    private val streamValidator: StreamValidator = StreamValidator(),
    private val webViewStreamSniffer: WebViewStreamSniffer = WebViewStreamSniffer(appContext),
    // A mutable *holder* (not a `var` property) referenced by the webViewSniff lambda default
    // below: a constructor property default expression can only read an earlier parameter's
    // value, not reassign a `var` property on `this` - `this` isn't fully constructed yet at that
    // point ("Cannot access '<this>' before the instance has been initialized"). Mutating an
    // already-constructed object's contents (same as calling webViewStreamSniffer.sniff() here)
    // is fine; only reassigning the reference itself wouldn't be. Set by that lambda, reset at the
    // top of every resolveStation() call.
    private val lastSniffTlsFailure: AtomicBoolean = AtomicBoolean(false),
    private val stationUrlResolver: StationUrlResolver =
        StationUrlResolver(
            streamValidator = streamValidator,
            // StationUrlResolver's webViewSniff contract is a plain (String) -> List<SniffedRequest>,
            // so WebViewStreamSniffer.SniffResult's extra hadTlsFailure signal is captured into
            // lastSniffTlsFailure here rather than threaded through that contract - keeps
            // StationUrlResolver (and its tests) untouched by a signal only this class acts on.
            webViewSniff = { url ->
                val result = webViewStreamSniffer.sniff(url)
                lastSniffTlsFailure.set(result.hadTlsFailure)
                result.candidates.map { SniffedRequest(it.url, it.headers) }
            },
        ),
    private val radioBrowserApi: RadioBrowserApi = RadioBrowserApi(),
    /** Fakeable in tests without a Robolectric ConnectivityManager shadow; defaults to the real check. */
    private val checkVpnActive: () -> Boolean = { isVpnActive(appContext) },
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddStationUiState(isEditing = editingStationId != null))
    val uiState: StateFlow<AddStationUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<AddStationEvent>(Channel.BUFFERED)
    val events: Flow<AddStationEvent> = eventChannel.receiveAsFlow()

    /** The icon the station had in the DB before this edit session, for cleanup once a replacement is saved. */
    private var originalCustomIcon: String? = null

    init {
        editingStationId?.let { id ->
            viewModelScope.launch {
                repository.getStationById(id)?.let { station ->
                    originalCustomIcon = station.customIcon
                    _uiState.value =
                        _uiState.value.copy(
                            name = station.name,
                            url = station.streamUrl,
                            customIcon = station.customIcon,
                            description = station.description.orEmpty(),
                            sortOrder = station.sortOrder,
                            isHls = station.isHls,
                            radioBrowserUuid = station.radioBrowserUuid,
                        )
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

    fun onDescriptionChange(value: String) {
        _uiState.value = _uiState.value.copy(description = value)
    }

    fun onEmojiIconSelected(emoji: String) {
        _uiState.value = _uiState.value.copy(customIcon = emoji)
    }

    fun onImageIconSelected(path: String) {
        _uiState.value = _uiState.value.copy(customIcon = path)
    }

    fun onRemoveIcon() {
        _uiState.value = _uiState.value.copy(customIcon = null)
    }

    fun save() {
        val nameTrimmed = _uiState.value.name.trim()
        // A pasted homepage/stream link may arrive with no scheme at all (e.g. "radioznb.ru") -
        // normalized once here so neither isValidUrl nor anything downstream rejects it just for
        // that.
        val urlTrimmed =
            _uiState.value.url
                .trim()
                .let { if (!it.contains("://")) "http://$it" else it }

        when {
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
            _uiState.value = _uiState.value.copy(isSaving = true, savingStageRes = null)
            try {
                val excludeId = editingStationId ?: 0L
                if (nameTrimmed.isNotEmpty() && repository.isNameTaken(nameTrimmed, excludeId)) {
                    _uiState.value =
                        _uiState.value.copy(
                            isSaving = false,
                            savingStageRes = null,
                            nameErrorRes = R.string.error_duplicate_name,
                        )
                    return@launch
                }

                // urlTrimmed may be a stream URL (used as-is) or a station homepage - a
                // non-technical user's more likely starting point - which stationUrlResolver
                // tries to turn into one via the Radio Browser directory or by scraping the page.
                var ambiguousCandidates: List<RadioBrowserStation>? = null
                var tlsBlocked = false
                val resolved =
                    resolveStation(
                        urlTrimmed,
                        onAmbiguous = { candidates -> ambiguousCandidates = candidates },
                        onTlsBlocked = { tlsBlocked = true },
                    )
                if (resolved == null) {
                    val candidates = ambiguousCandidates
                    _uiState.value =
                        when {
                            !candidates.isNullOrEmpty() -> {
                                _uiState.value.copy(
                                    isSaving = false,
                                    savingStageRes = null,
                                    candidateStations = candidates,
                                )
                            }

                            tlsBlocked && checkVpnActive() -> {
                                _uiState.value.copy(
                                    isSaving = false,
                                    savingStageRes = null,
                                    urlErrorRes = R.string.error_stream_blocked_vpn,
                                )
                            }

                            else -> {
                                _uiState.value.copy(
                                    isSaving = false,
                                    savingStageRes = null,
                                    urlErrorRes = R.string.error_stream_unreachable,
                                )
                            }
                        }
                    return@launch
                }

                finalizeSave(resolved)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, savingStageRes = null)
                eventChannel.send(AddStationEvent.SaveFailed(e.message))
            }
        }
    }

    /**
     * The write path once a [ResolvedStation] is in hand, shared by [save] (direct resolve) and
     * [selectCandidate] (user picked one from [AddStationUiState.candidateStations]) - everything
     * from the duplicate-URL check through the success event, unchanged from before either call
     * site existed. Reads current form state directly rather than taking it as parameters, same as
     * it always did as part of [save].
     */
    private suspend fun finalizeSave(resolved: ResolvedStation) {
        val excludeId = editingStationId ?: 0L
        if (repository.isUrlTaken(resolved.streamUrl, excludeId)) {
            _uiState.value =
                _uiState.value.copy(
                    isSaving = false,
                    savingStageRes = null,
                    urlErrorRes = R.string.error_duplicate_url,
                )
            return
        }

        // Left blank, the name is parsed from the resolved station (Radio Browser listing
        // or the homepage's own <title>) or, failing that, the stream's own host - so
        // "add station" never actually requires typing one.
        val nameTrimmed = _uiState.value.name.trim()
        val finalName = nameTrimmed.ifEmpty { uniqueAutoName(resolved, excludeId) }

        val id = editingStationId
        // Downloaded before the station is ever written, not patched in afterward: this
        // screen's own suspend fun already blocks "save" on the resolve round-trip, so
        // there's no separate "save finished, list already reloaded" moment for a
        // fire-and-forget update to lose a race against (MainViewModel.stations is a
        // one-shot load, not a live Room Flow, so a later background write wouldn't show up
        // until the next unrelated reload). Only attempted when the user left the icon on
        // its default (auto emoji) - an explicitly picked emoji or image is never
        // overwritten. Falls back to the auto-generated emoji (already showing) if there's
        // no favicon candidate, or it turns out unreachable/unparseable as an image.
        val finalIcon =
            _uiState.value.customIcon ?: resolved.favicon?.let { faviconUrl ->
                _uiState.value = _uiState.value.copy(savingStageRes = R.string.stage_downloading_icon)
                downloadFavicon(faviconUrl)
            }
        val finalDescription =
            _uiState.value.description
                .trim()
                .ifBlank { null }
        val station =
            if (id != null) {
                RadioStation(
                    id = id,
                    name = finalName,
                    streamUrl = resolved.streamUrl,
                    customIcon = finalIcon,
                    sortOrder = _uiState.value.sortOrder,
                    description = finalDescription,
                    isHls = resolved.isHls || _uiState.value.isHls,
                    radioBrowserUuid = resolved.radioBrowserUuid ?: _uiState.value.radioBrowserUuid,
                )
            } else {
                RadioStation(
                    name = finalName,
                    streamUrl = resolved.streamUrl,
                    customIcon = finalIcon,
                    description = finalDescription,
                    isHls = resolved.isHls,
                    radioBrowserUuid = resolved.radioBrowserUuid,
                )
            }
        if (id != null) {
            repository.updateStation(station)
        } else {
            repository.insertStation(station)
        }
        if (originalCustomIcon != null && originalCustomIcon != finalIcon) {
            IconStorage.delete(originalCustomIcon)
        }
        _uiState.value = _uiState.value.copy(isSaving = false, savingStageRes = null, url = resolved.streamUrl)
        eventChannel.send(AddStationEvent.SaveSucceeded(wasEditing = id != null))
    }

    /**
     * Called from [com.freqcast.ui.components.CandidateStationPickerSheet] when the user taps one
     * of [AddStationUiState.candidateStations]. Doesn't re-run the name-taken check [save] already
     * did before resolving turned out ambiguous - that check already passed on the [save] call that
     * produced the picker, and the sheet is modal (blocks background interaction), so the name
     * field can't have changed while it was up.
     */
    fun selectCandidate(candidate: RadioBrowserStation) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(candidateStations = null, isSaving = true, savingStageRes = null)
            try {
                val resolved = stationUrlResolver.resolveCandidate(candidate)
                if (resolved == null) {
                    _uiState.value =
                        _uiState.value.copy(isSaving = false, urlErrorRes = R.string.error_stream_unreachable)
                    return@launch
                }
                finalizeSave(resolved)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, savingStageRes = null)
                eventChannel.send(AddStationEvent.SaveFailed(e.message))
            }
        }
    }

    fun dismissCandidates() {
        _uiState.value = _uiState.value.copy(candidateStations = null)
    }

    /**
     * A name for [resolved] when the user left the name field blank: the directory/homepage name
     * [resolved] already carries, or else a label derived from the stream's own host (e.g.
     * "Radioznb" from `server.radioznb.ru`) - deduplicated via [RadioStationRepository.uniqueName]
     * so this never collides with an existing station.
     */
    private suspend fun uniqueAutoName(
        resolved: ResolvedStation,
        excludeId: Long,
    ): String {
        val base = resolved.name?.trim()?.ifBlank { null } ?: deriveNameFromUrl(resolved.streamUrl)
        return repository.uniqueName(base, excludeId)
    }

    /** Falls back to the stream's own host label (e.g. "Radioznb" from "server.radioznb.ru") when nothing better is known. */
    private fun deriveNameFromUrl(url: String): String {
        val host = stationUrlResolver.hostOf(url) ?: return DEFAULT_STATION_NAME
        val label = stationUrlResolver.searchKeyword(host) ?: host
        return label.replaceFirstChar { it.uppercase() }
    }

    /**
     * A pasted URL that's already a reachable audio stream is used as-is; anything else -
     * unreachable, or reachable but serving an ordinary webpage - falls back to
     * [stationUrlResolver], which treats it as a homepage to resolve instead. Reachability alone
     * isn't enough to tell the two apart, since a station's homepage is normally just as
     * reachable as its stream; the response's `Content-Type` is what actually distinguishes them.
     *
     * Reports its progress via [AddStationUiState.savingStageRes] as it goes, since this can take
     * several round-trips (direct-stream probe, then possibly the Radio Browser directory and/or
     * a page scrape) - a plain spinner alone would leave the user guessing how much is left.
     */
    private suspend fun resolveStation(
        url: String,
        onAmbiguous: (List<RadioBrowserStation>) -> Unit = {},
        onTlsBlocked: () -> Unit = {},
    ): ResolvedStation? {
        // Stale from a previous, unrelated save() attempt otherwise - stage 5 (WebView) doesn't
        // always run, so nothing else is guaranteed to overwrite this before it's read below.
        lastSniffTlsFailure.set(false)
        _uiState.value = _uiState.value.copy(savingStageRes = R.string.stage_checking_url)
        val probe = streamValidator.probe(url)
        // A TLS handshake reset on this very first, direct connection attempt is already a strong
        // enough signal to act on. It is *not* the only place this can show up, though - the
        // pasted homepage can load over TLS just fine while a *different* host its JS talks to
        // (e.g. a separate API/backend domain) is the one actually getting blocked; that case only
        // surfaces later, from stage 5's WebView load, via lastSniffHadTlsFailure below.
        if (probe.tlsHandshakeFailed) onTlsBlocked()
        return if (probe.reachable && probe.looksLikeAudio) {
            // No favicon here: a bare stream URL never went through a homepage fetch, so there's
            // nothing to read a real `<link rel="icon">` from - only [fromDirectory] and
            // [fromHtml] below have a page (or directory listing) to find one on.
            ResolvedStation(streamUrl = url, isHls = probe.contentType.orEmpty().contains("mpegurl", ignoreCase = true))
        } else {
            val resolved =
                stationUrlResolver.resolve(url, onAmbiguous = onAmbiguous) { stage ->
                    _uiState.value = _uiState.value.copy(savingStageRes = stage.toStageRes())
                }
            if (resolved == null && lastSniffTlsFailure.get()) onTlsBlocked()
            resolved
        }
    }

    /** Downloads and persists [faviconUrl] as a station icon file; null on any failure (network, decode, or storage). */
    private suspend fun downloadFavicon(faviconUrl: String): String? {
        val bytes = radioBrowserApi.downloadFavicon(faviconUrl) ?: return null
        return withContext(Dispatchers.IO) { IconStorage.saveImageBytes(appContext, bytes) }
    }

    private fun ResolveStage.toStageRes(): Int =
        when (this) {
            ResolveStage.SEARCHING_DIRECTORY -> R.string.stage_searching_directory
            ResolveStage.SCANNING_PAGE -> R.string.stage_scanning_page
            ResolveStage.RENDERING_PAGE -> R.string.stage_rendering_page
        }

    companion object {
        /** Only reached if the resolved stream URL somehow doesn't even parse as a URL - shouldn't happen in practice. */
        private const val DEFAULT_STATION_NAME = "New Station"

        fun provideFactory(
            repository: RadioStationRepository,
            editingStationId: Long?,
            context: Context,
        ): ViewModelProvider.Factory =
            viewModelFactory { AddStationViewModel(repository, editingStationId, context.applicationContext) }
    }
}
