package com.urlradiodroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.ui.theme.Spacing
import com.urlradiodroid.ui.theme.URLRadioDroidTheme
import com.urlradiodroid.ui.theme.background_gradient_start
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
    private val station =
        RadioStation(
            id = 1,
            name = "Radio Paradise",
            streamUrl = "https://stream.radioparadise.com/mp3-192",
        )

    @Composable
    private fun PreviewBackground(content: @Composable () -> Unit) {
        URLRadioDroidTheme {
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
        captureRoboImage {
            PreviewBackground {
                StationItem(
                    station = station,
                    isActive = false,
                    isPlaying = false,
                    onPlayClick = {},
                    onFavoriteClick = {},
                    onEditClick = {},
                    onDeleteClick = {},
                    onShareClick = {},
                )
            }
        }
    }

    @Test
    fun activePlayingStationCardShowsEqualizerAndBorder() {
        captureRoboImage {
            PreviewBackground {
                StationItem(
                    station = station.copy(isFavorite = true),
                    isActive = true,
                    isPlaying = true,
                    trackTitle = "On Air: Track Name",
                    onPlayClick = {},
                    onFavoriteClick = {},
                    onEditClick = {},
                    onDeleteClick = {},
                    onShareClick = {},
                )
            }
        }
    }
}
