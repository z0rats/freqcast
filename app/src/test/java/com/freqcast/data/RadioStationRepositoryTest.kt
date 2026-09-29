package com.freqcast.data

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [RadioStationRepository.insertStationIfAbsent] — the one seam every non-interactive "add a
 * station" flow (RadioBrowserStationInstaller, curated-pack seeding/restore) goes through. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioStationRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        repository = RadioStationRepository(database.radioStationDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insertStationIfAbsent inserts when nothing collides`() =
        runTest {
            val id =
                repository.insertStationIfAbsent(
                    RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock"),
                )

            assertEquals("Rock FM", repository.getStationById(id!!)?.name)
        }

    @Test
    fun `insertStationIfAbsent skips a url already saved, regardless of name collision policy`() =
        runTest {
            repository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock"))

            val id =
                repository.insertStationIfAbsent(
                    RadioStation(name = "Different Name", streamUrl = "http://example.com/rock"),
                    onNameCollision = NameCollisionPolicy.RENAME,
                )

            assertNull(id)
            assertEquals(1, repository.getAllStations().size)
        }

    @Test
    fun `insertStationIfAbsent with SKIP policy skips a name collision with a different station`() =
        runTest {
            repository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock"))

            val id =
                repository.insertStationIfAbsent(
                    RadioStation(name = "Rock FM", streamUrl = "http://example.com/other"),
                    onNameCollision = NameCollisionPolicy.SKIP,
                )

            assertNull(id)
            assertEquals(1, repository.getAllStations().size)
        }

    @Test
    fun `insertStationIfAbsent with RENAME policy auto-renames on a name collision with a different station`() =
        runTest {
            repository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock"))

            val id =
                repository.insertStationIfAbsent(
                    RadioStation(name = "Rock FM", streamUrl = "http://example.com/other"),
                    onNameCollision = NameCollisionPolicy.RENAME,
                )

            assertEquals("Rock FM (2)", repository.getStationById(id!!)?.name)
        }

    @Test
    fun `insertStation appends to the end of the manually-ordered list`() =
        runTest {
            repository.insertStation(RadioStation(name = "First", streamUrl = "http://example.com/1"))
            repository.insertStation(RadioStation(name = "Second", streamUrl = "http://example.com/2"))
            repository.insertStation(RadioStation(name = "Third", streamUrl = "http://example.com/3"))

            val stations = repository.getAllStations()

            assertEquals(listOf("First", "Second", "Third"), stations.map { it.name })
            assertEquals(listOf(0, 1, 2), stations.map { it.sortOrder })
        }

    @Test
    fun `updateSortOrder persists a new manual order`() =
        runTest {
            val id1 = repository.insertStation(RadioStation(name = "First", streamUrl = "http://example.com/1"))
            val id2 = repository.insertStation(RadioStation(name = "Second", streamUrl = "http://example.com/2"))
            val id3 = repository.insertStation(RadioStation(name = "Third", streamUrl = "http://example.com/3"))

            repository.updateSortOrder(listOf(id3, id1, id2))

            assertEquals(listOf("Third", "First", "Second"), repository.getAllStations().map { it.name })
        }

    @Test
    fun `restoreStation preserves the original sortOrder instead of appending`() =
        runTest {
            repository.insertStation(RadioStation(name = "First", streamUrl = "http://example.com/1"))
            val toDelete =
                repository.getStationById(
                    repository.insertStation(RadioStation(name = "Second", streamUrl = "http://example.com/2")),
                )!!
            repository.insertStation(RadioStation(name = "Third", streamUrl = "http://example.com/3"))
            repository.deleteStation(toDelete.id)

            repository.restoreStation(toDelete)

            assertEquals(listOf("First", "Second", "Third"), repository.getAllStations().map { it.name })
        }
}
