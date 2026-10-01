package com.freqcast.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Whether an app store or update manager (F-Droid, RuStore, Google Play, OEM stores, ...) installed this build and therefore
 * owns update notifications. Decided at runtime from the installer package, not via a build
 * flavor/`BuildConfig` flag: F-Droid's reproducible-build check compares its own build byte-for-byte
 * against the GitHub Release APK, so the binary must be identical for both channels.
 */
object InstallSource {
    internal val STORE_INSTALLERS =
        setOf(
            // F-Droid family
            "org.fdroid.fdroid",
            "org.fdroid.basic",
            "com.machiav3lli.fdroid", // Neo Store
            "com.looker.droidify", // Droid-ify
            // Russia
            "ru.vk.store", // RuStore
            // Google Play and clients
            "com.android.vending",
            "com.aurora.store",
            // OEM stores
            "com.sec.android.app.samsungapps", // Samsung Galaxy Store
            "com.huawei.appmarket", // Huawei AppGallery
            "com.xiaomi.mipicks", // Xiaomi GetApps
            "com.xiaomi.market",
            "com.heytap.market", // OPPO/OnePlus/Realme
            "com.bbk.appstore", // vivo
            // Other stores / update managers
            "com.amazon.venezia", // Amazon Appstore
            "app.accrescent.client",
            "com.uptodown",
            "dev.imranr.obtainium", // Obtainium tracks GitHub releases itself
            "dev.imranr.obtainium.fdroid",
        )

    fun isManagedByStore(context: Context): Boolean = isStoreInstaller(installerPackage(context))

    internal fun isStoreInstaller(installer: String?): Boolean = installer in STORE_INSTALLERS

    private fun installerPackage(context: Context): String? =
        try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
}
