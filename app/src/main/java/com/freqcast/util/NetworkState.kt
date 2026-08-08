package com.freqcast.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the currently active network is routed through a VPN. Used only to sharpen an error
 * message: some sites reset the TLS handshake specifically for connections that look VPN-routed
 * (observed with at least one RU-hosted station's site), so knowing a VPN is active turns a
 * generic "could not connect" into an actionable hint - see
 * [com.freqcast.ui.AddStationViewModel]'s TLS-failure handling.
 */
fun isVpnActive(context: Context): Boolean {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}
