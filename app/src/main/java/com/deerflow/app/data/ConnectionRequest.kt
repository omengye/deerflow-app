package com.deerflow.app.data

import com.deerflow.app.data.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkStatus(
    val available: Boolean = false,
    val revision: Long = 0,
    val validated: Boolean? = null,
    val captivePortal: Boolean? = null,
    /** Improved reachability wakes retries without replacing a healthy private-network socket. */
    val retryRevision: Long = 0,
)
data class ConnectionRequest(val config: AppSettings, val keepConnected: Boolean, val network: NetworkStatus, val refresh: Long)

/** A handover is one atomic change; unrelated preference writes do not replace the socket. */
fun connectionRequests(settings: Flow<AppSettings>, retained: Flow<Boolean>, network: Flow<NetworkStatus>, refresh: Flow<Long>): Flow<ConnectionRequest> =
    combine(
        settings.distinctUntilChanged(),
        retained,
        network.distinctUntilChanged { before, after ->
            before.available == after.available && before.revision == after.revision
        },
        refresh,
        ::ConnectionRequest,
    ).distinctUntilChanged()
