package com.deerflow.app.data.waku

import com.deerflow.app.domain.BlockKind
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WakuProjectionTest {
    private val initial get() = WakuProjection.bindRuntime(
        WakuProjection.fromSession(WakuProjection.createSession("session", "project", nowMillis = 1_000)), "runtime")

    @Test fun creationMatchesWakuV10WireFixture() {
        val sessionId = "00000000-0000-0000-0000-000000000001"
        val projectId = "00000000-0000-0000-0000-000000000002"
        val project = WakuProjection.createProject(projectId, "Project", "/workspace", 1_700_000_000_123)
        val session = WakuProjection.createSession(sessionId, projectId, "test-model", 1_700_000_000_123)
        assertEquals("deerFlow", session["provider"]!!.jsonPrimitive.content)
        assertEquals("fullAccess", session["runtime_mode"]!!.jsonPrimitive.content)
        assertEquals("local", session["workspace"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000L, session["created_at"]!!.jsonPrimitive.long)
        assertEquals(project["created_at"], session["created_at"])
        assertFalse(session.containsKey("projectId"))
        assertEquals(projectId, session["project_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, session["provider_cursor"])
        for (key in listOf("messages", "transcript_blocks", "turns", "queued_messages")) assertTrue(session[key] is JsonArray)
        val raw = buildJsonObject {
            put("sessionId", sessionId); put("runtimeId", UUID.randomUUID().toString()); put("epoch", UUID.randomUUID().toString())
            put("sequence", 1); put("replayable", true); put("event", buildJsonObject { put("kind", "turnStarted"); put("payload", JsonNull) })
        }
        val active = WakuProjection.reduce(WakuProjection.fromSession(session), raw, 1_700_000_000_123)
        val turn = active.session["turns"]!!.jsonArray.single().jsonObject
        UUID.fromString(turn["id"]!!.jsonPrimitive.content)
        assertEquals(1, turn["turn_count"]!!.jsonPrimitive.int)
        assertTrue(turn["provider_turn_started"]!!.jsonPrimitive.boolean)
        val cursor = active.session["runtime_event_cursor"]!!.jsonObject
        assertEquals(raw["runtimeId"], cursor["runtime_id"])
        assertFalse(cursor.containsKey("runtimeId"))
    }

    @Test fun backendApprovalEventsOverrideRememberedModeWithoutDismissingPermissionCards() {
        val ask = reduce(initial, 1, "toolApprovalChanged", JsonPrimitive("ask"))
        assertEquals("ask", ask.session["runtime_mode"]!!.jsonPrimitive.content)
        val full = reduce(ask, 2, "toolApprovalChanged", JsonPrimitive("allow_always"))
        assertEquals("fullAccess", full.session["runtime_mode"]!!.jsonPrimitive.content)
        val permission = reduce(full, 3, "permission", buildJsonObject { put("requestId", "special-approval") })
        val off = reduce(permission, 4, "toolApprovalChanged", JsonPrimitive("off"))
        assertEquals("fullAccess", off.session["runtime_mode"]!!.jsonPrimitive.content)
        assertEquals(permission.permissions, off.permissions)
        assertEquals(off, reduce(off, 1, "toolApprovalChanged", JsonPrimitive("ask")))
    }

    @Test fun replayDoesNotDuplicateIdenticalTextButDistinctEventsDo() {
        var state = reduce(initial, 1, "promptSubmitted", prompt("turn", "message", "hello"))
        state = reduce(state, 2, "turnStarted")
        state = reduce(state, 3, "textDelta", JsonPrimitive("same"))
        val firstId = messages(state).last()["id"]
        val duplicate = reduce(state, 3, "textDelta", JsonPrimitive("same"))
        assertEquals(state, duplicate)
        val next = reduce(duplicate, 4, "textDelta", JsonPrimitive("same"))
        assertEquals("samesame", messages(next).last()["content"]!!.jsonPrimitive.content)
        assertEquals(firstId, messages(next).last()["id"])
        assertEquals(2, messages(next).size)
    }

    @Test fun echoedOptimisticSubmissionUsesIdsRatherThanText() {
        val first = reduce(initial, 1, "promptSubmitted", prompt("turn-1", "message-1", "repeat"))
        val echo = reduce(first, 2, "promptSubmitted", prompt("turn-1", "message-1", "repeat"))
        assertEquals(1, messages(echo).size)
        assertEquals(1, echo.session["turns"]!!.jsonArray.size)
        val finished = reduce(echo, 3, "turnFinished", buildJsonObject { put("success", true) })
        val second = reduce(finished, 4, "promptSubmitted", prompt("turn-2", "message-2", "repeat"))
        assertEquals(2, messages(second).count { it["role"]!!.jsonPrimitive.content == "user" })
        assertEquals(2, second.session["turns"]!!.jsonArray.size)
    }

    @Test fun toolBoundariesPreserveOrderedTextAndReasoningOnRebuild() {
        var state = reduce(initial, 1, "promptSubmitted", prompt("turn", "user", "inspect"))
        state = reduce(state, 2, "reasoningDelta", JsonPrimitive("thinking"))
        state = reduce(state, 3, "textDelta", JsonPrimitive("before"))
        state = reduce(state, 4, "activity", tool("call-1", "Read file", complete = false))
        state = reduce(state, 5, "activity", tool("call-1", "Read file", complete = true))
        state = reduce(state, 6, "textDelta", JsonPrimitive("after"))
        val display = WakuProjection.display(state)
        assertEquals(listOf(BlockKind.USER, BlockKind.REASONING, BlockKind.ASSISTANT, BlockKind.TOOL, BlockKind.ASSISTANT), display.blocks.map { it.kind })
        assertEquals(listOf("inspect", "thinking", "before", "result", "after"), display.blocks.map { it.content })
        val reopened = WakuProjection.fromSession(state.session)
        assertEquals(display.blocks, WakuProjection.display(reopened).blocks)
        assertEquals(state, reduce(state, 5, "activity", tool("call-1", "Read file", true)))
    }

    @Test fun toolNameAndCompletionSurviveResultUpdatesAndHistoryHydration() {
        var state = reduce(initial, 1, "promptSubmitted", prompt("turn", "message", "inspect"))
        state = reduce(state, 2, "richActivity", buildJsonObject {
            put("id", "call"); put("kind", "fileRead"); put("tool_name", "read_file")
            put("title", "/private/report.txt"); put("arguments", """{"path":"/private/report.txt"}""")
            put("complete", false)
        })
        val running = WakuProjection.display(state).blocks.single { it.kind == BlockKind.TOOL }
        assertEquals("read_file", running.tool!!.name)
        assertTrue(running.tool.isRunning)
        state = reduce(state, 3, "richActivity", buildJsonObject {
            put("id", "call"); put("title", "Tool Result"); put("output", "private-result"); put("complete", true)
        })
        val completed = WakuProjection.display(state).blocks.single { it.kind == BlockKind.TOOL }
        assertEquals(running.key, completed.key)
        assertEquals("read_file", completed.tool!!.name)
        assertFalse(completed.tool.isRunning)
        assertTrue(WakuProjection.display(state).running)
        assertEquals(completed, WakuProjection.display(WakuProjection.fromSession(state.session)).blocks.single { it.kind == BlockKind.TOOL })
    }

    @Test fun failedToolDoesNotKeepSpinningDuringAnActiveTurn() {
        val state = reduce(reduce(initial, 1, "promptSubmitted", prompt("turn", "message", "inspect")), 2, "richActivity", buildJsonObject {
            put("id", "failed"); put("kind", "command"); put("tool_name", "execute")
            put("failed", true); put("complete", false)
        })
        val tool = WakuProjection.display(state).blocks.single { it.kind == BlockKind.TOOL }.tool!!
        assertTrue(tool.isError)
        assertFalse(tool.isRunning)
    }

    @Test fun identicalToolTitlesRemainDistinctWithoutMatchingIds() {
        var state = reduce(initial, 1, "promptSubmitted", prompt("turn", "user", "inspect"))
        state = reduce(state, 2, "activity", tool("call-1", "Read file", false))
        state = reduce(state, 3, "activity", tool("call-2", "Read file", false))
        state = reduce(state, 4, "activity", tool("call-1", "Read file", true))
        assertEquals(2, WakuProjection.display(state).blocks.count { it.kind == BlockKind.TOOL })
    }

    @Test fun oldEpochAndOldRuntimeCannotRollProjectionBack() {
        val first = reduce(initial, 10, "autoTitleUpdated", JsonPrimitive("first"), epoch = "epoch-a")
        val next = reduce(first, 1, "autoTitleUpdated", JsonPrimitive("new"), epoch = "epoch-b")
        assertEquals("new", next.session["auto_title"]!!.jsonPrimitive.content)
        assertEquals(next, reduce(next, 99, "autoTitleUpdated", JsonPrimitive("stale"), epoch = "epoch-a"))
        val bound = WakuProjection.bindRuntime(next, "new-runtime")
        assertEquals(bound, reduce(bound, 100, "autoTitleUpdated", JsonPrimitive("old runtime"), epoch = "epoch-b"))
    }

    @Test fun terminalOutputDoesNotAdvanceTranscriptCursor() {
        val first = reduce(initial, 1, "autoTitleUpdated", JsonPrimitive("title"))
        val ephemeral = event(8, "terminalOutput", JsonPrimitive("shell")) + ("replayable" to JsonPrimitive(false))
        assertEquals(first, WakuProjection.reduce(first, JsonObject(ephemeral), 2_000))
        val afterHole = reduce(first, 9, "autoTitleUpdated", JsonPrimitive("later"))
        assertEquals(9, afterHole.cursor!!["sequence"]!!.jsonPrimitive.int)
    }

    @Test fun pendingPromptsAndProviderFieldsSurviveUntilCompletion() {
        val unknown = JsonObject(initial.session + ("future_field" to buildJsonObject { put("keep", true) }))
        var state = initial.copy(session = unknown)
        state = reduce(state, 1, "promptSubmitted", prompt("turn", "user", "inspect"))
        state = reduce(state, 2, "permission", buildJsonObject { put("requestId", "permission"); put("options", JsonArray(emptyList())) })
        state = reduce(state, 3, "userInputRequested", buildJsonObject { put("requestId", "question"); put("questions", JsonArray(emptyList())) })
        state = reduce(state, 4, "connected", buildJsonObject { put("provider", "deerflow"); put("sessionId", "native") })
        state = reduce(state, 5, "deerflowModelsChanged", buildJsonObject { put("model", "chosen"); put("thinkingMode", "enabled") })
        assertEquals(1, state.permissions.size)
        assertEquals(1, state.userInputs.size)
        assertEquals("chosen", state.session["model"]!!.jsonPrimitive.content)
        assertEquals("enabled", state.session["thinking_mode"]!!.jsonPrimitive.content)
        assertEquals("native", state.session["provider_cursor"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        state = reduce(state, 6, "turnFinished", buildJsonObject { put("success", true) })
        assertTrue(state.permissions.isEmpty())
        assertTrue(state.userInputs.isEmpty())
        assertEquals(unknown["future_field"], state.session["future_field"])
        assertEquals("idle", state.session["status"]!!.jsonPrimitive.content)
    }

    @Test fun serverHydrationCursorRejectsAlreadyMaterializedEvents() {
        val snapshot = reduce(reduce(initial, 1, "promptSubmitted", prompt("turn", "user", "hi")), 2, "textDelta", JsonPrimitive("partial"))
        val hydrated = WakuProjection.fromSession(snapshot.session)
        assertEquals(hydrated, reduce(hydrated, 2, "textDelta", JsonPrimitive("partial")))
        val continued = reduce(hydrated, 3, "textDelta", JsonPrimitive(" end"))
        assertEquals("partial end", messages(continued).last()["content"]!!.jsonPrimitive.content)
        assertEquals(messages(snapshot).last()["id"], messages(continued).last()["id"])
    }

    private fun reduce(snapshot: WakuSnapshot, seq: Long, kind: String, payload: JsonElement = JsonNull, epoch: String = "epoch") =
        WakuProjection.reduce(snapshot, event(seq, kind, payload, epoch), 12_000)
    private fun event(seq: Long, kind: String, payload: JsonElement, epoch: String = "epoch") = buildJsonObject {
        put("sessionId", "session"); put("runtimeId", "runtime"); put("epoch", epoch); put("sequence", seq); put("replayable", true)
        put("event", buildJsonObject { put("kind", kind); put("payload", payload) })
    }
    private fun messages(snapshot: WakuSnapshot) = snapshot.session["messages"]!!.jsonArray.map { it.jsonObject }
    private fun prompt(turn: String, message: String, text: String) = buildJsonObject { put("turnId", turn); put("messageId", message); put("message", text) }
    private fun tool(id: String, title: String, complete: Boolean) = buildJsonObject {
        put("id", id); put("kind", "fileRead"); put("title", title); put("detail", "result"); put("complete", complete)
    }
}
