package com.freqcast.util

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BatteryOptimizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `not ignoring battery optimizations by default`() {
        assertFalse(BatteryOptimization.isIgnoringBatteryOptimizations(context))
    }

    @Test
    fun `reflects PowerManager once the app is exempted`() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, true)

        assertTrue(BatteryOptimization.isIgnoringBatteryOptimizations(context))
    }

    @Test
    fun `exemption intent targets the system dialog for this app's package`() {
        val intent = BatteryOptimization.requestExemptionIntent(context)

        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }
}
