package com.deerflow.app.data.waku

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Acceptance scenarios at the durable client boundary, without a live model or Android device. */
class WakuWeakNetworkScenarioTest {
    private val sessionId = "10000000-0000-0000-0000-000000000001"
    private val runtimeId = "20000000-0000-0000-0000-000000000001"
    private val turnId = "30000000-0000-0000-0000-000000000001"
    private val messageId = "40000000-0000-0000-0000-000000000001"

    private fun event(sequence: Long, kind: String, payload: JsonElement = JsonNull) = buildJsonObject {
        put("type", "event"); put("sessionId", sessionId); put("runtimeId", runtimeId)
        put("epoch", "epoch-one"); put("sequence", sequence); put("replayable", true)
        put("event", buildJsonObject { put("kind", kind); put("payload", payload) })
    }

    private fun submitted() = event(1, "promptSubmitted", buildJsonObject {
        put("message", "Run a controlled test"); put("turnId", turnId); put("messageId", messageId)
    })

    @Test fun `phone process dies after cursor and partial text - replay restores exactly one complete answer`() {
        val root = Files.createTempDirectory("waku-scenario").toFile()
        try {
            val cache = WakuSessionStore(root, "wss://test.invalid/v1", "device-token")
            var snapshot = WakuProjection.bindRuntime(WakuProjection.fromSession(WakuProjection.createSession(sessionId, turnId)), runtimeId)
            snapshot = WakuProjection.reduce(snapshot, submitted(), 1000)
            snapshot = WakuProjection.reduce(snapshot, event(2, "turnStarted"), 1000)
            val prefix = event(3, "textDelta", JsonPrimitive("Saved prefix "))
            snapshot = WakuProjection.reduce(snapshot, prefix, 1000)
            cache.save(snapshot)

            // A fresh store models a new process; it has no in-memory text buffer or socket.
            var restored = WakuSessionStore(root, "wss://test.invalid/v1", "device-token").load(sessionId)!!
            assertEquals(3L, restored.cursor!!.long("sequence"))
            val replay = listOf(prefix, event(4, "textDelta", JsonPrimitive("and recovered suffix.")),
                event(5, "turnFinished", buildJsonObject { put("success", true); put("summary", JsonNull) }))
            replay.forEach { restored = WakuProjection.reduce(restored, it, 2000) }
            cache.save(restored)
            val messages = restored.session.array("messages").filterIsInstance<JsonObject>()
            assertEquals(2, messages.size)
            assertEquals("Saved prefix and recovered suffix.", messages.last().string("content"))
            assertEquals("idle", restored.session.string("status"))
            assertEquals(restored, cache.load(sessionId))
        } finally { root.deleteRecursively() }
    }

    @Test fun `lost prompt acknowledgement stays uncertain until exact message identity arrives`() {
        val root = Files.createTempDirectory("waku-unknown").toFile()
        try {
            val cache = WakuSessionStore(root, "wss://test.invalid/v1", "device-token")
            val command = buildJsonObject {
                put("id", "50000000-0000-0000-0000-000000000001"); put("kind", "prompt")
                put("messageId", messageId); put("turnId", turnId); put("delivery", "unknown"); put("text", "Run a controlled test")
            }
            val beforeSend = WakuProjection.fromSession(WakuProjection.createSession(sessionId, turnId))
                .copy(pendingCommands = listOf(command))
            cache.save(beforeSend)
            val afterRestart = cache.load(sessionId)!!
            assertEquals(listOf(command), WakuRecovery.resolveObservedCommands(afterRestart).pendingCommands)
            val echoed = WakuProjection.reduce(afterRestart, submitted())
            val reconciled = WakuRecovery.resolveObservedCommands(echoed)
            assertTrue(reconciled.pendingCommands.isEmpty())
            assertEquals(1, reconciled.session.array("messages").size)
            // Duplicate delivery cannot create a second user turn or require another prompt send.
            assertEquals(reconciled, WakuProjection.reduce(reconciled, submitted()))
        } finally { root.deleteRecursively() }
    }

    @Test fun `cached approval cannot cross runtime replacement or server profile`() {
        val root = Files.createTempDirectory("waku-approval").toFile()
        try {
            var snapshot = WakuProjection.fromSession(WakuProjection.createSession(sessionId, turnId))
            snapshot = WakuProjection.reduce(snapshot, submitted())
            snapshot = WakuProjection.reduce(snapshot, event(2, "permission", buildJsonObject {
                put("requestId", "permission-one"); put("title", "Confirm controlled test"); put("options", JsonArray(emptyList()))
            }))
            val first = WakuSessionStore(root, "wss://first.invalid/v1", "first-token")
            first.save(snapshot)
            assertEquals(1, first.load(sessionId)!!.permissions.size)
            assertNull(WakuSessionStore(root, "wss://second.invalid/v1", "first-token").load(sessionId))
            assertNull(WakuSessionStore(root, "wss://first.invalid/v1", "rotated-token").load(sessionId))
            val replacement = WakuProjection.bindRuntime(snapshot, "60000000-0000-0000-0000-000000000001")
            assertTrue(replacement.permissions.isEmpty())
            assertEquals(replacement, WakuProjection.reduce(replacement, event(3, "textDelta", JsonPrimitive("stale"))))
        } finally { root.deleteRecursively() }
    }
}
