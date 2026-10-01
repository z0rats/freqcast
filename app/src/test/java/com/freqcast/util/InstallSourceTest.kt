package com.freqcast.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
