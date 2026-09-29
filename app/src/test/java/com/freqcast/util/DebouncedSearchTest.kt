package com.freqcast.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** No Robolectric needed - pure Kotlin/coroutines, no Android dependency. The ensureActive() race
 * guard itself (a stale run's *own* cancellation not having surfaced by the time it returns) needs
 * a real dispatcher hop to reproduce and is exercised indirectly via MainViewModelTest/
 * DiscoverStationsViewModelTest instead; what's deterministic under virtual time is covered here. */
@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedSearchTest {
    @Test
    fun `launch delivers a successful result after the debounce`() =
        runTest {
            val debounced = DebouncedSearch<Int>(this)
            var result: Result<Int>? = null

            debounced.launch(debounceMs = 300L, block = { 42 }, onResult = { result = it })
            assertNull(result)
            advanceTimeBy(300L)
            advanceUntilIdle()

            assertEquals(Result.success(42), result)
        }

    @Test
    fun `a second launch cancels the first before it completes`() =
        runTest {
            val debounced = DebouncedSearch<Int>(this)
            val results = mutableListOf<Result<Int>>()

            debounced.launch(debounceMs = 1000L, block = { 1 }, onResult = { results += it })
            advanceTimeBy(500L)
            debounced.launch(debounceMs = 1000L, block = { 2 }, onResult = { results += it })
            advanceUntilIdle()

            assertEquals(listOf(Result.success(2)), results)
        }

    @Test
    fun `cancel prevents a pending run from ever calling onResult`() =
        runTest {
            val debounced = DebouncedSearch<Int>(this)
            var called = false

            debounced.launch(debounceMs = 1000L, block = { 1 }, onResult = { called = true })
            advanceTimeBy(500L)
            debounced.cancel()
            advanceUntilIdle()

            assertFalse(called)
        }

    @Test
    fun `launch delivers Result failure for a non-cancellation exception thrown by block`() =
        runTest {
            val debounced = DebouncedSearch<Int>(this)
            val error = RuntimeException("boom")
            var result: Result<Int>? = null

            debounced.launch(debounceMs = 0L, block = { throw error }, onResult = { result = it })
            advanceUntilIdle()

            assertEquals(error, result?.exceptionOrNull())
        }

    @Test
    fun `debounceMs of zero still delivers a result`() =
        runTest {
            val debounced = DebouncedSearch<Int>(this)
            var result: Result<Int>? = null

            debounced.launch(debounceMs = 0L, block = { 7 }, onResult = { result = it })
            advanceUntilIdle()

            assertEquals(Result.success(7), result)
        }
}
