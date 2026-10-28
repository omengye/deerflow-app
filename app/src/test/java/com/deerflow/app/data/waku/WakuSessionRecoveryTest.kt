package com.deerflow.app.data.waku

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakuSessionRecoveryTest {
    @Test fun selectingDuringReconnectWaitsForTheActualRecoveryResult() = runTest {
        val gate = WakuSessionRecovery(); val release = CompletableDeferred<Unit>()
        supervisorScope {
            val first = async { runCatching { gate.run("session") { release.await(); throw IOException("page lost") } } }
            runCurrent()
            val selected = async { runCatching { gate.run("session") { fail("must join existing recovery") } } }
            runCurrent(); assertFalse(selected.isCompleted)
            release.complete(Unit)
            assertTrue(first.await().exceptionOrNull() is IOException)
            assertTrue(selected.await().exceptionOrNull() is IOException)
            var retried = false
            gate.run("session") { retried = true }
            assertTrue(retried)
        }
    }

    @Test fun slowOtherSessionDoesNotBlockSelectedSessionAndCancellationReleasesGate() = runTest {
        val gate = WakuSessionRecovery()
        val slow = launch { gate.run("slow") { awaitCancellation() } }
        runCurrent()
        var ready = false
        gate.run("selected") { ready = true }
        assertTrue(ready)
        slow.cancelAndJoin()
        gate.run("slow") { ready = false }
        assertFalse(ready)
    }
}
