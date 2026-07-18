package com.deerflow.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** App-lifetime network monitor used to avoid connection attempts while offline. */
class NetworkMonitor(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val _isAvailable = MutableStateFlow(hasUsableNetwork())
    private val becameAvailable = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val isAvailable = _isAvailable.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    suspend fun awaitAvailable(timeoutMillis: Long): Boolean {
        if (_isAvailable.value) return true
        if (timeoutMillis <= 0) return false
        return withTimeoutOrNull(timeoutMillis) {
            isAvailable.filter { it }.first()
            true
        } ?: false
    }

    /** Wait for normal backoff, but return early when an unavailable network comes back. */
    suspend fun awaitRetry(delayMillis: Long, timeoutMillis: Long): Boolean {
        val boundedDelay = minOf(delayMillis, timeoutMillis)
        if (boundedDelay <= 0) return false
        if (!_isAvailable.value) return awaitAvailable(timeoutMillis)
        withTimeoutOrNull(boundedDelay) {
            becameAvailable.first()
        }
        return true
    }

    private fun refresh() {
        val wasAvailable = _isAvailable.value
        val available = hasUsableNetwork()
        _isAvailable.value = available
        if (available && !wasAvailable) becameAvailable.tryEmit(Unit)
    }

    private fun hasUsableNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        // Local DeerFlow endpoints are commonly reached over Wi-Fi without
        // Android's internet validation, so do not require NET_CAPABILITY_VALIDATED.
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
