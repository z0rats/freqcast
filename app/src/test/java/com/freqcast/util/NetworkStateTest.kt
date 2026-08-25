package com.freqcast.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NetworkStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `false by default, before any network capabilities are set`() {
        assertFalse(isVpnActive(context))
    }

    @Test
    fun `false when the active network is not routed through a VPN`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(cm).setNetworkCapabilities(network, capabilities)

        assertFalse(isVpnActive(context))
    }

    @Test
    fun `true when the active network is routed through a VPN`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        shadowOf(cm).setNetworkCapabilities(network, capabilities)

        assertTrue(isVpnActive(context))
    }
}
