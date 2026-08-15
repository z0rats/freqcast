package com.freqcast.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioTagRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioTagRepository

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RadioTagRepository(database.radioTagDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `isEmpty is true before any tags are cached`() =
        runTest {
            assertTrue(repository.isEmpty())
        }

    @Test
    fun `replaceAll populates the cache`() =
        runTest {
            repository.replaceAll(listOf(RadioTag("jazz", 100), RadioTag("rock", 50)))

            assertFalse(repository.isEmpty())
        }

    @Test
    fun `searchByPrefix matches only tags starting with the given prefix`() =
        runTest {
            repository.replaceAll(listOf(RadioTag("jazz", 100), RadioTag("jazzy", 5), RadioTag("rock", 50)))

            val results = repository.searchByPrefix("ja")

            assertEquals(setOf("jazz", "jazzy"), results.map { it.tag }.toSet())
        }

    @Test
    fun `searchByPrefix orders matches by station count descending`() =
        runTest {
            repository.replaceAll(listOf(RadioTag("jazzy", 5), RadioTag("jazz", 100)))

            val results = repository.searchByPrefix("ja")

            assertEquals(listOf("jazz", "jazzy"), results.map { it.tag })
        }

    @Test
    fun `searchByPrefix is case-insensitive`() =
        runTest {
            repository.replaceAll(listOf(RadioTag("jazz", 10)))

            val results = repository.searchByPrefix("JA")

            assertEquals(listOf("jazz"), results.map { it.tag })
        }

    @Test
    fun `searchByPrefix respects the limit`() =
        runTest {
            repository.replaceAll((1..20).map { RadioTag("jazz$it", it) })

            val results = repository.searchByPrefix("jazz", limit = 3)

            assertEquals(3, results.size)
        }

    @Test
    fun `searchByPrefix returns nothing for a tag that was never cached`() =
        runTest {
            repository.replaceAll(listOf(RadioTag("jazz", 10)))

            val results = repository.searchByPrefix("rock")

            assertTrue(results.isEmpty())
        }

    @Test
    fun `replaceAll clears any previously cached tags`() =
        runTest {
            repository.replaceAll(listOf(RadioTag("oldtag", 1)))

            repository.replaceAll(listOf(RadioTag("newtag", 1)))

            assertTrue(repository.searchByPrefix("old").isEmpty())
        }
}
