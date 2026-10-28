package com.deerflow.app.data.waku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle signals wake retry waits without replacing an authenticated, responsive socket. */
class WakuConnectionSupervisor(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val recentTrafficMillis: Long = 15_000,
    private val probeTimeoutMillis: Long = 2_000,
    private val stableConnectionMillis: Long = 15_000,
    private val retryDelay: (Int) -> Long = { WakuRecovery.retryDelayMillis(it) },
) {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val probeRequested = AtomicBoolean(false)
    private val wakeRevision = AtomicLong(0)
    private var handledRevision = 0L
    private var failures = 0

    init {
        require(recentTrafficMillis > 0 && probeTimeoutMillis > 0 && stableConnectionMillis > 0)
    }

    fun foreground() { probeRequested.set(true); wakeRevision.incrementAndGet(); wakeups.trySend(Unit) }
    fun networkRecovered() { wakeRevision.incrementAndGet(); wakeups.trySend(Unit) }
    fun resetFailures() { failures = 0 }

    /** The connection loop is the sole retry waiter; its foreground monitor is stopped first. */
    suspend fun awaitRetry(connectedForMillis: Long?, immediate: Boolean = false) {
        if (connectedForMillis != null && connectedForMillis >= stableConnectionMillis) failures = 0
        // A foreground replacement must still account for the preceding healthy connection.
        // It is an explicit wake, not another failed attempt to add to the backoff history.
        if (immediate) return
        val waitMillis = retryDelay(failures)
        failures = (failures + 1).coerceAtMost(30)
        val requested = wakeRevision.get()
        if (requested > handledRevision) {
            handledRevision = requested
            wakeups.tryReceive()
            probeRequested.set(false)
            return
        }
        withTimeoutOrNull(waitMillis) {
            wakeups.receive()
            handledRevision = wakeRevision.get()
            probeRequested.set(false)
        }
    }

    suspend fun monitorForeground(
        health: () -> WakuConnectionHealth,
        probe: suspend (timeoutMillis: Long) -> Unit,
        reconnect: () -> Unit,
    ) {
        for (wake in wakeups) {
            val requested = wakeRevision.get()
            val needsProbe = probeRequested.getAndSet(false)
            val before = health()
            // A signal during disconnect cleanup must survive until the retry waiter starts.
            // An existing connection or handshake already handles the request to get online.
            if (before.connected || before.connecting) handledRevision = requested
            if (!needsProbe) continue
            // In-progress handshakes have their own deadline. A new visibility event cannot
            // repeatedly cancel a slow but valid handshake, or a live streaming connection.
            if (!before.connected || before.lastReceivedAtMillis?.let {
                    clockMillis() - it < recentTrafficMillis
                } == true) continue
            try {
                probe(probeTimeoutMillis)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val after = health()
                // The probe response can queue behind replay traffic. Any new valid inbound
                // message proves this socket is alive; do not discard it just for that delay.
                if (after.connected && after.lastReceivedAtMillis == before.lastReceivedAtMillis) reconnect()
            }
        }
    }
}

data class WakuConnectionHealth(
    val connected: Boolean,
    val lastReceivedAtMillis: Long?,
    val connectedForMillis: Long?,
    val connecting: Boolean = false,
)

/** Contains timing/classification only, never URLs, tokens, request bodies or server error text. */
data class WakuConnectionDiagnostic(
    val stage: String,
    val elapsedMillis: Long,
    val failure: WakuConnectionFailure? = null,
    val transport: String? = null,
)
