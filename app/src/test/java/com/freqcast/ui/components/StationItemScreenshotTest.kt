package com.freqcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freqcast.data.RadioStation
import com.freqcast.ui.theme.FreqcastTheme
import com.freqcast.ui.theme.Spacing
import com.freqcast.ui.theme.background_gradient_start
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel-level regression tests over [StationItem]'s Card shape/shadow/border and text
 * contrast, using Robolectric Native Graphics — the class of bug a plain unit test can't
 * catch (a shadow clipped into a rectangle, text invisible against its background).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StationItemScreenshotTest {
    // Tolerates the antialiasing drift between recording and verification environments
    // (e.g. a Compose-compiler/Roborazzi version bump) without masking a real visual
    // regression - see AGENTS.md's Testing section.
    private val roborazziOptions =
        RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
        )

    private val station =
        RadioStation(
            id = 1,
            name = "Radio Paradise",
            streamUrl = "https://stream.radioparadise.com/mp3-192",
        )

    @Composable
    private fun PreviewBackground(content: @Composable () -> Unit) {
        FreqcastTheme {
            Box(
                modifier =
                    Modifier
                        .width(360.dp)
                        .background(background_gradient_start)
                        .padding(Spacing.md),
            ) {
                content()
            }
        }
    }

    @Test
    fun inactiveStationCard() {
        captureRoboImage(roborazziOptions = roborazziOptions) {
            PreviewBackground {
                StationItem(
                    station = station,
                    isActive = false,
                    isPlaying = false,
                    onPlayClick = {},
                    onEditClick = {},
                    onDeleteClick = {},
                    onShareClick = {},
                )
            }
        }
    }

    @Test
    fun activePlayingStationCardShowsEqualizerAndBorder() {
        captureRoboImage(roborazziOptions = roborazziOptions) {
            PreviewBackground {
                StationItem(
                    station = station,
                    isActive = true,
                    isPlaying = true,
                    trackTitle = "On Air: Track Name",
                    onPlayClick = {},
                    onEditClick = {},
                    onDeleteClick = {},
                    onShareClick = {},
                )
            }
        }
    }

    @Test
    fun draggingStationCardShowsAccentBorderAndElevation() {
        captureRoboImage(roborazziOptions = roborazziOptions) {
            PreviewBackground {
                StationItem(
                    station = station,
                    isActive = false,
                    isPlaying = false,
                    isDragging = true,
                    onPlayClick = {},
                    onEditClick = {},
                    onDeleteClick = {},
                    onShareClick = {},
                )
            }
        }
    }
}
