package com.deerflow.app.data.waku

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WakuRuntimeAttachmentTest {
    private val runtime = "20000000-0000-0000-0000-000000000001"
    private val operation = "30000000-0000-0000-0000-000000000001"

    // Field names match waku-protocol's v12 ResponsePayload::SessionRuntime.
    private fun payload(runtimeId: String? = runtime, pending: JsonElement = JsonNull) = buildJsonObject {
        put("type", "sessionRuntime"); put("runtimeId", runtimeId?.let(::JsonPrimitive) ?: JsonNull)
        put("supportsSteer", true); put("conversationCapability", true)
        put("deerflowReady", if (runtimeId == null) JsonNull else buildJsonObject {
            put("kind", "ready"); put("connectionId", "acp-connection"); put("sessionId", "acp-session")
            put("capabilities", buildJsonObject { put("artifactRead", true); put("sessionBudget", true) })
        })
        put("pendingHistory", pending)
    }
    private fun pending() = buildJsonObject {
        put("kind", "rewind"); put("turnCount", 2); put("operationId", operation)
    }

    @Test fun v12AttachmentReadsCurrentCapabilitiesAndReady() {
        val info = WakuRuntimeAttachment.parse(payload())
        assertEquals(runtime, info.runtimeId)
        assertEquals(true, info.conversationCapability)
        assertEquals("acp-connection", info.deerflowReady!!.string("connectionId"))
        assertNull(info.pendingHistory)
        val snapshot = info.applyTo(WakuProjection.fromSession(WakuProjection.createSession("s", "p")))
        assertEquals(runtime, snapshot.runtimeId)
        assertTrue(snapshot.session.bool("conversation_capability"))
    }

    @Test fun pendingHistorySurvivesWithoutALiveRuntimeAndClearsAfterRecovery() {
        val info = WakuRuntimeAttachment.parse(payload(runtimeId = null, pending = pending()))
        val stored = info.applyTo(WakuProjection.fromSession(WakuProjection.createSession("s", "p")))
        assertNull(stored.runtimeId)
        assertEquals(pending(), stored.pendingHistory)
        assertEquals(stored, WakuJson.decodeFromString(WakuSnapshot.serializer(),
            WakuJson.encodeToString(WakuSnapshot.serializer(), stored)))
        assertNull(WakuRuntimeAttachment.parse(payload()).applyTo(stored).pendingHistory)
    }

    @Test fun absentOrRefusedCapabilityCannotRetainAnOldConfirmation() {
        val supported = WakuRuntimeAttachment.parse(payload()).applyTo(
            WakuProjection.fromSession(WakuProjection.createSession("s", "p")))
        val refused = WakuRuntimeAttachment.parse(payload().updated("conversationCapability" to JsonPrimitive(false)))
        assertFalse(refused.applyTo(supported).session.bool("conversation_capability"))
        val absent = WakuRuntimeAttachment.parse(payload().updated("conversationCapability" to JsonNull))
        assertEquals(JsonNull, absent.applyTo(supported).session["conversation_capability"])
    }

    @Test fun malformedHistoryOrReadyCannotBeTreatedAsNoPendingOperation() {
        val invalid = listOf(payload(pending = JsonPrimitive("pending")), payload(pending = pending().updated("turnCount" to JsonNull)),
            payload(pending = pending().updated("operationId" to JsonPrimitive("not-a-uuid"))),
            payload().updated("runtimeId" to JsonNull), payload().updated("deerflowReady" to JsonPrimitive(true)))
        invalid.forEach { assertTrue(runCatching { WakuRuntimeAttachment.parse(it) }.isFailure) }
    }
}
