package com.freqcast.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FeedbackLinksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `github issue intent prefills title and body as encoded query params`() {
        val intent = FeedbackLinks.githubIssueIntent(title = "Can't add station", body = "a&b=c\nd")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        val uri = intent.data!!
        assertEquals("github.com", uri.host)
        assertEquals("/z0rats/freqcast/issues/new", uri.path)
        assertEquals("Can't add station", uri.getQueryParameter("title"))
        assertEquals("a&b=c\nd", uri.getQueryParameter("body"))
    }

    @Test
    fun `email intent targets the support address via mailto`() {
        val intent = FeedbackLinks.emailIntent(subject = "Bug", body = "Details")
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        assertArrayEquals(arrayOf("z0rats.dev@gmail.com"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Bug", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Details", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `report body without extra is diagnostics only`() {
        setVersionName("9.8.7")
        val body = FeedbackLinks.reportBody(context)
        assertTrue(body.startsWith("App version: 9.8.7\n"))
        assertTrue(body.contains("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"))
        assertTrue(body.contains("Device: "))
        assertEquals(body, FeedbackLinks.reportBody(context, extra = "   "))
    }

    @Test
    fun `report body puts extra first, separated from diagnostics`() {
        setVersionName("1.0")
        val body = FeedbackLinks.reportBody(context, extra = "Tried to add https://x")
        assertTrue(body.startsWith("Tried to add https://x\n\n---\nApp version: 1.0"))
    }

    @Test
    @Config(sdk = [33])
    fun `app version name uses the Tiramisu package info flags API`() {
        setVersionName("2.0")
        assertEquals("2.0", FeedbackLinks.appVersionName(context))
    }

    @Test
    fun `missing version name becomes empty`() {
        setVersionName(null)
        assertEquals("", FeedbackLinks.appVersionName(context))
    }

    private fun setVersionName(name: String?) {
        val pm = shadowOf(context.packageManager)
        val info = pm.getInternalMutablePackageInfo(context.packageName)
        info.versionName = name
    }
}
