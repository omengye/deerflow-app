package com.deerflow.app.data.waku

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakuTaskArtifactsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session = "10000000-0000-0000-0000-000000000001"
    private val runtime = "20000000-0000-0000-0000-000000000001"
    private val epoch = "30000000-0000-0000-0000-000000000001"

    @Test
    fun correlatedRepliesDownloadPinnedChunksIntoSafeCache() = runBlocking {
        val fixture = Fixture(temporary.newFolder())
        val artifacts = fixture.helper.loadArtifacts(session, runtime)
        assertEquals(1, artifacts.size)
        val artifact = artifacts.single()
        assertEquals("secret.txt", artifact.name)
        val file = File(java.net.URI(artifact.url)).canonicalFile
        assertTrue(file.toPath().startsWith(fixture.directory.canonicalFile.toPath()))
        assertArrayEquals(fixture.bytes, file.readBytes())
        val reads = fixture.actions.filter { it.text("kind") == "artifactRead" }
        assertEquals(listOf(0L, 4L, 8L), reads.map { it.long("offset") })
        assertEquals(JsonNull, reads.first()["expectedSha256"])
        assertTrue(reads.drop(1).all { it.text("expectedSha256") == fixture.hash })
        assertTrue(fixture.actions.all { it.text("kind") in setOf("list", "artifactList", "artifactRead") })
    }

    @Test
    fun ackAloneCannotCompleteArtifactRequest() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), answer = false)
        val result = runCatching { fixture.helper.loadArtifacts(session, runtime) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("超时"))
        assertEquals(1, fixture.actions.size)
    }

    @Test
    fun wrongCorrelationCannotCompleteArtifactRequest() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), wrongCorrelation = true)
        val result = runCatching { fixture.helper.loadArtifacts(session, runtime) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("超时"))
    }

    @Test
    fun missingReadyIsRecoveredWithoutExecutingWork() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), initiallyReady = false)
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(1, fixture.replayRequests)
        assertTrue(fixture.actions.none { it.text("kind") in setOf("executionResume", "resume") })
    }

    @Test
    fun verifiedCacheAvoidsDownloadingAllChunksAgain() = runBlocking {
        val fixture = Fixture(temporary.newFolder())
        val first = fixture.helper.loadArtifacts(session, runtime).single()
        fixture.actions.clear()
        assertEquals(first.url, fixture.helper.loadArtifacts(session, runtime).single().url)
        assertEquals(1, fixture.actions.count { it.text("kind") == "artifactRead" })
        File(java.net.URI(first.url)).writeBytes("jello world".toByteArray())
        fixture.actions.clear()
        fixture.helper.loadArtifacts(session, runtime)
        assertEquals(3, fixture.actions.count { it.text("kind") == "artifactRead" })
        assertArrayEquals(fixture.bytes, File(java.net.URI(first.url)).readBytes())
    }

    @Test
    fun changedHashAbortsAndLeavesNoPartialFile() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), corruptHash = true)
        assertTrue(runCatching { fixture.helper.loadArtifacts(session, runtime) }.isFailure)
        assertTrue(File(fixture.directory, "waku-artifacts").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun invalidOffsetAndCorruptDataAreRejected() = runBlocking {
        for (offsetFailure in listOf(true, false)) {
            val fixture = Fixture(temporary.newFolder(), corruptOffset = offsetFailure, corruptBytes = !offsetFailure)
            assertTrue(runCatching { fixture.helper.loadArtifacts(session, runtime) }.isFailure)
            assertTrue(File(fixture.directory, "waku-artifacts").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun oversizeMetadataNeverStartsFileRead() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), maxFileBytes = 4)
        assertTrue(runCatching { fixture.helper.loadArtifacts(session, runtime) }.isFailure)
        assertTrue(fixture.actions.none { it.text("kind") == "artifactRead" })
    }

    @Test
    fun disconnectReleasesPendingControls() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), answer = false)
        val result = async { runCatching { fixture.helper.loadArtifacts(session, runtime) } }
        // loadArtifacts uses Dispatchers.IO; wait until its request was actually admitted.
        fixture.admitted.await()
        fixture.helper.disconnected()
        assertTrue(result.await().exceptionOrNull()?.message.orEmpty().contains("断开"))
    }

    private inner class Fixture(
        val directory: File,
        val answer: Boolean = true,
        val wrongCorrelation: Boolean = false,
        initiallyReady: Boolean = true,
        val corruptHash: Boolean = false,
        val corruptOffset: Boolean = false,
        val corruptBytes: Boolean = false,
        maxFileBytes: Long = 1024,
    ) {
        val bytes = "hello world".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val actions = mutableListOf<JsonObject>()
        val admitted = kotlinx.coroutines.CompletableDeferred<Unit>()
        var replayRequests = 0
        var sequence = 1L
        val ready = objectOf("kind" to JsonPrimitive("ready"), "connectionId" to JsonPrimitive("connection-1"), "sessionId" to JsonPrimitive("acp-1"),
            "capabilities" to objectOf("tree" to JsonPrimitive(true), "artifactList" to JsonPrimitive(true), "artifactRead" to JsonPrimitive(true)))
        val metadata = objectOf("id" to JsonPrimitive("asset-1"), "name" to JsonPrimitive("../../secret.txt"), "mimeType" to JsonPrimitive("text/plain"), "size" to JsonPrimitive(bytes.size))
        val helper = WakuTaskArtifacts(directory, maxFileBytes = maxFileBytes, maxTotalBytes = maxOf(2048, maxFileBytes), chunkBytes = 4, requestTimeoutMs = 300) { command, sid, rid ->
            assertEquals(session, sid); assertEquals(runtime, rid)
            if (command.text("type") == "replayEvents") {
                replayRequests++
                objectOf("type" to JsonPrimitive("replayEvents"), "epoch" to JsonPrimitive(epoch), "throughSequence" to JsonPrimitive(1),
                    "nextSequence" to JsonPrimitive(1), "hasMore" to JsonPrimitive(false), "events" to JsonArray(listOf(wire(ready, 1))))
            } else {
                assertEquals("deerFlowTasks", command.text("type"))
                val control = command["command"]!!.jsonObject
                assertEquals("connection-1", control.text("connectionId"))
                assertEquals("acp-1", control.text("sessionId"))
                val action = control["action"]!!.jsonObject
                actions += action
                admitted.complete(Unit)
                if (answer) answerControl(control, action)
                objectOf("type" to JsonPrimitive("ack"))
            }
        }

        init { if (initiallyReady) helper.onWireEvent(wire(ready, 1)) }

        private fun answerControl(command: JsonObject, action: JsonObject) {
            val kind: String
            val data: JsonObject
            when (action.text("kind")) {
                "list" -> {
                    kind = "page"
                    data = objectOf("sessionId" to JsonPrimitive("acp-1"), "hasMore" to JsonPrimitive(false), "nodes" to JsonArray(listOf(
                        objectOf("rootId" to JsonPrimitive("root-1"), "id" to JsonPrimitive("root/root-1"), "rootSessionId" to JsonPrimitive("acp-1")),
                    )))
                }
                "artifactList" -> {
                    kind = "artifacts"
                    data = objectOf("rootId" to JsonPrimitive("root-1"), "nodeId" to JsonPrimitive("root/root-1"),
                        "hasMore" to JsonPrimitive(false), "artifacts" to JsonArray(listOf(metadata)))
                }
                else -> {
                    assertEquals("artifactRead", action.text("kind"))
                    kind = "artifact"
                    val offset = action.long("offset").toInt()
                    val end = minOf(bytes.size, offset + 4)
                    val chunk = bytes.copyOfRange(offset, end)
                    if (corruptBytes && offset == 0) chunk[0] = 0
                    data = objectOf("rootId" to JsonPrimitive("root-1"), "nodeId" to JsonPrimitive("root/root-1"), "artifact" to metadata,
                        "sha256" to JsonPrimitive(if (corruptHash && offset > 0) "0".repeat(64) else hash),
                        "encoding" to JsonPrimitive("base64"), "data" to JsonPrimitive(Base64.getEncoder().encodeToString(chunk)),
                        "offset" to JsonPrimitive(if (corruptOffset) offset + 1 else offset), "nextOffset" to JsonPrimitive(end), "eof" to JsonPrimitive(end == bytes.size))
                }
            }
            val echoed = if (wrongCorrelation) JsonObject(command.toMutableMap().apply { put("requestId", JsonPrimitive("other-request")) }) else command
            helper.onWireEvent(wire(objectOf("kind" to JsonPrimitive("reply"), "command" to echoed,
                "response" to objectOf("kind" to JsonPrimitive(kind), "data" to data)), ++sequence))
        }
        private fun wire(payload: JsonObject, sequence: Long) = objectOf("type" to JsonPrimitive("event"), "sessionId" to JsonPrimitive(session),
            "runtimeId" to JsonPrimitive(runtime), "epoch" to JsonPrimitive(epoch), "sequence" to JsonPrimitive(sequence),
            "event" to objectOf("kind" to JsonPrimitive("deerflowTasks"), "payload" to payload))
    }

    private fun objectOf(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: error("missing $key")
}
