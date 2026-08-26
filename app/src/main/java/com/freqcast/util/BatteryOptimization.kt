package com.freqcast.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Background playback/alarms rely on the foreground-service + `AlarmManager.setAlarmClock`
 * exemptions Android grants by default (see `docs/architecture/playback.md`), but several OEM
 * skins (MIUI, EMUI, ColorOS/FuntouchOS, One UI's "sleeping apps", ...) layer their own
 * battery-saver on top that can still suspend the service in the background regardless. Standing
 * up the stock "ignore battery optimizations" dialog is the one cross-vendor lever the platform
 * actually exposes for that — everything else is per-brand autostart settings with no common API.
 */
object BatteryOptimization {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a normal (install-time-granted) manifest
     * permission, not a runtime one — this intent shows the system's confirmation dialog directly,
     * no separate permission-request round trip needed.
     */
    fun requestExemptionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
}
