package dev.nittala.voicegateway.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.nittala.voicegateway.service.NetworkMonitor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [NetworkMonitor] on the default network (Tailscale's VPN when it is up): true while it has internet capability,
 * false when it is lost. Starts with the current state.
 */
class ConnectivityNetworkMonitor(private val connectivity: ConnectivityManager) : NetworkMonitor {
    override val online: Flow<Boolean> = callbackFlow {
        fun hasInternet(caps: NetworkCapabilities?) = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        trySend(hasInternet(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(hasInternet(caps))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
