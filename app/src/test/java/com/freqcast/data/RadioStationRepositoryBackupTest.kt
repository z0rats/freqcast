package com.freqcast.data

import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
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
                // See DiscoverStationsViewModelTest: keeps Room's suspend DAO calls off its own
                // real thread pool so they can't race the virtual test dispatcher.
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
    fun `exportStationsToJson with no stations returns an empty array`() =
        runTest {
            assertEquals("[]", repository.exportStationsToJson())
        }

    @Test
    fun `exportStationsToJson serializes name, streamUrl, customIcon and description`() =
        runTest {
            repository.insertStation(
                RadioStation(
                    name = "Rock FM",
                    streamUrl = "http://example.com/rock",
                    customIcon = "🎸",
                    description = "rock,classic rock",
                ),
            )
            repository.insertStation(RadioStation(name = "Jazz Radio", streamUrl = "http://example.com/jazz"))

            val array = JSONArray(repository.exportStationsToJson())

            assertEquals(2, array.length())
            assertEquals("Rock FM", array.getJSONObject(0).getString("name"))
            assertEquals("http://example.com/rock", array.getJSONObject(0).getString("streamUrl"))
            assertEquals("🎸", array.getJSONObject(0).getString("customIcon"))
            assertEquals("rock,classic rock", array.getJSONObject(0).getString("description"))
            assertTrue(array.getJSONObject(1).isNull("customIcon"))
            assertTrue(array.getJSONObject(1).isNull("description"))
        }

    @Test
    fun `importStationsFromJson reads description, isHls and radioBrowserUuid when present`() =
        runTest {
            val json =
                """
                [{
                  "name": "Rock FM", "streamUrl": "http://example.com/rock",
                  "description": "rock", "isHls": true, "radioBrowserUuid": "uuid-1"
                }]
                """.trimIndent()

            repository.importStationsFromJson(json)

            assertEquals("rock", repository.getAllStations()[0].description)
            assertEquals(true, repository.getAllStations()[0].isHls)
            assertEquals("uuid-1", repository.getAllStations()[0].radioBrowserUuid)
        }

    @Test
    fun `importStationsFromJson falls back to the older 'genre' key when description is absent`() =
        runTest {
            val json = """[{"name": "Rock FM", "streamUrl": "http://example.com/rock", "genre": "rock"}]"""

            repository.importStationsFromJson(json)

            assertEquals("rock", repository.getAllStations()[0].description)
        }

    @Test
    fun `importStationsFromJson defaults description, isHls and radioBrowserUuid for older backups`() =
        runTest {
            val json = """[{"name": "Rock FM", "streamUrl": "http://example.com/rock"}]"""

            repository.importStationsFromJson(json)

            assertNull(repository.getAllStations()[0].description)
            assertEquals(false, repository.getAllStations()[0].isHls)
            assertNull(repository.getAllStations()[0].radioBrowserUuid)
        }

    @Test
    fun `importStationsFromJson ignores a leftover isFavorite field from an older backup`() =
        runTest {
            val json =
                """[{"name": "Rock FM", "streamUrl": "http://example.com/rock", "isFavorite": true}]"""

            val result = repository.importStationsFromJson(json)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 0), result)
            assertEquals("Rock FM", repository.getAllStations()[0].name)
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
    fun `importStationsFromPlaylist imports OPML entries and skips duplicates`() =
        runTest {
            repository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://example.com/existing-rock"))
            val opml =
                """
                <opml><body>
                  <outline text="Rock FM" xmlUrl="http://example.com/rock"/>
                  <outline text="Jazz Radio" xmlUrl="http://example.com/jazz"/>
                </body></opml>
                """.trimIndent()

            val result = repository.importStationsFromPlaylist(opml)

            assertEquals(ImportResult(imported = 1, skipped = 1, failed = 0), result)
            assertEquals(listOf("Rock FM", "Jazz Radio"), repository.getAllStations().map { it.name })
        }

    @Test
    fun `importStationsFromPlaylist imports M3U entries`() =
        runTest {
            val m3u = "#EXTM3U\n#EXTINF:-1,Rock FM\nhttp://example.com/rock"

            val result = repository.importStationsFromPlaylist(m3u)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 0), result)
            assertEquals("Rock FM", repository.getAllStations()[0].name)
            assertEquals("http://example.com/rock", repository.getAllStations()[0].streamUrl)
        }

    @Test
    fun `importStationsFromPlaylist imports PLS entries`() =
        runTest {
            val pls = "[playlist]\nFile1=http://example.com/rock\nTitle1=Rock FM\nNumberOfEntries=1"

            val result = repository.importStationsFromPlaylist(pls)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 0), result)
            assertEquals("Rock FM", repository.getAllStations()[0].name)
        }

    @Test
    fun `importStationsFromPlaylist throws IllegalArgumentException for unrecognized content`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.importStationsFromPlaylist("not a playlist") }
        }
    }

    @Test
    fun `importStations dispatches to JSON import for a JSON array`() =
        runTest {
            val json = """[{"name": "Rock FM", "streamUrl": "http://example.com/rock", "description": "rock"}]"""

            val result = repository.importStations(json)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 0), result)
            assertEquals("rock", repository.getAllStations()[0].description)
        }

    @Test
    fun `importStations dispatches to playlist import for M3U content`() =
        runTest {
            val m3u = "#EXTM3U\n#EXTINF:-1,Rock FM\nhttp://example.com/rock"

            val result = repository.importStations(m3u)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 0), result)
            assertEquals("Rock FM", repository.getAllStations()[0].name)
        }

    @Test
    fun `export then import into a fresh database round-trips all fields`() =
        runTest {
            repository.insertStation(
                RadioStation(
                    name = "Rock FM",
                    streamUrl = "http://example.com/rock",
                    customIcon = "🎸",
                    description = "rock",
                ),
            )
            repository.insertStation(RadioStation(name = "Jazz Radio", streamUrl = "http://example.com/jazz"))
            val json = repository.exportStationsToJson()

            val freshDatabase =
                Room
                    .inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(),
                        AppDatabase::class.java,
                    ).allowMainThreadQueries()
                    .setQueryExecutor { it.run() }
                    .setTransactionExecutor { it.run() }
                    .build()
            val freshRepository = RadioStationRepository(freshDatabase.radioStationDao())

            val result = freshRepository.importStationsFromJson(json)

            assertEquals(ImportResult(imported = 2, skipped = 0, failed = 0), result)
            val imported = freshRepository.getAllStations()
            assertEquals("Rock FM", imported[0].name)
            assertEquals("http://example.com/rock", imported[0].streamUrl)
            assertEquals("🎸", imported[0].customIcon)
            assertEquals("rock", imported[0].description)
            assertEquals("Jazz Radio", imported[1].name)
            assertNull(imported[1].customIcon)
            assertNull(imported[1].description)
            freshDatabase.close()
        }
}
