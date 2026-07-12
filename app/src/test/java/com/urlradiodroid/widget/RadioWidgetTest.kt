package com.urlradiodroid.widget

import android.content.Intent
import android.graphics.Bitmap
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders [RadioWidgetContent] in isolation with fixed inputs and asserts on the resulting node
 * tree — the pattern `runGlanceAppWidgetUnitTest` is designed for (see its doc), rather than going
 * through [RadioWidget.provideGlance]'s [com.urlradiodroid.ui.playback.WidgetStateStore] read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RadioWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val icon = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private val openIntent = Intent(Intent.ACTION_VIEW)

    @Test
    fun `shows the playing station with a working pause button and skip controls`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable {
                RadioWidgetContent(
                    stationName = "Jazz FM",
                    statusText = "Playing",
                    isPlaying = true,
                    hasStation = true,
                    iconBitmap = icon,
                    openIntent = openIntent,
                )
            }

            onNode(hasText("Jazz FM")).assertExists()
            onNode(hasText("Playing")).assertExists()
            onNode(hasRunCallbackClickAction<TogglePlaybackAction>()).assertExists()
            onNode(hasRunCallbackClickAction<NextStationAction>()).assertExists()
            onNode(hasRunCallbackClickAction<PreviousStationAction>()).assertExists()
            onNode(hasStartActivityClickAction(openIntent)).assertExists()
        }

    @Test
    fun `shows a play button (not pause) when paused`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable {
                RadioWidgetContent(
                    stationName = "Jazz FM",
                    statusText = "Paused",
                    isPlaying = false,
                    hasStation = true,
                    iconBitmap = icon,
                    openIntent = openIntent,
                )
            }

            onNode(hasText("Paused")).assertExists()
            onNode(hasRunCallbackClickAction<TogglePlaybackAction>()).assertExists()
        }

    @Test
    fun `hides playback controls when no station has ever played`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable {
                RadioWidgetContent(
                    stationName = "No station yet",
                    statusText = "Tap to open the app",
                    isPlaying = false,
                    hasStation = false,
                    iconBitmap = icon,
                    openIntent = openIntent,
                )
            }

            onNode(hasText("No station yet")).assertExists()
            onAllNodes(hasRunCallbackClickAction<TogglePlaybackAction>()).assertCountEquals(0)
            onAllNodes(hasRunCallbackClickAction<NextStationAction>()).assertCountEquals(0)
            onAllNodes(hasRunCallbackClickAction<PreviousStationAction>()).assertCountEquals(0)
        }
}
