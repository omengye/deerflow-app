package com.deerflow.app.data.waku

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal object ReplayFixture {
    const val session = "10000000-0000-0000-0000-000000000001"
    const val runtime = "20000000-0000-0000-0000-000000000001"
    const val epoch = "30000000-0000-0000-0000-000000000001"
    fun event(seq: Long, kind: String = "textDelta", payload: JsonElement = JsonPrimitive("[$seq]"), e: String = epoch) = buildJsonObject {
        put("type", "event"); put("sessionId", session); put("runtimeId", runtime); put("epoch", e)
        put("sequence", seq); put("replayable", true)
        put("event", buildJsonObject { put("kind", kind); put("payload", payload) })
    }
    fun page(command: JsonObject, total: Long, events: (Long) -> JsonObject = { event(it) }): JsonObject {
        val after = command.long("afterSequence")
        val through = (command["throughSequence"] as? JsonPrimitive)?.longOrNull ?: total
        val next = minOf(through, after + command.long("limit"))
        return wakuCommand("replayEvents") {
            put("epoch", epoch); put("throughSequence", through); put("nextSequence", next); put("hasMore", next < through)
            put("events", JsonArray((after + 1..next).map(events)))
        }
    }
    fun snapshot(at: Long = 0): WakuSnapshot {
        var s = WakuProjection.bindRuntime(WakuProjection.fromSession(WakuProjection.createSession(session, session)), runtime)
        s = WakuProjection.reduce(s, event(1, "promptSubmitted", buildJsonObject {
            put("message", "Synthetic recovery test"); put("messageId", "message-1"); put("turnId", "turn-1")
        }))
        s = WakuProjection.reduce(s, event(2, "turnStarted", JsonNull))
        return s.atReplayPosition(WakuReplayPosition(runtime, epoch, at))
    }
    fun apply(s: WakuSnapshot, p: WakuReplayPage): WakuSnapshot = p.events.fold(s) { previous, event ->
        WakuProjection.reduce(previous, event)
    }.atReplayPosition(p.position)
}

@OptIn(ExperimentalCoroutinesApi::class)
class WakuReplayRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val f = ReplayFixture

    @Test fun cached1802EventsOnlyRequestsTheMissingSuffix() = runTest {
        val requests = mutableListOf<Long>()
        val pages = mutableListOf<WakuReplayPage>()
        WakuReplayRunner().run(f.session, f.runtime, f.snapshot(1802).replayPosition(), request = {
            requests += it.long("afterSequence"); f.page(it, 1802)
        }, commit = { pages += it })
        assertEquals(listOf(1802L), requests)
        assertTrue(pages.single().events.isEmpty())
        assertEquals(100, pages.single().percent)
    }

    @Test fun hundredThousandEventHistoryWithSlowPagesOnlyTransfersOneHundredMissingEvents() = runTest {
        val requested = mutableListOf<Long>()
        var count = 0
        var position = f.snapshot(99_900).replayPosition()
        WakuReplayRunner().run(f.session, f.runtime, position, request = {
            requested += it.long("afterSequence")
            delay(20_000) // Below the per-page deadline; total recovery is allowed to exceed it.
            f.page(it, 100_000)
        }, commit = { count += it.events.size; position = it.position })
        assertEquals(listOf(99_900L, 99_964L), requested)
        assertEquals(100, count); assertEquals(100_000L, position!!.sequence)
        assertEquals(40_000L, currentTime)
    }

    @Test fun twentyFourConsecutiveHandoversResumeDurablePagesWithoutDuplicateText() = runTest {
        val directory = temporary.newFolder()
        fun store() = WakuSessionStore(directory, "wss://test.invalid/v1", "synthetic-token")
        store().save(f.snapshot(2))
        val attempts = mutableListOf<Long>()
        repeat(25) { attempt ->
            var snapshot = store().load(f.session)!! // Reload the disk checkpoint like a new process/link.
            attempts += snapshot.replayPosition()!!.sequence
            var requests = 0
            try {
                WakuReplayRunner(pageSize = 16).run(f.session, f.runtime, snapshot.replayPosition(), request = {
                    delay(1200)
                    if (attempt < 24 && ++requests == 3) throw IOException("Wi-Fi/cellular handover")
                    f.page(it, 802)
                }, commit = {
                    snapshot = f.apply(snapshot, it)
                    store().save(snapshot)
                })
            } catch (_: IOException) { assertTrue(attempt < 24) }
        }
        val final = store().load(f.session)!!
        assertEquals((0..24).map { 2L + it * 32L }, attempts)
        assertEquals(802L, final.replayPosition()!!.sequence)
        val assistant = final.session.array("messages").filterIsInstance<JsonObject>().single { it.string("role") == "assistant" }
        assertEquals((3L..802L).joinToString("") { "[$it]" }, assistant.string("content"))
    }

    @Test fun aLatePageFromCancelledNetworkCannotCommitOrReplaceNewProgress() = runTest {
        var commits = 0
        val work = launch {
            WakuReplayRunner().run(f.session, f.runtime, f.snapshot(128).replayPosition(), request = {
                withContext(NonCancellable) { delay(1000) } // Simulate an already queued old socket callback.
                f.page(it, 192)
            }, commit = { commits++ })
        }
        runCurrent(); work.cancel(); advanceUntilIdle(); work.join()
        assertEquals(0, commits)
    }

    @Test fun stalledPageTimesOutWithoutMovingTheSavedCursor() = runTest {
        var position = f.snapshot(128).replayPosition()
        val error = runCatching {
            WakuReplayRunner(pageTimeoutMs = 1500).run(f.session, f.runtime, position,
                request = { delay(30_000); f.page(it, 192) }, commit = { position = it.position })
        }.exceptionOrNull()
        assertTrue(error is IOException); assertFalse(error is CancellationException)
        assertEquals(128L, position!!.sequence); assertEquals(1500L, currentTime)
    }

    @Test fun parentCancellationIsNotMisreportedAsNetworkTimeout() = runTest {
        val error = runCatching {
            withTimeout(100) {
                WakuReplayRunner().run(f.session, f.runtime, null,
                    request = { delay(30_000); f.page(it, 64) }, commit = { fail("cancelled page committed") })
            }
        }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
    }

    @Test fun epochReplacementRequiresHydrationBeforeAnyPageCanBeCommitted() = runTest {
        val newEpoch = "30000000-0000-0000-0000-000000000002"
        val error = runCatching {
            WakuReplayRunner().run(f.session, f.runtime, f.snapshot(1802).replayPosition(), request = {
                f.page(wakuCommand("replayEvents") { put("afterSequence", 0); put("limit", 64) }, 2).updated("epoch" to JsonPrimitive(newEpoch))
            }, commit = { fail("old cursor must never cross daemon epochs") })
        }.exceptionOrNull()
        assertEquals(newEpoch, (error as ReplayEpochChanged).actualEpoch)
    }

    @Test fun noProgressPageFailsInsteadOfLoopingForever() = runTest {
        val error = runCatching {
            WakuReplayRunner().run(f.session, f.runtime, f.snapshot(64).replayPosition(), request = {
                wakuCommand("replayEvents") {
                    put("epoch", f.epoch); put("throughSequence", 100); put("nextSequence", 64)
                    put("hasMore", true); put("events", JsonArray(emptyList()))
                }
            }, commit = { fail("invalid page") })
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun controlRecoveryResumesSeparatelyAndDoesNotResurrectOldApprovals() = runTest {
        val store = WakuSessionStore(temporary.newFolder(), "wss://test.invalid/v1", "synthetic-token")
        var snapshot = f.snapshot(32).copy(interactionsVerified = false)
        val originalMessages = snapshot.session.array("messages")
        store.save(snapshot)
        val seen = mutableListOf<Long>()
        val events: (Long) -> JsonObject = { n -> when (n) {
            5L -> f.event(n, "permission", buildJsonObject { put("requestId", "old") })
            8L -> f.event(n, "turnFinished", buildJsonObject { put("success", true) })
            22L -> f.event(n, "permission", buildJsonObject { put("requestId", "current") })
            24L -> f.event(n, "userInputRequested", buildJsonObject { put("requestId", "question") })
            else -> f.event(n)
        } }
        repeat(2) { attempt ->
            snapshot = store.load(f.session)!!
            var checkpoint = snapshot.interactionReplay ?: WakuControlCheckpoint(WakuReplayPosition(f.runtime, f.epoch, 0))
            try {
                WakuReplayRunner(16).run(f.session, f.runtime, checkpoint.position, 32, request = {
                    if (attempt == 0 && it.long("afterSequence") == 16L) throw IOException("handover")
                    seen += it.long("afterSequence"); f.page(it, 32, events)
                }, commit = { page ->
                    checkpoint = checkpoint.advance(page)
                    snapshot = snapshot.copy(interactionReplay = checkpoint)
                    store.save(snapshot)
                })
            } catch (_: IOException) { assertEquals(0, attempt) }
        }
        assertEquals(listOf(0L, 16L), seen)
        assertEquals(listOf("current"), snapshot.interactionReplay!!.permissions.map { it.string("requestId") })
        assertEquals(listOf("question"), snapshot.interactionReplay!!.questions.map { it.string("requestId") })
        assertEquals(32L, snapshot.replayPosition()!!.sequence)
        assertEquals(originalMessages, snapshot.session.array("messages"))
    }

    @Test fun runtimeChangeDropsSavedControlsAndNativeWaitingSnapshotsRequireVerification() {
        val s = f.snapshot(100).copy(interactionsVerified = true,
            interactionReplay = WakuControlCheckpoint(f.snapshot(100).replayPosition()),
            artifactReplay = WakuControlCheckpoint(f.snapshot(100).replayPosition()), taskReady = f.event(2))
        val replaced = WakuProjection.bindRuntime(s, "20000000-0000-0000-0000-000000000002")
        assertFalse(replaced.interactionsVerified); assertNull(replaced.interactionReplay)
        assertNull(replaced.artifactReplay); assertNull(replaced.taskReady)
        assertFalse(WakuProjection.fromSession(s.session.updated("status" to JsonPrimitive("waiting"))).interactionsVerified)
    }
}
