package com.freqcast.ui.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import com.freqcast.data.AppDatabase
import com.freqcast.data.RadioStation
import com.freqcast.data.RadioStationRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the Android Auto / Assistant browse tree's own bookkeeping - [RadioPlaybackServiceAutoTest]
 * already exercises [RadioBrowseTree.loadStations]/[RadioBrowseTree.findStation] end to end through
 * a real service, but [RadioBrowseTree.search] (voice search) and [RadioBrowseTree.mediaItemFor]
 * have no coverage of their own anywhere else.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioBrowseTreeTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RadioStationRepository
    private lateinit var tree: RadioBrowseTree

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        repository = RadioStationRepository(database.radioStationDao())
        tree = RadioBrowseTree(repository, "Freqcast")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `isRoot only matches the root media id`() {
        assertTrue(tree.isRoot(RadioBrowseTree.ROOT_ID))
        assertTrue(!tree.isRoot("https://example.com/stream"))
    }

    @Test
    fun `rootItem is a browsable, non-playable folder titled after the app`() {
        assertEquals(RadioBrowseTree.ROOT_ID, tree.rootItem.mediaId)
        assertEquals(
            "Freqcast",
            tree.rootItem.mediaMetadata.title
                .toString(),
        )
        assertEquals(true, tree.rootItem.mediaMetadata.isBrowsable)
        assertEquals(false, tree.rootItem.mediaMetadata.isPlayable)
    }

    @Test
    fun `mediaItemFor resolves a cached station and is null for an unknown or stale id`() =
        runTest {
            assertNull(tree.mediaItemFor("https://example.com/jazz"))

            repository.insertStation(RadioStation(name = "Jazz FM", streamUrl = "https://example.com/jazz"))
            tree.loadStations()

            val item = tree.mediaItemFor("https://example.com/jazz")
            assertEquals("https://example.com/jazz", item?.mediaId)
            assertEquals("Jazz FM", item?.mediaMetadata?.title.toString())
            assertEquals(true, item?.mediaMetadata?.isPlayable)
            assertEquals(false, item?.mediaMetadata?.isBrowsable)
            assertNull(item?.mediaMetadata?.artworkUri)

            assertNull(tree.mediaItemFor("https://example.com/not-a-real-station"))
        }

    @Test
    fun `search matches station names case-insensitively against the most recent load`() =
        runTest {
            repository.insertStation(RadioStation(name = "Classical FM", streamUrl = "https://example.com/classical"))
            repository.insertStation(RadioStation(name = "Jazz FM", streamUrl = "https://example.com/jazz"))
            repository.insertStation(RadioStation(name = "Rock FM", streamUrl = "https://example.com/rock"))
            tree.loadStations()

            val results = tree.search("jazz")

            assertEquals(1, results.size)
            assertEquals("https://example.com/jazz", results.single().mediaId)
        }

    @Test
    fun `browsable item carries the artwork uri the resolver returns for that station icon`() =
        runTest {
            val icon = "/data/user/0/com.freqcast/files/station_icons/jazz.jpg"
            val artwork = Uri.parse("content://com.freqcast.fileprovider/station_icons/jazz.jpg")
            val withArt =
                RadioBrowseTree(repository, "Freqcast") { customIcon ->
                    if (customIcon == icon) artwork else null
                }
            repository.insertStation(
                RadioStation(name = "Jazz FM", streamUrl = "https://example.com/jazz", customIcon = icon),
            )
            repository.insertStation(
                RadioStation(name = "Talk", streamUrl = "https://example.com/talk", customIcon = "🎙️"),
            )

            val items = withArt.loadStations()

            assertEquals(artwork, items[0].mediaMetadata.artworkUri)
            assertNull(items[1].mediaMetadata.artworkUri)
        }

    @Test
    fun `browsable item carries artwork bytes when the resolver returns them`() =
        runTest {
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val withBytes =
                RadioBrowseTree(
                    repository,
                    "Freqcast",
                    artworkDataFor = { customIcon -> if (customIcon == "/icons/a.jpg") jpeg else null },
                )
            repository.insertStation(
                RadioStation(name = "Jazz FM", streamUrl = "https://example.com/jazz", customIcon = "/icons/a.jpg"),
            )
            repository.insertStation(
                RadioStation(name = "Talk", streamUrl = "https://example.com/talk", customIcon = "🎙️"),
            )

            val items = withBytes.loadStations()

            assertTrue(jpeg.contentEquals(items[0].mediaMetadata.artworkData))
            assertNull(items[1].mediaMetadata.artworkData)
            assertNull(items[0].mediaMetadata.artworkUri)
        }

    @Test
    fun `search returns nothing before loadStations has ever been called`() {
        assertEquals(emptyList<Any>(), tree.search("anything"))
    }
}
