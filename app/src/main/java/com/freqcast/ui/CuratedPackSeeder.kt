package com.freqcast.ui

import android.content.Context
import com.freqcast.data.CuratedStations
import com.freqcast.data.NameCollisionPolicy
import com.freqcast.data.RadioStationRepository
import com.freqcast.ui.playback.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-time [CuratedStations.pack] seeding on a fresh install, plus the one-time swipe-to-reveal
 * hint that teaches the edit/share/delete gesture on the first seeded station. Pulled out of
 * `MainViewModel` since onboarding is a self-contained concern that only happens to run through
 * the same object as the steady-state station list.
 */
class CuratedPackSeeder(
    private val repository: RadioStationRepository,
    private val settingsStore: SettingsStore,
) {
    // Non-null for exactly one station, right after CuratedStations.pack is seeded on a fresh
    // install - StationListPane plays a one-time swipe-to-reveal peek animation on that station,
    // then calls clearSwipeHint(). See SettingsStore.hasShownSwipeHint for why this only ever
    // fires once per install.
    private val _swipeHintStationId = MutableStateFlow<Long?>(null)
    val swipeHintStationId: StateFlow<Long?> = _swipeHintStationId.asStateFlow()

    /**
     * Inserts [CuratedStations.pack] the first time this runs on a given install (guarded by
     * [SettingsStore.hasSeededCuratedPack], so it never re-runs after a user deletes some or all
     * of the pack). Called once from [MainActivity]'s startup effect, before the first station
     * list load. [context] resolves each entry's bundled icon via [CuratedStations.withResolvedIcon]
     * - not stored on this class, same as `SettingsViewModel.importStations`'s per-call Context.
     *
     * Goes through [RadioStationRepository.insertStationIfAbsent] (default
     * [NameCollisionPolicy.SKIP]) rather than a bare `insertStation`, so a broken
     * `hasSeededCuratedPack` invariant - the pack somehow re-running against a non-empty library -
     * skips already-present entries instead of throwing the unique-constraint violation straight
     * out of [MainActivity]'s `LaunchedEffect`.
     */
    suspend fun seedIfNeeded(context: Context) {
        if (settingsStore.hasSeededCuratedPack) return
        val insertedIds =
            CuratedStations.pack.mapNotNull { station ->
                repository.insertStationIfAbsent(CuratedStations.withResolvedIcon(context, station))
            }
        settingsStore.hasSeededCuratedPack = true

        if (!settingsStore.hasShownSwipeHint) {
            // The second entry, not the first - purely a visual choice (demonstrating the gesture
            // on the very first row read as "the whole list works this way" rather than pointing
            // at one specific station). Falls back to the first if the pack ever shrinks to 1.
            _swipeHintStationId.value = insertedIds.getOrNull(1) ?: insertedIds.firstOrNull()
            settingsStore.hasShownSwipeHint = true
        }
    }

    /** Called once the one-time swipe hint animation (see [swipeHintStationId]) has played. */
    fun clearSwipeHint() {
        _swipeHintStationId.value = null
    }
}
