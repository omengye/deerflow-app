package com.deerflow.app.data.waku

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WakuRecoveryTest {
    private fun session(sequence: Long, title: String = "title", epoch: String = "e") =
        WakuProjection.createSession("s", "p").updated(
            "title" to JsonPrimitive(title),
            "runtime_event_cursor" to buildJsonObject {
                put("runtime_id", "r"); put("epoch", epoch); put("sequence", sequence)
            },
        )

    @Test fun `equal cursor uses remote metadata and preserves unresolved submission`() {
        val pending = buildJsonObject { put("id", "command"); put("kind", "prompt"); put("messageId", "m") }
        val local = WakuProjection.fromSession(session(4, "old")).copy(pendingCommands = listOf(pending))
        val chosen = WakuRecovery.chooseSnapshot(local, session(4, "renamed"))
        assertEquals("renamed", chosen.session.string("title"))
        assertEquals(listOf(pending), chosen.pendingCommands)
    }

    @Test fun `newer local stream keeps text but accepts renamed title`() {
        val local = WakuProjection.fromSession(session(5).updated("messages" to JsonArray(listOf(buildJsonObject { put("id", "m") }))))
        val chosen = WakuRecovery.chooseSnapshot(local, session(4, "remote title"))
        assertEquals(5L, chosen.cursor!!.long("sequence"))
        assertEquals(1, chosen.session.array("messages").size)
        assertEquals("remote title", chosen.session.string("title"))
    }

    @Test fun `rewind revision replaces larger obsolete cursor and clears old interaction state`() {
        val command = buildJsonObject { put("kind", "prompt"); put("messageId", "unconfirmed") }
        val permission = buildJsonObject { put("requestId", "old-approval") }
        val local = WakuProjection.fromSession(session(999).updated("messages" to JsonArray(listOf(
            buildJsonObject { put("id", "removed-by-rewind") },
        )))).copy(permissions = listOf(permission), userInputs = listOf(permission),
            pendingCommands = listOf(command), taskReady = permission, interactionReplay = WakuControlCheckpoint())
        val remote = session(4).updated("history_revision" to JsonPrimitive(1))
        assertTrue(WakuRecovery.needsHistoryRefresh(local.session, remote))
        val chosen = WakuRecovery.chooseSnapshot(local, remote)
        assertEquals(1L, WakuRecovery.historyRevision(chosen.session))
        assertEquals(4L, chosen.cursor!!.long("sequence"))
        assertTrue(chosen.session.array("messages").isEmpty())
        assertTrue(chosen.permissions.isEmpty())
        assertTrue(chosen.userInputs.isEmpty())
        assertNull(chosen.taskReady)
        assertNull(chosen.interactionReplay)
        assertEquals(listOf(command), chosen.pendingCommands)
    }

    @Test fun `late snapshot cannot undo a newer history revision`() {
        val local = WakuProjection.fromSession(session(4).updated("history_revision" to JsonPrimitive(2)))
        val obsolete = session(999).updated("history_revision" to JsonPrimitive(1))
        assertSame(local, WakuRecovery.chooseSnapshot(local, obsolete))
        assertEquals(local.session, WakuSessionTitles.mergeMetadata(local.session, obsolete, fullSnapshot = true))
        assertFalse(WakuRecovery.localStreamAhead(local.session, obsolete))
    }

    @Test fun `catalog revision is not applied without its matching full history`() {
        val local = session(50)
        val summary = session(0, "rewound").updated("history_revision" to JsonPrimitive(1))
        assertEquals(local, WakuSessionTitles.mergeMetadata(local, summary))
        assertFalse(WakuSessionTitles.needsDetails(local, summary))
        assertTrue(WakuRecovery.needsHistoryRefresh(local, summary))
        val legacy = JsonObject(local - "history_revision")
        assertEquals(0L, WakuRecovery.historyRevision(legacy))
        assertFalse(WakuRecovery.needsHistoryRefresh(legacy, local))
    }

    @Test fun `new daemon snapshot replaces obsolete epoch even if local sequence is larger`() {
        val chosen = WakuRecovery.chooseSnapshot(WakuProjection.fromSession(session(999)), session(1, epoch = "new"))
        assertEquals("new", chosen.cursor!!.string("epoch"))
    }

    @Test fun `server with no runtime cursor is not silently replaced by obsolete local runtime`() {
        val remote = session(0).updated("runtime_event_cursor" to JsonNull)
        assertNull(WakuRecovery.chooseSnapshot(WakuProjection.fromSession(session(99)), remote).cursor)
    }

    @Test fun `only stable observed message identity settles prompt uncertainty`() {
        val command = buildJsonObject { put("kind", "prompt"); put("messageId", "m"); put("text", "same text") }
        val snapshot = WakuProjection.fromSession(session(1)).copy(pendingCommands = listOf(command))
        assertEquals(1, WakuRecovery.resolveObservedCommands(snapshot).pendingCommands.size)
        val withEcho = snapshot.copy(session = snapshot.session.updated("messages" to JsonArray(listOf(buildJsonObject { put("id", "m") }))))
        assertTrue(WakuRecovery.resolveObservedCommands(withEcho).pendingCommands.isEmpty())
    }

    @Test fun `recovery resolves observed acknowledgements and exposes only old unobserved prompts`() {
        fun pending(request: String, state: String = "accepted", kind: String = "prompt") = buildJsonObject {
            put("requestId", request); put("messageId", request); put("kind", kind); put("state", state)
        }
        val commands = listOf(pending("observed"), pending("missing"), pending("new"), pending("sending", "sending"),
            pending("approval", kind = "respond"))
        val snapshot = WakuProjection.fromSession(session(1).updated("messages" to JsonArray(listOf(
            buildJsonObject { put("id", "observed") },
        )))).copy(pendingCommands = commands)
        val resolved = WakuRecovery.resolveRecoveredCommands(snapshot, setOf("observed", "missing"))
        assertEquals(listOf("missing", "new", "sending", "approval"), resolved.pendingCommands.map { it.string("requestId") })
        assertEquals(listOf("unknown", "accepted", "sending", "accepted"), resolved.pendingCommands.map { it.string("state") })
        assertNotNull(resolved.pendingCommands.first().string("error"))
        assertTrue(WakuSubmissionStatus.from(resolved.pendingCommands).needsReview)
    }

    @Test fun `recovered message echo clears an acknowledgement without any manual review`() {
        val pending = buildJsonObject {
            put("requestId", "request"); put("messageId", "message"); put("kind", "prompt"); put("state", "accepted")
        }
        val snapshot = WakuProjection.fromSession(session(1).updated("messages" to JsonArray(listOf(
            buildJsonObject { put("id", "message") },
        )))).copy(pendingCommands = listOf(pending))
        val resolved = WakuRecovery.resolveRecoveredCommands(snapshot, setOf("request"))
        assertTrue(resolved.pendingCommands.isEmpty())
        assertEquals(WakuSubmissionStatus(), WakuSubmissionStatus.from(resolved.pendingCommands))
    }

    @Test fun `jitter is bounded and retry never spins`() {
        assertEquals(500L, WakuRecovery.retryDelayMillis(0, 0.0))
        assertEquals(1000L, WakuRecovery.retryDelayMillis(0, 1.0))
        assertEquals(30_000L, WakuRecovery.retryDelayMillis(Int.MAX_VALUE, 1.0))
    }

    private fun page(vararg events: JsonObject, next: Long = 5, more: Boolean = false) = buildJsonObject {
        put("type", "replayEvents"); put("epoch", "e"); put("throughSequence", 5)
        put("nextSequence", next); put("hasMore", more); put("events", JsonArray(events.toList()))
    }
    private fun event(seq: Long, id: String = "s") = buildJsonObject {
        put("sessionId", id); put("runtimeId", "r"); put("epoch", "e"); put("sequence", seq)
    }

    @Test fun `sequence gaps for non replayable terminal output are allowed`() {
        assertEquals(5L, WakuRecovery.validateReplayPage(page(event(2), event(4)), "r", "s", 0, null))
    }
    @Test fun `replay rejects identity changes reversed events and missing fields`() {
        val invalid = listOf(page(event(2, "other")), page(event(4), event(2)), page(event(2)).updated("epoch" to JsonNull),
            page(event(2)).updated("hasMore" to JsonNull), page(event(2)).updated("nextSequence" to JsonPrimitive("5")),
            page(next = 0, more = true), page(next = 4))
        invalid.forEach { value ->
            assertTrue("must reject $value", runCatching { WakuRecovery.validateReplayPage(value, "r", "s", 0, null) }.isFailure)
        }
    }
    @Test fun `replay fixed upper bound cannot drift across pages`() {
        assertTrue(runCatching { WakuRecovery.validateReplayPage(page(event(3)), "r", "s", 2, 4) }.isFailure)
    }
}
