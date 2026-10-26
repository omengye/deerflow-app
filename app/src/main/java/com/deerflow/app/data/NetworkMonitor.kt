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
    private val mutableAvailable = MutableStateFlow(false)
    private val mutableRevision = MutableStateFlow(0L)
    private var lastNetwork: Network? = null
    val isAvailable = mutableAvailable.asStateFlow()
    /** Wi-Fi to cellular handover must reconnect even when both networks report available. */
    val changes = mutableRevision.asStateFlow()

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
        mutableAvailable.value = usable
        if (changed) mutableRevision.value += 1
    }
}