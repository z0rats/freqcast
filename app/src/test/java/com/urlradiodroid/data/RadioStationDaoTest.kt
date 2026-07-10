package com.urlradiodroid.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioStationDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: RadioStationDao

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        dao = database.radioStationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `test insert and getAllStations`() =
        runTest {
            val station1 =
                RadioStation(
                    name = "Station 1",
                    streamUrl = "http://example.com/stream1",
                )
            val station2 =
                RadioStation(
                    name = "Station 2",
                    streamUrl = "http://example.com/stream2",
                )

            dao.insertStation(station1)
            dao.insertStation(station2)

            val stations = dao.getAllStations()

            assertEquals(2, stations.size)
            assertEquals("Station 1", stations[0].name)
            assertEquals("Station 2", stations[1].name)
        }

    @Test
    fun `test getAllStations returns empty list when no stations`() =
        runTest {
            val stations = dao.getAllStations()

            assertEquals(0, stations.size)
        }

    @Test
    fun `test getAllStations orders by id ascending oldest first`() =
        runTest {
            val station1 = RadioStation(name = "Z Station", streamUrl = "http://example.com/z")
            val station2 = RadioStation(name = "A Station", streamUrl = "http://example.com/a")
            val station3 = RadioStation(name = "M Station", streamUrl = "http://example.com/m")

            dao.insertStation(station1)
            dao.insertStation(station2)
            dao.insertStation(station3)

            val stations = dao.getAllStations()

            assertEquals(3, stations.size)
            assertEquals("Z Station", stations[0].name)
            assertEquals("A Station", stations[1].name)
            assertEquals("M Station", stations[2].name)
        }

    @Test
    fun `test findStationByName returns station when exists`() =
        runTest {
            dao.insertStation(RadioStation(name = "Unique Name", streamUrl = "http://example.com/u"))
            val found = dao.findStationByName("Unique Name")
            assertEquals("Unique Name", found?.name)
            assertEquals("http://example.com/u", found?.streamUrl)
        }

    @Test
    fun `test findStationByName returns null when not found`() =
        runTest {
            val found = dao.findStationByName("No Such Station")
            assertNull(found)
        }

    @Test
    fun `test findStationByName excludes station by excludeId`() =
        runTest {
            val id = dao.insertStation(RadioStation(name = "Same", streamUrl = "http://example.com/1"))
            val found = dao.findStationByName("Same", excludeId = id)
            assertNull(found)
            val foundWithoutExclude = dao.findStationByName("Same", excludeId = 0L)
            assertEquals(id, foundWithoutExclude?.id)
        }

    @Test
    fun `test findStationByUrl returns station when exists`() =
        runTest {
            dao.insertStation(RadioStation(name = "Any", streamUrl = "http://example.com/unique-url"))
            val found = dao.findStationByUrl("http://example.com/unique-url")
            assertEquals("http://example.com/unique-url", found?.streamUrl)
        }

    @Test
    fun `test findStationByUrl returns null when not found`() =
        runTest {
            val found = dao.findStationByUrl("http://example.com/nonexistent")
            assertNull(found)
        }

    @Test
    fun `test findStationByUrl excludes station by excludeId`() =
        runTest {
            val id = dao.insertStation(RadioStation(name = "A", streamUrl = "http://example.com/same"))
            val found = dao.findStationByUrl("http://example.com/same", excludeId = id)
            assertNull(found)
            val foundWithoutExclude = dao.findStationByUrl("http://example.com/same", excludeId = 0L)
            assertEquals(id, foundWithoutExclude?.id)
        }

    @Test
    fun `test updateStation updates and getAllStations returns updated data`() =
        runTest {
            val id = dao.insertStation(RadioStation(name = "Old", streamUrl = "http://example.com/old"))
            val updated = RadioStation(id = id, name = "New", streamUrl = "http://example.com/new")
            dao.updateStation(updated)
            val retrieved = dao.getStationById(id)
            assertEquals("New", retrieved?.name)
            assertEquals("http://example.com/new", retrieved?.streamUrl)
        }

    @Test
    fun `test deleteStation removes station`() =
        runTest {
            val id = dao.insertStation(RadioStation(name = "To Remove", streamUrl = "http://example.com/rm"))
            assertEquals(1, dao.getAllStations().size)
            dao.deleteStation(id)
            assertEquals(0, dao.getAllStations().size)
            assertNull(dao.getStationById(id))
        }

    @Test
    fun `test deleteStation for non-existent id does not crash`() =
        runTest {
            dao.insertStation(RadioStation(name = "Only", streamUrl = "http://example.com/only"))
            dao.deleteStation(999L)
            assertEquals(1, dao.getAllStations().size)
        }

    @Test
    fun `test insertStation with customIcon persists`() =
        runTest {
            val id =
                dao.insertStation(
                    RadioStation(name = "With Icon", streamUrl = "http://example.com/icon", customIcon = "🎵"),
                )
            val retrieved = dao.getStationById(id)
            assertEquals("🎵", retrieved?.customIcon)
        }

    @Test
    fun `test getStationById returns correct station`() =
        runTest {
            val station =
                RadioStation(
                    name = "Test Station",
                    streamUrl = "http://example.com/test",
                )

            val id = dao.insertStation(station)
            val retrieved = dao.getStationById(id)

            assertEquals(station.name, retrieved?.name)
            assertEquals(station.streamUrl, retrieved?.streamUrl)
            assertEquals(id, retrieved?.id)
        }

    @Test
    fun `test getStationById returns null for non-existent id`() =
        runTest {
            val station = dao.getStationById(999L)

            assertNull(station)
        }

    @Test
    fun `test insertStation returns generated id`() =
        runTest {
            val station =
                RadioStation(
                    name = "New Station",
                    streamUrl = "http://example.com/new",
                )

            val id = dao.insertStation(station)

            assert(id > 0)
            val retrieved = dao.getStationById(id)
            assertEquals(id, retrieved?.id)
        }

    @Test
    fun `test insertStation with duplicate name violates unique constraint`() {
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking {
                dao.insertStation(RadioStation(name = "Same Name", streamUrl = "http://example.com/1"))
                dao.insertStation(RadioStation(name = "Same Name", streamUrl = "http://example.com/2"))
            }
        }
    }

    @Test
    fun `test insertStation with duplicate streamUrl violates unique constraint`() {
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking {
                dao.insertStation(RadioStation(name = "Station A", streamUrl = "http://example.com/same"))
                dao.insertStation(RadioStation(name = "Station B", streamUrl = "http://example.com/same"))
            }
        }
    }
}
