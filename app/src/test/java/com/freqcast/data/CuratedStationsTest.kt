package com.freqcast.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.freqcast.util.IconStorage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Native graphics mode (like IconStorageTest) since these decode the real bundled `res/raw` bytes
 * through BitmapFactory/IcoDecoder - the legacy Robolectric shadow doesn't actually decode.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CuratedStationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `withResolvedIcon replaces every pack entry's emoji with a decodable local icon file`() {
        for (station in CuratedStations.pack) {
            val resolved = CuratedStations.withResolvedIcon(context, station)

            val icon = resolved.customIcon
            assertNotNull("expected a resolved icon for ${station.name}", icon)
            assertTrue("expected an image path for ${station.name}, got emoji $icon", IconStorage.isImagePath(icon!!))
            assertTrue("resolved icon file missing for ${station.name}", File(icon).exists())
            assertNotNull("resolved icon didn't decode for ${station.name}", IconStorage.decodeBitmap(icon))
        }
    }

    @Test
    fun `withResolvedIcon falls back to the original customIcon for a name with no bundled resource`() {
        val station = RadioStation(name = "Not In The Pack", streamUrl = "https://example.com/x", customIcon = "📻")

        val resolved = CuratedStations.withResolvedIcon(context, station)

        assertTrue(resolved.customIcon == "📻")
    }
}
