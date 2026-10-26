package com.deerflow.app.data.waku

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WakuToolApprovalTest {
    private val session = "10000000-0000-0000-0000-000000000001"
    private val runtime = "20000000-0000-0000-0000-000000000001"
    private val otherSession = "10000000-0000-0000-0000-000000000002"
    private val otherRuntime = "20000000-0000-0000-0000-000000000002"
    private val epoch = "30000000-0000-0000-0000-000000000001"
    private val oldEpoch = "30000000-0000-0000-0000-000000000002"

    private fun event(mode: String, sequence: Long = 1, s: String = session,
        r: String = runtime, e: String = epoch, live: Boolean = true) = buildJsonObject {
        if (live) put("type", "event")
        put("sessionId", s); put("runtimeId", r); put("epoch", e); put("sequence", sequence)
        put("event", buildJsonObject { put("kind", "toolApprovalChanged"); put("payload", mode) })
    }
    private fun confirmed() = wakuCommand("toolApproval") { put("mode", "allow_always") }

    @Test fun restoredAskWaitsForExplicitServerAcknowledgementAndSendsOnce() = runBlocking {
        val response = CompletableDeferred<JsonObject>()
        val sent = mutableListOf<JsonObject>()
        val helper = WakuToolApproval { command, s, r ->
            assertEquals(session, s); assertEquals(runtime, r)
            sent += command
            response.await()
        }
        helper.onWireEvent(event("ask", live = false))
        val first = async { helper.ensureFullAccess(session, runtime) }
        val second = async { helper.ensureFullAccess(session, runtime) }
        repeat(3) { yield() }
        assertEquals(listOf(wakuCommand("setToolApproval") { put("mode", "allow_always") }), sent)
        assertFalse(first.isCompleted); assertFalse(second.isCompleted)
        response.complete(confirmed())
        first.await(); second.await()
        helper.ensureFullAccess(session, runtime)
        assertEquals(1, sent.size)
    }

    @Test fun fullAccessAndDisabledApprovalsNeedNoMutation() = runBlocking {
        for (mode in listOf("allow_always", "off")) {
            val helper = WakuToolApproval { _, _, _ -> error("No permission mutation expected") }
            helper.onWireEvent(event(mode))
            helper.ensureFullAccess(session, runtime)
        }
        assertEquals("fullAccess", WakuToolApproval.RUNTIME_MODE)
    }

    @Test fun waitsForAuthoritativeInitializationBeforeChangingPolicy() = runBlocking {
        var count = 0
        val helper = WakuToolApproval { _, _, _ -> count++; confirmed() }
        val waiting = async { helper.ensureFullAccess(session, runtime) }
        repeat(3) { yield() }
        assertFalse(waiting.isCompleted); assertEquals(0, count)
        helper.onWireEvent(event("ask"))
        waiting.await()
        assertEquals(1, count)
    }

    @Test fun explicitFullAccessCanReplaceOtherAdvertisedPolicies() = runBlocking {
        for (mode in listOf("global", "reject_always", "read_only")) {
            var count = 0
            val helper = WakuToolApproval { _, _, _ -> count++; confirmed() }
            helper.onWireEvent(event(mode))
            helper.ensureFullAccess(session, runtime)
            assertEquals(1, count)
        }
    }

    @Test fun timeoutIsVisibleFailureAndDoesNotAuthorizeOrRetry() = runBlocking {
        var count = 0
        val response = CompletableDeferred<JsonObject>()
        val helper = WakuToolApproval(40) { _, _, _ -> count++; response.await() }
        helper.onWireEvent(event("ask"))
        val error = runCatching { helper.ensureFullAccess(session, runtime) }.exceptionOrNull()
        assertTrue(error is IOException); assertFalse(error is CancellationException)
        assertEquals(1, count)
        response.complete(confirmed())
        assertTrue(runCatching { helper.ensureFullAccess(session, runtime) }.exceptionOrNull() is IOException)
        assertEquals(1, count)
        helper.onWireEvent(event("allow_always", 2))
        helper.ensureFullAccess(session, runtime)
        assertEquals(1, count)
    }

    @Test fun absentAuthorityAndPermissionCardsNeverTriggerApproval() = runBlocking {
        var count = 0
        val helper = WakuToolApproval(40) { _, _, _ -> count++; confirmed() }
        helper.onWireEvent(event("ask").updated("event" to buildJsonObject {
            put("kind", "permission"); put("payload", buildJsonObject { put("requestId", "tool") })
        }))
        assertTrue(runCatching { helper.ensureFullAccess(session, runtime) }.exceptionOrNull() is IOException)
        assertEquals(0, count)
    }

    @Test fun disconnectReleasesInitializationWaitAndFencesLateEvents() = runBlocking {
        var count = 0
        val helper = WakuToolApproval { _, _, _ -> count++; confirmed() }
        val waiting = async { runCatching { helper.ensureFullAccess(session, runtime) } }
        repeat(3) { yield() }
        helper.disconnected()
        assertTrue(waiting.await().exceptionOrNull() is IOException)
        helper.onWireEvent(event("allow_always"))
        assertTrue(runCatching { helper.ensureFullAccess(session, runtime) }.exceptionOrNull() is IOException)
        assertEquals(0, count)
    }

    @Test fun disconnectDuringMutationCannotAcceptLateReply() = runBlocking {
        var count = 0
        val response = CompletableDeferred<JsonObject>()
        val sent = CompletableDeferred<Unit>()
        val helper = WakuToolApproval { _, _, _ -> count++; sent.complete(Unit); response.await() }
        helper.onWireEvent(event("ask"))
        val waiting = async { runCatching { helper.ensureFullAccess(session, runtime) } }
        sent.await()
        helper.disconnected()
        assertTrue(waiting.await().exceptionOrNull() is IOException)
        response.complete(confirmed())
        assertTrue(runCatching { helper.ensureFullAccess(session, runtime) }.exceptionOrNull() is IOException)
        assertEquals(1, count)
    }

    @Test fun mismatchedResponseAndTransportErrorNeverGrantPermissionOrRetry() = runBlocking {
        for (failure in listOf(false, true)) {
            var count = 0
            val helper = WakuToolApproval { _, _, _ ->
                count++
                if (failure) throw IOException("network lost")
                wakuCommand("toolApproval") { put("mode", "ask") }
            }
            helper.onWireEvent(event("ask"))
            assertTrue(runCatching { helper.ensureFullAccess(session, runtime) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { helper.ensureFullAccess(session, runtime) }.exceptionOrNull() is IOException)
            assertEquals(1, count)
        }
    }

    @Test fun duplicateSequencesAndForeignEpochCannotOverwritePolicy() = runBlocking {
        var count = 0
        val helper = WakuToolApproval { _, _, _ -> count++; confirmed() }
        helper.onWireEvent(event("ask", 4))
        helper.onWireEvent(event("allow_always", 4))
        helper.onWireEvent(event("allow_always", 100, e = oldEpoch))
        helper.ensureFullAccess(session, runtime)
        assertEquals(1, count)
        helper.onWireEvent(event("ask", 3))
        helper.onWireEvent(event("ask", 101, e = oldEpoch))
        helper.ensureFullAccess(session, runtime)
        assertEquals(1, count)
    }

    @Test fun policiesAreIsolatedByBothSessionAndRuntime() = runBlocking {
        val sent = mutableListOf<Pair<String, String>>()
        val helper = WakuToolApproval { _, s, r -> sent += s to r; confirmed() }
        helper.onWireEvent(event("allow_always"))
        helper.onWireEvent(event("ask", s = otherSession))
        helper.onWireEvent(event("ask", r = otherRuntime))
        helper.ensureFullAccess(session, runtime)
        helper.ensureFullAccess(otherSession, runtime)
        helper.ensureFullAccess(session, otherRuntime)
        assertEquals(listOf(otherSession to runtime, session to otherRuntime), sent)
    }

    @Test fun newerRestrictiveEventWinsOverInFlightConfirmation() = runBlocking {
        val response = CompletableDeferred<JsonObject>()
        val sent = CompletableDeferred<Unit>()
        val helper = WakuToolApproval { _, _, _ -> sent.complete(Unit); response.await() }
        helper.onWireEvent(event("ask"))
        val waiting = async { runCatching { helper.ensureFullAccess(session, runtime) } }
        sent.await()
        helper.onWireEvent(event("allow_always", 2))
        helper.onWireEvent(event("ask", 3))
        response.complete(confirmed())
        assertTrue(waiting.await().exceptionOrNull() is IOException)
    }

    @Test fun externalCancellationStillPropagatesWithoutGrantingPermission() = runBlocking {
        var count = 0
        val helper = WakuToolApproval { _, _, _ -> count++; confirmed() }
        val waiting = async { helper.ensureFullAccess(session, runtime) }
        repeat(3) { yield() }
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        assertEquals(0, count)
    }
}
