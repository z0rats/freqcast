package com.freqcast.ui.components

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.freqcast.R
import com.freqcast.data.RadioBrowserStation
import com.freqcast.testing.FakeActivityResultRegistry
import com.freqcast.testing.composeHostRuleChain
import com.freqcast.ui.stationSubtitle
import com.freqcast.util.EmojiGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Directory-facing components: [RadioBrowserResultCard], [CandidateStationPickerSheet],
 * [StationIconPickerDialog], plus the pure [stationSubtitle] they share.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DirectoryComponentsTest {
    private val composeTestRule = createComposeRule()

    @get:Rule
    val ruleChain = composeHostRuleChain(composeTestRule)

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val jazz =
        RadioBrowserStation(
            uuid = "u1",
            name = "Jazz FM",
            url = "https://jazz.example/s",
            country = "Germany",
            tags = "jazz",
            bitrate = 128,
            countryCode = "DE",
            codec = "mp3",
            votes = 1500,
            homepage = "https://jazz.example",
            sslError = true,
        )

    @Test
    fun `subtitle joins distance, flag, tags and bitrate-codec, falling back to the url`() {
        assertEquals("🇩🇪 • jazz • 128 kbps MP3", stationSubtitle(jazz))
        assertEquals(
            "📍 2.5 km • 🇩🇪 • jazz • 128 kbps MP3",
            stationSubtitle(jazz.copy(distanceKm = 2.5)).replace(',', '.'),
        )
        assertEquals("AAC", stationSubtitle(jazz.copy(countryCode = "", tags = "", bitrate = 0, codec = "aac")))
        assertEquals(
            "https://bare.example",
            stationSubtitle(RadioBrowserStation("u", "Bare", "https://bare.example", "", "", 0)),
        )
    }

    @Test
    fun `result card add button fires, votes ssl warning and website link render`() {
        var added = 0
        composeTestRule.setContent { RadioBrowserResultCard(station = jazz, isAdded = false, onAddClick = { added++ }) }

        composeTestRule
            .onNodeWithText(
                com.freqcast.util.VoteCountFormatter
                    .format(1500),
            ).assertExists()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.discover_ssl_warning)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.discover_add)).performClick()
        assertEquals(1, added)

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.visit_website)).performClick()
        val opened = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(Uri.parse("https://jazz.example"), opened.data)
    }

    @Test
    fun `result card for an added station shows a check instead of add, and hides empty extras`() {
        composeTestRule.setContent {
            RadioBrowserResultCard(
                station = jazz.copy(votes = 0, sslError = false, homepage = ""),
                isAdded = true,
                onAddClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.discover_added)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.discover_add)).assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.discover_ssl_warning),
            ).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.visit_website)).assertDoesNotExist()
    }

    @Test
    fun `candidate sheet lists candidates, selecting one or closing reports back`() {
        val rock = jazz.copy(uuid = "u2", name = "Rock FM", url = "https://rock.example/s", votes = 0)
        val selected = mutableListOf<RadioBrowserStation>()
        var dismissed = 0
        composeTestRule.setContent {
            CandidateStationPickerSheet(
                candidates = listOf(jazz, rock),
                onSelect = { selected += it },
                onDismiss = { dismissed++ },
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.candidate_picker_title)).assertExists()
        composeTestRule.onNodeWithText("Rock FM").performClick()
        assertEquals(listOf(rock), selected)

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `icon picker reports emoji, remove and close`() {
        val calls = mutableListOf<String>()
        composeTestRule.setContent {
            StationIconPickerDialog(
                hasCustomIcon = true,
                onDismiss = { calls += "dismiss" },
                onEmojiSelected = { calls += "emoji:$it" },
                onImagePicked = { calls += "image:$it" },
                onRemoveIcon = { calls += "remove" },
            )
        }
        val emoji = EmojiGenerator.pickerEmojis.first()

        composeTestRule.onNodeWithText(emoji).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.remove_icon)).performClick()
        composeTestRule.onNodeWithText(context.getString(R.string.close)).performClick()

        assertEquals(listOf("emoji:$emoji", "remove", "dismiss"), calls)
    }

    @Test
    fun `icon picker hides remove without a custom icon`() {
        composeTestRule.setContent {
            StationIconPickerDialog(hasCustomIcon = false, onDismiss = {
            }, onEmojiSelected = {}, onImagePicked = {}, onRemoveIcon = {})
        }
        composeTestRule.onNodeWithText(context.getString(R.string.remove_icon)).assertDoesNotExist()
    }

    @Test
    fun `icon picker passes a picked image through, and ignores a cancelled pick`() {
        val picked = mutableListOf<Uri>()
        var nextResult: Uri? = Uri.parse("content://media/picked/1")
        val registry = FakeActivityResultRegistry(resultFor = { nextResult })
        composeTestRule.setContent {
            registry.Provide {
                StationIconPickerDialog(
                    hasCustomIcon = false,
                    onDismiss = {},
                    onEmojiSelected = {},
                    onImagePicked = { picked += it },
                    onRemoveIcon = {},
                )
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.upload_image)).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(Uri.parse("content://media/picked/1")), picked)

        nextResult = null
        composeTestRule.onNodeWithText(context.getString(R.string.upload_image)).performClick()
        composeTestRule.waitForIdle()
        assertTrue(picked.size == 1)
    }
}
