package com.freqcast.data

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import com.freqcast.util.IconStorage
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Native graphics mode (not the legacy Robolectric shadow) so the icon round-trip test's
 * BitmapFactory decode reflects real pixel data - see IconStorageTest for the same rationale. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StationBackupIOTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository
    private lateinit var backupIO: StationBackupIO
    private val context: Context = RuntimeEnvironment.getApplication()
    private val extraDatabases = mutableListOf<AppDatabase>()

    private fun pngBytesFor(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    // See DiscoverStationsViewModelTest: keeps Room's suspend DAO calls off its own real thread
    // pool so they can't race the virtual test dispatcher.
    private fun newInMemoryDatabase(): AppDatabase =
        Room
            .inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                AppDatabase::class.java,
            ).allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

    private fun newInMemoryRepository(): RadioStationRepository {
        val db = newInMemoryDatabase()
        extraDatabases += db
        return RadioStationRepository(db.radioStationDao())
    }

    @Before
    fun setup() {
        database = newInMemoryDatabase()
        repository = RadioStationRepository(database.radioStationDao())
        backupIO = StationBackupIO(repository)
    }

    @After
    fun tearDown() {
        database.close()
        extraDatabases.forEach { it.close() }
    }

    @Test
    fun `export with no stations returns null`() =
        runTest {
            assertNull(backupIO.export())
        }

    @Test
    fun `export serializes name, streamUrl, customIcon and description`() =
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

            val array = JSONArray(backupIO.export())

            assertEquals(2, array.length())
            assertEquals("Rock FM", array.getJSONObject(0).getString("name"))
            assertEquals("http://example.com/rock", array.getJSONObject(0).getString("streamUrl"))
            assertEquals("🎸", array.getJSONObject(0).getString("customIcon"))
            assertEquals("rock,classic rock", array.getJSONObject(0).getString("description"))
            assertTrue(array.getJSONObject(1).isNull("customIcon"))
            assertTrue(array.getJSONObject(1).isNull("description"))
        }

    @Test
    fun `importJson reads description, isHls and radioBrowserUuid when present`() =
        runTest {
            val json =
                """
                [{
                  "name": "Rock FM", "streamUrl": "http://example.com/rock",
                  "description": "rock", "isHls": true, "radioBrowserUuid": "uuid-1"
                }]
                """.trimIndent()

            backupIO.importJson(context, json)

            assertEquals("rock", repository.getAllStations()[0].description)
            assertEquals(true, repository.getAllStations()[0].isHls)
            assertEquals("uuid-1", repository.getAllStations()[0].radioBrowserUuid)
        }

    @Test
    fun `importJson falls back to the older 'genre' key when description is absent`() =
        runTest {
            val json = """[{"name": "Rock FM", "streamUrl": "http://example.com/rock", "genre": "rock"}]"""

            backupIO.importJson(context, json)

            assertEquals("rock", repository.getAllStations()[0].description)
        }

    @Test
    fun `importJson defaults description, isHls and radioBrowserUuid for older backups`() =
        runTest {
            val json = """[{"name": "Rock FM", "streamUrl": "http://example.com/rock"}]"""

            backupIO.importJson(context, json)

            assertNull(repository.getAllStations()[0].description)
            assertEquals(false, repository.getAllStations()[0].isHls)
            assertNull(repository.getAllStations()[0].radioBrowserUuid)
        }

    @Test
    fun `importJson ignores a leftover isFavorite field from an older backup`() =
        runTest {
            val json =
                """[{"name": "Rock FM", "streamUrl": "http://example.com/rock", "isFavorite": true}]"""

            val result = backupIO.importJson(context, json)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 0), result)
            assertEquals("Rock FM", repository.getAllStations()[0].name)
        }

    @Test
    fun `importJson imports all valid entries`() =
        runTest {
            val json =
                """
                [
                  {"name": "Rock FM", "streamUrl": "http://example.com/rock", "customIcon": "🎸"},
                  {"name": "Jazz Radio", "streamUrl": "http://example.com/jazz", "customIcon": null}
                ]
                """.trimIndent()

            val result = backupIO.importJson(context, json)

            assertEquals(ImportResult(imported = 2, skipped = 0, failed = 0), result)
            val stations = repository.getAllStations()
            assertEquals(2, stations.size)
            assertEquals("🎸", stations[0].customIcon)
            assertNull(stations[1].customIcon)
        }

    @Test
    fun `importJson skips entries whose name or url already exists`() =
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

            val result = backupIO.importJson(context, json)

            assertEquals(ImportResult(imported = 1, skipped = 2, failed = 0), result)
            assertEquals(3, repository.getAllStations().size)
        }

    @Test
    fun `importJson counts entries missing name or url as failed`() =
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

            val result = backupIO.importJson(context, json)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 4), result)
        }

    @Test
    fun `importJson throws IllegalArgumentException for non-array JSON`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { backupIO.importJson(context, "not json at all") }
        }
    }

    @Test
    fun `importPlaylist imports OPML entries and skips duplicates`() =
        runTest {
            repository.insertStation(RadioStation(name = "Rock FM", streamUrl = "http://example.com/existing-rock"))
            val opml =
                """
                <opml><body>
                  <outline text="Rock FM" xmlUrl="http://example.com/rock"/>
                  <outline text="Jazz Radio" xmlUrl="http://example.com/jazz"/>
                </body></opml>
                """.trimIndent()

            val result = backupIO.importPlaylist(opml)

            assertEquals(ImportResult(imported = 1, skipped = 1, failed = 0), result)
            assertEquals(listOf("Rock FM", "Jazz Radio"), repository.getAllStations().map { it.name })
        }

    @Test
    fun `importPlaylist imports M3U entries`() =
        runTest {
            val m3u = "#EXTM3U\n#EXTINF:-1,Rock FM\nhttp://example.com/rock"

            val result = backupIO.importPlaylist(m3u)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 0), result)
            assertEquals("Rock FM", repository.getAllStations()[0].name)
            assertEquals("http://example.com/rock", repository.getAllStations()[0].streamUrl)
        }

    @Test
    fun `importPlaylist imports PLS entries`() =
        runTest {
            val pls = "[playlist]\nFile1=http://example.com/rock\nTitle1=Rock FM\nNumberOfEntries=1"

            val result = backupIO.importPlaylist(pls)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 0), result)
            assertEquals("Rock FM", repository.getAllStations()[0].name)
        }

    @Test
    fun `importPlaylist counts a PLS entry with a blank File value as failed`() =
        runTest {
            // A blank File value still leaves an entry in PlaylistImport.parsePls's `files` map
            // (only entries with no '=' at all, or no digit-suffixed File/Title key, are dropped
            // during parsing) - this is the one realistic way a ParsedPlaylistStation reaches
            // importPlaylist with a blank streamUrl.
            val pls =
                "[playlist]\nFile1=http://example.com/rock\nTitle1=Rock FM\n" +
                    "File2=\nTitle2=Broken Entry\nNumberOfEntries=2"

            val result = backupIO.importPlaylist(pls)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 1), result)
            assertEquals("Rock FM", repository.getAllStations().single().name)
        }

    @Test
    fun `importPlaylist throws IllegalArgumentException for unrecognized content`() {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { backupIO.importPlaylist("not a playlist") }
        }
    }

    @Test
    fun `import dispatches to JSON import for a JSON array`() =
        runTest {
            val json = """[{"name": "Rock FM", "streamUrl": "http://example.com/rock", "description": "rock"}]"""

            val result = backupIO.import(context, json)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 0), result)
            assertEquals("rock", repository.getAllStations()[0].description)
        }

    @Test
    fun `import dispatches to playlist import for M3U content`() =
        runTest {
            val m3u = "#EXTM3U\n#EXTINF:-1,Rock FM\nhttp://example.com/rock"

            val result = backupIO.import(context, m3u)

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
            val json = requireNotNull(backupIO.export())

            val freshRepository = newInMemoryRepository()
            val freshBackupIO = StationBackupIO(freshRepository)

            val result = freshBackupIO.importJson(context, json)

            assertEquals(ImportResult(imported = 2, skipped = 0, failed = 0), result)
            val imported = freshRepository.getAllStations()
            assertEquals("Rock FM", imported[0].name)
            assertEquals("http://example.com/rock", imported[0].streamUrl)
            assertEquals("🎸", imported[0].customIcon)
            assertEquals("rock", imported[0].description)
            assertEquals("Jazz Radio", imported[1].name)
            assertNull(imported[1].customIcon)
            assertNull(imported[1].description)
        }

    @Test
    fun `export embeds a locally stored icon's bytes as base64 iconData`() =
        runTest {
            val iconPath = requireNotNull(IconStorage.saveImageBytes(context, pngBytesFor(64, 64)))
            repository.insertStation(
                RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock", customIcon = iconPath),
            )

            val array = JSONArray(backupIO.export())

            assertTrue(array.getJSONObject(0).has("iconData"))
            assertTrue(array.getJSONObject(0).getString("iconData").isNotBlank())
        }

    @Test
    fun `export omits iconData for an emoji icon`() =
        runTest {
            repository.insertStation(
                RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock", customIcon = "🎸"),
            )

            val array = JSONArray(backupIO.export())

            assertTrue(array.getJSONObject(0).isNull("customIcon").not())
            assertTrue(!array.getJSONObject(0).has("iconData"))
        }

    @Test
    fun `export then import round-trips a locally stored icon image onto a fresh device`() =
        runTest {
            val iconPath = requireNotNull(IconStorage.saveImageBytes(context, pngBytesFor(64, 64)))
            repository.insertStation(
                RadioStation(name = "Rock FM", streamUrl = "http://example.com/rock", customIcon = iconPath),
            )
            val json = requireNotNull(backupIO.export())

            // Simulate importing on another device/after a reinstall: the original icon file is gone,
            // so only the embedded iconData payload can restore it.
            File(iconPath).delete()
            val freshRepository = newInMemoryRepository()
            val freshBackupIO = StationBackupIO(freshRepository)

            val result = freshBackupIO.importJson(context, json)

            assertEquals(ImportResult(imported = 1, skipped = 0, failed = 0), result)
            val importedIcon = requireNotNull(freshRepository.getAllStations()[0].customIcon)
            assertTrue(IconStorage.isImagePath(importedIcon))
            assertTrue(File(importedIcon).exists())
            assertNotEquals(iconPath, importedIcon)
            assertNotNull(IconStorage.decodeBitmap(importedIcon))
        }
}
