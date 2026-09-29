package com.freqcast.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Cancel-latest-wins search: each [launch] cancels whatever [launch] before it is still running,
 * so only the most recent one can ever deliver a result. Previously reimplemented independently
 * in three places (`MainViewModel`'s catalog-fallback search, `DiscoverStationsViewModel`'s
 * text/genre search and its `searchNearby`) — the two subtle parts worth getting right exactly
 * once: the [debounceMs] delay before [block] runs at all, and the `ensureActive()` guard right
 * before [onResult] — [block]'s own suspend call isn't itself interruptible (a blocking network
 * request, say), so a cancellation requested while it was in flight may not have surfaced as an
 * exception yet by the time it returns; without the guard a stale, superseded run's result could
 * overwrite whatever a newer run already delivered.
 */
class DebouncedSearch<T>(
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** Cancels any run in progress without starting a new one — e.g. when the query is cleared. */
    fun cancel() {
        job?.cancel()
    }

    /**
     * Cancels any run in progress, waits [debounceMs] (0 for an immediate but still race-safe
     * run — e.g. a tapped chip rather than a keystroke), then runs [block] and delivers its
     * outcome to [onResult] as a [Result]. A [CancellationException] thrown by [block] propagates
     * as an ordinary cancellation rather than reaching [onResult], same as it would uncaught.
     */
    fun launch(
        debounceMs: Long = 0L,
        block: suspend () -> T,
        onResult: (Result<T>) -> Unit,
    ) {
        job?.cancel()
        job =
            scope.launch {
                if (debounceMs > 0) delay(debounceMs)
                val result =
                    try {
                        Result.success(block())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                coroutineContext.ensureActive()
                onResult(result)
            }
    }
}
