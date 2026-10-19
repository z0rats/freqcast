package com.freqcast.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class InstallSourceTest {
    @Test
    fun `known F-Droid clients count as store installers`() {
        assertTrue(InstallSource.isStoreInstaller("org.fdroid.fdroid"))
        assertTrue(InstallSource.isStoreInstaller("com.machiav3lli.fdroid"))
        assertTrue(InstallSource.isStoreInstaller("com.looker.droidify"))
    }

    @Test
    fun `RuStore, Google Play and OEM stores count as store installers`() {
        assertTrue(InstallSource.isStoreInstaller("ru.vk.store"))
        assertTrue(InstallSource.isStoreInstaller("com.android.vending"))
        assertTrue(InstallSource.isStoreInstaller("com.huawei.appmarket"))
    }

    @Test
    fun `sideloaded or unknown installers do not`() {
        assertFalse(InstallSource.isStoreInstaller(null))
        assertFalse(InstallSource.isStoreInstaller("com.google.android.packageinstaller"))
        assertFalse(InstallSource.isStoreInstaller("com.android.chrome"))
    }
}

/** [InstallSource.isManagedByStore] reading the real installer package on each side of the API 30 split. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class InstallSourceInstallerLookupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `pre-R reads the deprecated installer package name`() {
        @Suppress("DEPRECATION")
        context.packageManager.setInstallerPackageName(context.packageName, "org.fdroid.fdroid")
        assertTrue(InstallSource.isManagedByStore(context))

        @Suppress("DEPRECATION")
        context.packageManager.setInstallerPackageName(context.packageName, "com.android.chrome")
        assertFalse(InstallSource.isManagedByStore(context))
    }

    @Test
    @Config(sdk = [31])
    fun `R and later read the install source info`() {
        shadowOf(context.packageManager).setInstallSourceInfo(context.packageName, "ru.vk.store", "ru.vk.store")
        assertTrue(InstallSource.isManagedByStore(context))
    }

    @Test
    @Config(sdk = [31])
    fun `no recorded install source is treated as sideloaded`() {
        assertFalse(InstallSource.isManagedByStore(context))
    }
}
