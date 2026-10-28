package com.deerflow.app.data

/** Only properties relevant to reachability; bandwidth and metering updates must not reconnect. */
internal data class NetworkHints(
    val usable: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
)

/**
 * Orders default-network callbacks without querying ConnectivityManager from inside a callback.
 * Android sends capabilities after onAvailable. Commit a handover once both are known, and
 * ignore callbacks for the old network, which can arrive after its replacement is announced.
 */
internal class DefaultNetworkTracker<K : Any> {
    private var announcedNetwork: K? = null
    private var committedNetwork: K? = null
    private var hints: NetworkHints? = null
    private var blocked = false
    var status = NetworkStatus()
        private set

    fun available(network: K): NetworkStatus {
        if (announcedNetwork != network) {
            announcedNetwork = network
            hints = null
            blocked = false
        }
        return status
    }

    fun capabilities(network: K, next: NetworkHints): NetworkStatus {
        if (network != announcedNetwork) return status
        val previous = hints
        hints = next
        return publish(network, previous)
    }

    fun blocked(network: K, value: Boolean): NetworkStatus {
        if (network != announcedNetwork || blocked == value) return status
        blocked = value
        return publish(network, hints)
    }

    fun lost(network: K): NetworkStatus {
        if (network != announcedNetwork) return status
        announcedNetwork = null
        committedNetwork = null
        hints = null
        blocked = false
        status = NetworkStatus(revision = status.revision + 1, retryRevision = status.retryRevision)
        return status
    }

    private fun publish(network: K, previous: NetworkHints?): NetworkStatus {
        val next = hints ?: return status
        val handover = committedNetwork != network
        val usable = next.usable && !blocked
        val replaced = handover || status.available != usable
        val improved = !handover && usable && previous != null && (
            (!previous.validated && next.validated) ||
                (previous.captivePortal && !next.captivePortal)
            )
        committedNetwork = network
        status = NetworkStatus(
            available = usable,
            revision = status.revision + if (replaced) 1 else 0,
            validated = next.validated,
            captivePortal = next.captivePortal,
            retryRevision = status.retryRevision + if (improved) 1 else 0,
        )
        return status
    }
}
