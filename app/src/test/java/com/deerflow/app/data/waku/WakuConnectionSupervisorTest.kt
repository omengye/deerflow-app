package com.deerflow.app.data.waku

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakuConnectionSupervisorTest {
    @Test fun foregroundDuringDisconnectCleanupSurvivesUntilBackoffStarts() = runTest {
        val supervisor = WakuConnectionSupervisor(retryDelay = { 30_000 })
        val monitor = backgroundScope.launch {
            supervisor.monitorForeground({ WakuConnectionHealth(false, 0, 1_000) }, { fail("Disconnected probe") }, { fail("Disconnected replacement") })
        }
        runCurrent(); supervisor.foreground(); runCurrent()
        monitor.cancelAndJoin()
        val began = currentTime
        supervisor.awaitRetry(1_000)
        assertEquals(began, currentTime)
        // One wake requests one immediate retry, not an unbounded busy reconnect loop.
        supervisor.awaitRetry(null)
        assertEquals(began + 30_000, currentTime)
    }

    @Test fun foregroundInterruptsThirtySecondBackoffWithoutAConnectedSocket() = runTest {
        val supervisor = WakuConnectionSupervisor(retryDelay = { 30_000 })
        var retriedAt: Long? = null
        val waiting = launch { supervisor.awaitRetry(null); retriedAt = currentTime }
        runCurrent(); advanceTimeBy(1_000)
        supervisor.foreground(); runCurrent()
        assertEquals(1_000L, retriedAt)
        waiting.join()
    }

    @Test fun recoveredNetworkWakesBackoffButDoesNotProbeAnExistingConnection() = runTest {
        val supervisor = WakuConnectionSupervisor(clockMillis = { currentTime }, retryDelay = { 30_000 })
        var probes = 0
        val monitor = backgroundScope.launch {
            supervisor.monitorForeground({ WakuConnectionHealth(true, 0, currentTime) }, { probes++ }, { fail("Unexpected replacement") })
        }
        advanceTimeBy(30_000); supervisor.networkRecovered(); runCurrent()
        assertEquals(0, probes)
        monitor.cancelAndJoin()
        var retried = false
        val waiting = launch { supervisor.awaitRetry(null); retried = true }
        runCurrent(); supervisor.networkRecovered(); runCurrent()
        assertTrue(retried)
        waiting.join()
    }

    @Test fun recentTrafficAndHandshakingArePreservedAcrossRepeatedForegroundEvents() = runTest {
        val supervisor = WakuConnectionSupervisor(clockMillis = { currentTime })
        var connected = false
        var receivedAt: Long? = null
        val monitor = backgroundScope.launch {
            supervisor.monitorForeground({ WakuConnectionHealth(connected, receivedAt, null, connecting = !connected) }, { fail("Unexpected probe") }, { fail("Unexpected replacement") })
        }
        repeat(10) { supervisor.foreground(); runCurrent(); advanceTimeBy(1_000) }
        connected = true; receivedAt = currentTime
        repeat(10) { supervisor.foreground(); runCurrent(); advanceTimeBy(1_000) }
        monitor.cancelAndJoin()
    }

    @Test fun staleSilentSocketIsReplacedAfterBoundedProbeAndSignalsAreCoalesced() = runTest {
        val supervisor = WakuConnectionSupervisor(clockMillis = { currentTime })
        var connected = true
        var probes = 0
        var reconnectAt: Long? = null
        val monitor = backgroundScope.launch {
            supervisor.monitorForeground(
                { WakuConnectionHealth(connected, 0, currentTime) },
                { timeout -> probes++; delay(timeout); throw IOException("blackholed socket") },
                { connected = false; reconnectAt = currentTime },
            )
        }
        advanceTimeBy(20_000)
        repeat(20) { supervisor.foreground() }
        // A validation callback cannot overwrite the pending foreground health check.
        supervisor.networkRecovered(); runCurrent()
        advanceTimeBy(1_999); runCurrent(); assertNull(reconnectAt)
        advanceTimeBy(1); runCurrent()
        assertEquals(22_000L, reconnectAt)
        assertEquals(1, probes)
        monitor.cancelAndJoin()
    }

    @Test fun replayTrafficDuringSlowProbeProvesConnectionAlive() = runTest {
        val supervisor = WakuConnectionSupervisor(clockMillis = { currentTime })
        var receivedAt = 0L
        val monitor = backgroundScope.launch {
            supervisor.monitorForeground(
                { WakuConnectionHealth(true, receivedAt, currentTime) },
                { timeout -> delay(timeout / 2); receivedAt = currentTime; delay(timeout / 2); throw IOException("queued response") },
                { fail("Inbound replay traffic must preserve this connection") },
            )
        }
        advanceTimeBy(20_000); supervisor.foreground(); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(21_000L, receivedAt)
        monitor.cancelAndJoin()
    }

    @Test fun responsiveIdleSocketIsNotReplaced() = runTest {
        val supervisor = WakuConnectionSupervisor(clockMillis = { currentTime })
        var probes = 0
        val monitor = backgroundScope.launch {
            supervisor.monitorForeground({ WakuConnectionHealth(true, 0, currentTime) }, { delay(100); probes++ }, { fail("Healthy socket replaced") })
        }
        advanceTimeBy(30_000); supervisor.foreground(); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(1, probes)
        monitor.cancelAndJoin()
    }

    @Test fun cancelledOldProbeCannotReplaceTheNewConnection() = runTest {
        val supervisor = WakuConnectionSupervisor(clockMillis = { currentTime })
        val monitor = backgroundScope.launch {
            supervisor.monitorForeground({ WakuConnectionHealth(true, 0, currentTime) }, { delay(2_000); throw IOException() }, { fail("Old probe escaped cancellation") })
        }
        advanceTimeBy(20_000); supervisor.foreground(); runCurrent()
        advanceTimeBy(500); monitor.cancelAndJoin()
        advanceTimeBy(10_000); runCurrent()
    }

    @Test fun stableAuthenticatedConnectionResetsFailuresButFlappingKeepsBackoff() = runTest {
        val attempts = mutableListOf<Int>()
        val supervisor = WakuConnectionSupervisor(retryDelay = { attempts += it; 100 })
        supervisor.awaitRetry(null)
        supervisor.awaitRetry(10)
        supervisor.awaitRetry(14_999)
        supervisor.awaitRetry(15_000)
        supervisor.awaitRetry(null)
        assertEquals(listOf(0, 1, 2, 0, 1), attempts)
    }

    @Test fun stableConnectionThenImmediateForegroundReplacementClearsPreviousFailureHistory() = runTest {
        val attempts = mutableListOf<Int>()
        val supervisor = WakuConnectionSupervisor(retryDelay = { attempts += it; 100 })
        repeat(6) { supervisor.awaitRetry(null) }
        val beforeReplacement = currentTime
        supervisor.awaitRetry(20_000, immediate = true)
        assertEquals(beforeReplacement, currentTime)
        assertEquals((0..5).toList(), attempts)
        // The replacement handshake fails: its delay must start at the initial retry interval.
        supervisor.awaitRetry(null)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 0), attempts)
    }
}
