package com.freqcast.util

import androidx.activity.ComponentActivity
import com.freqcast.ui.playback.ClipFormat
import com.freqcast.ui.playback.controller.FakePlaybackController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The success path (destination file written, FileProvider URI resolved, chooser Intent launched)
 * is deliberately not covered here: `androidx.core.content.FileProvider` caches its resolved roots
 * per authority for the whole test JVM, not just per test class - see [StationShareTest]'s doc,
 * which already claims the app's single `com.freqcast.fileprovider` authority as the only place in
 * the suite allowed to reach `FileProvider.getUriForFile`. A second Robolectric activity doing so
 * here - even from an entirely different test class sharing the same Gradle test worker JVM - gets
 * a stale cached root pointing at the *first* activity's cache dir and throws
 * `IllegalArgumentException: Failed to find configured root` (confirmed in practice: adding that
 * assertion here intermittently broke [StationShareTest] depending on Gradle's test class ordering).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClipExportTest {
    @Test
    fun `export reports failure immediately when no clip format is available`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val controller = FakePlaybackController().apply { clipFormat = null }
        var result: Boolean? = null

        ClipExport.export(activity, controller, "Jazz FM", 30_000, "Share clip") { result = it }

        assertEquals(false, result)
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `export reports failure when the underlying clip copy fails`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val controller =
            FakePlaybackController().apply {
                clipFormat = ClipFormat.MP3
                exportClipResult = false
            }
        var result: Boolean? = null

        ClipExport.export(activity, controller, "Jazz FM", 30_000, "Share clip") { result = it }

        assertEquals(false, result)
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }
}
