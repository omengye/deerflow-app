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
    private var lastNetwork: Network? = null
    val state = mutableState.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
    }

    init {
        refresh()
        manager.registerDefaultNetworkCallback(callback)
    }

    @Synchronized private fun refresh() {
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val usable = capabilities != null && (
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
        val changed = lastNetwork != network
        lastNetwork = network
        mutableState.value = NetworkStatus(usable, mutableState.value.revision + if (changed) 1 else 0)
    }
}
