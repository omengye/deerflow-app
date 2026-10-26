package com.deerflow.app.data.waku

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class WakuSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val endpoint = "wss://example.test/v1"
    private val token = "never-write-this-token"

    @Test fun reopeningRestoresStreamingContentCursorAndUncertainCommandTogether() {
        val store = WakuSessionStore(temporary.root, endpoint, token)
        var snapshot = WakuProjection.fromSession(WakuProjection.createSession("session", "project"))
        snapshot = WakuProjection.reduce(snapshot, event(1, "promptSubmitted", buildJsonObject {
            put("turnId", "turn"); put("messageId", "user"); put("message", "hello")
        }))
        snapshot = WakuProjection.reduce(snapshot, event(2, "textDelta", JsonPrimitive("streamed")))
        snapshot = snapshot.copy(pendingCommands = listOf(buildJsonObject {
            put("requestId", "uncertain-request"); put("state", "unknown"); put("command", buildJsonObject { put("type", "prompt"); put("prompt", "do it") })
        }))
        store.save(snapshot)
        val recovered = WakuSessionStore(temporary.root, endpoint, token).load("session")!!
        assertEquals(snapshot, recovered)
        assertEquals(recovered, WakuProjection.reduce(recovered, event(2, "textDelta", JsonPrimitive("streamed"))))
        val continued = WakuProjection.reduce(recovered, event(3, "textDelta", JsonPrimitive(" again")))
        assertEquals("streamed again", continued.session["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(1, continued.pendingCommands.size)
        assertEquals(listOf(snapshot), store.list())
    }

    @Test fun profilesAreIsolatedAndCredentialsNeverAppearOnDisk() {
        val snapshot = WakuSnapshot(WakuProjection.createSession("../../session", "project"))
        val first = WakuSessionStore(temporary.root, endpoint, token)
        first.save(snapshot)
        assertNull(WakuSessionStore(temporary.root, endpoint, "another-token").load("../../session"))
        assertNull(WakuSessionStore(temporary.root, "wss://other.test/v1", token).load("../../session"))
        temporary.root.walkTopDown().forEach { file ->
            assertFalse(file.name.contains(token))
            if (file.isFile) assertFalse(file.readText().contains(token))
            assertTrue(file.canonicalPath.startsWith(temporary.root.canonicalPath))
        }
        first.delete("../../session")
        assertTrue(first.list().isEmpty())
    }

    @Test fun interruptedFallbackReplacementRecoversWholeBackupAndIgnoresTemporaryFile() {
        val store = WakuSessionStore(temporary.root, endpoint, token)
        val snapshot = WakuSnapshot(WakuProjection.createSession("session", "project"))
        store.save(snapshot)
        val file = temporary.root.walkTopDown().single { it.isFile && it.extension == "json" }
        val backup = File(file.parentFile, "${file.name}.bak")
        Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        File(file.parentFile, "interrupted.tmp").writeText("{broken")
        assertEquals(snapshot, store.load("session"))
        assertEquals(listOf(snapshot), store.list())
        file.writeText("{broken")
        assertEquals(snapshot, store.load("session"))
        store.save(snapshot)
        assertEquals(snapshot, store.load("session"))
    }

    @Test(expected = IOException::class) fun corruptFileCannotSilentlyBecomeEmptyHistory() {
        val store = WakuSessionStore(temporary.root, endpoint, token)
        store.save(WakuSnapshot(WakuProjection.createSession("session", "project")))
        temporary.root.walkTopDown().single { it.isFile && it.extension == "json" }.writeText("{broken")
        store.load("session")
    }

    private fun event(sequence: Long, kind: String, payload: JsonElement) = buildJsonObject {
        put("sessionId", "session"); put("runtimeId", "runtime"); put("epoch", "epoch"); put("sequence", sequence); put("replayable", true)
        put("event", buildJsonObject { put("kind", kind); put("payload", payload) })
    }
}
