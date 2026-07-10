package com.urlradiodroid.util

import android.content.Intent
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * A single test method on purpose: androidx.core.content.FileProvider statically caches its
 * resolved roots per authority for the process lifetime, so a second Robolectric activity (with
 * its own fake cache dir) would collide with the first call's cached root and fail to resolve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StationShareTest {
    @Test
    fun `share writes a sanitized backup file and launches a chooser wrapping ACTION_SEND with it attached`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val json = """[{"name":"Test","streamUrl":"http://example.com"}]"""

        StationShare.share(activity, json, "Share station", "Jazz / Rock: \"Live\"?")

        val backupsDir = File(activity.cacheDir, "backups")
        val writtenFile = backupsDir.listFiles()?.singleOrNull()
        assertNotNull(writtenFile)
        assertEquals("Jazz _ Rock_ _Live__.json", writtenFile!!.name)
        assertEquals(json, writtenFile.readText())

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)

        @Suppress("DEPRECATION")
        val sendIntent = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(sendIntent)
        assertEquals(Intent.ACTION_SEND, sendIntent!!.action)
        assertEquals("application/json", sendIntent.type)
        @Suppress("DEPRECATION")
        val streamUri = sendIntent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertNotNull(streamUri)
        assertTrue(
            sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }
}
