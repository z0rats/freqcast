package com.urlradiodroid.data

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioStationRepositoryBackupTest {
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
                .build()
        repository = RadioStationRepository(database.radioStationDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `exportStationsToJson with no stations returns an empty array`() =
        runTest {
            assertEquals("[]", repository.exportStationsToJson())
        }

    @Test
    fun `exportStationsToJson serializes name, streamUrl, customIcon and isFavorite`() =
        runTest {
            val id =
                repository.insertStation(
                    RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock", customIcon = "🎸"),
                )
            repository.setFavorite(id, true)
            repository.insertStation(RadioStation(name = "Jazz Radio", streamUrl = "http://example.com/jazz"))

            val array = JSONArray(repository.exportStationsToJson())

            assertEquals(2, array.length())
            assertEquals("Rock FM", array.getJSONObject(0).getString("name"))
            assertEquals("http://example.com/rock", array.getJSONObject(0).getString("streamUrl"))
            assertEquals("🎸", array.getJSONObject(0).getString("customIcon"))
            assertTrue(array.getJSONObject(0).getBoolean("isFavorite"))
            assertTrue(array.getJSONObject(1).isNull("customIcon"))
            assertFalse(array.getJSONObject(1).getBoolean("isFavorite"))
        }

    @Test
    fun `importStationsFromJson reads isFavorite when present`() =
        runTest {
            val json = """[{"name": "Rock FM", "streamUrl": "http://example.com/rock", "isFavorite": true}]"""

            repository.importStationsFromJson(json)

            assertEquals(true, repository.getAllStations()[0].isFavorite)
        }

    @Test
    fun `importStationsFromJson defaults isFavorite to false for older backups without the field`() =
        runTest {
            val json = """[{"name": "Rock FM", "streamUrl": "http://example.com/rock"}]"""

            repository.importStationsFromJson(json)

            assertEquals(false, repository.getAllStations()[0].isFavorite)
        }

    @Test
    fun `importStationsFromJson imports all valid entries`() =
        runTest {
            val json =
                """
                [
                  {"name": "Rock FM", "streamUrl": "http://example.com/rock", "customIcon": "🎸"},
                  {"name": "Jazz Radio", "streamUrl": "http://example.com/jazz", "customIcon": null}
                ]
                """.trimIndent()

            val result = repository.importStationsFromJson(json)

            assertEquals(ImportResult(imported = 2, skipped = 0, failed = 0), result)
            val stations = repository.getAllStations()
            assertEquals(2, stations.size)
            assertEquals("🎸", stations[0].customIcon)
            assertNull(stations[1].customIcon)
        }

    @Test
    fun `importStationsFromJson skips entries whose name or url already exists`() =
        runTest {
            repository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://example.com/existing-rock"))
            repository.insertStation(RadioStation(name = "Existing Url Station", streamUrl = "http://example.com/jazz"))
            val json =
                """
                [
                  {"name": "Rock FM", "streamUrl": "http://example.com/rock"},
                  {"name": "Jazz Radio", "streamUrl": "http://example.com/jazz"},
                  {"name": "New Station", "streamUrl": "http://example.com/new"}
                ]
                """.trimIndent()

            val result = repository.importStationsFromJson(json)

            assertEquals(ImportResult(imported = 1, skipped = 2, failed = 0), result)
            assertEquals(3, repository.getAllStations().size)
        }

    @Test
    fun `importStationsFromJson counts entries missing name or url as failed`() =
        runTest {
            val json =
                """
                [
                  {"name": "", "streamUrl": "http://example.com/a"},
                  {"name": "No Url"},
                  {"streamUrl": "http://example.com/b"},
                  "not an object",
                  {"name": "Valid", "streamUrl": "http://example.com/valid"}
                ]
                """.trimIndent()

            val result = repository.importStationsFromJson(json)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 4), result)
        }

    @Test
    fun `importStationsFromJson throws IllegalArgumentException for non-array JSON`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.importStationsFromJson("not json at all") }
        }
    }

    @Test
    fun `export then import into a fresh database round-trips all fields`() =
        runTest {
            repository.insertStation(
                RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock", customIcon = "🎸"),
            )
            repository.insertStation(RadioStation(name = "Jazz Radio", streamUrl = "http://example.com/jazz"))
            val json = repository.exportStationsToJson()

            val freshDatabase =
                Room
                    .inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(),
                        AppDatabase::class.java,
                    ).allowMainThreadQueries()
                    .build()
            val freshRepository = RadioStationRepository(freshDatabase.radioStationDao())

            val result = freshRepository.importStationsFromJson(json)

            assertEquals(ImportResult(imported = 2, skipped = 0, failed = 0), result)
            val imported = freshRepository.getAllStations()
            assertEquals("Rock FM", imported[0].name)
            assertEquals("http://example.com/rock", imported[0].streamUrl)
            assertEquals("🎸", imported[0].customIcon)
            assertEquals("Jazz Radio", imported[1].name)
            assertNull(imported[1].customIcon)
            freshDatabase.close()
        }
}
