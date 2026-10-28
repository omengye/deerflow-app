package com.deerflow.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-lifetime reachability hints. Socket health remains authoritative, including on a private LAN. */
class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutableState = MutableStateFlow(NetworkStatus())
    private val tracker = DefaultNetworkTracker<Network>()
    val state = mutableState.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update { available(network) }
        override fun onLost(network: Network) = update { lost(network) }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = update { this.blocked(network, blocked) }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update {
            this.capabilities(network, NetworkHints(
                usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            ))
        }
    }

    init {
        // Registration delivers the current default network and its capabilities in order.
        manager.registerDefaultNetworkCallback(callback)
    }

    @Synchronized private fun update(change: DefaultNetworkTracker<Network>.() -> NetworkStatus) {
        mutableState.value = tracker.change()
    }
}
