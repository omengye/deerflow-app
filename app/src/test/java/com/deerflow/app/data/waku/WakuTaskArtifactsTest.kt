package com.deerflow.app.data.waku

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
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

    @Test fun listingDoesNotReadFileBodiesAndSelectedDownloadVerifiesContent() = runBlocking {
        val fixture = Fixture(temporary.newFolder())
        val listed = fixture.helper.listArtifacts(session, runtime)
        assertEquals(1, listed.size)
        assertEquals("", listed.single().url)
        assertTrue(fixture.actions.none { it.text("kind") == "artifactRead" })
        assertTrue(fixture.directory.walkTopDown().none { it.isFile })
        val downloaded = fixture.helper.loadArtifact(session, runtime, listed.single().path)
        assertArrayEquals(fixture.bytes, File(java.net.URI(downloaded.url)).readBytes())
        assertTrue(fixture.actions.any { it.text("kind") == "artifactRead" })
    }

    @Test fun selectedDownloadCannotReadAnUnlistedArtifact() = runBlocking {
        val fixture = Fixture(temporary.newFolder())
        assertTrue(runCatching { fixture.helper.loadArtifact(session, runtime, "waku:other/task/file") }.isFailure)
        assertTrue(fixture.actions.none { it.text("kind") == "artifactRead" })
    }

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
        assertEquals(3, fixture.actions.size)
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

    @Test fun v12AttachReadyAvoidsReplayingHistoryToReadArtifacts() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), initiallyReady = false)
        fixture.helper.onAttached(session, WakuRuntimeAttachment(runtime, true, fixture.ready, null))
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(0, fixture.replayRequests)
    }

    @Test fun oldReplayedReadyCannotReplaceCurrentAttachAuthority() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), initiallyReady = false)
        fixture.helper.onAttached(session, WakuRuntimeAttachment(runtime, true, fixture.ready, null))
        fixture.helper.onWireEvent(artifactWire(fixture.ready.updated("connectionId" to JsonPrimitive("obsolete")), 1), replayed = true)
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(0, fixture.replayRequests)
    }

    @Test fun liveReadySupersedesAnOlderAttachResponse() = runBlocking {
        val fixture = Fixture(temporary.newFolder())
        val obsolete = fixture.ready.updated("connectionId" to JsonPrimitive("obsolete"))
        fixture.helper.onAttached(session, WakuRuntimeAttachment(runtime, true, obsolete, null))
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(0, fixture.replayRequests)
    }

    @Test fun newLiveReadyCanReplaceAttachAuthority() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), initiallyReady = false)
        fixture.helper.onAttached(session, WakuRuntimeAttachment(runtime, true,
            fixture.ready.updated("connectionId" to JsonPrimitive("obsolete")), null))
        fixture.helper.onWireEvent(artifactWire(fixture.ready, 2))
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(0, fixture.replayRequests)
    }

    @Test fun normalPromptCompletionKeepsTheArtifactConnectionUsable() = runBlocking {
        val fixture = Fixture(temporary.newFolder())
        // This order matches the ACP prompt completion callback in the server.
        fixture.helper.onWireEvent(artifactWire(fixture.ready.updated("kind" to JsonPrimitive("invalidated")), ++fixture.sequence))
        fixture.helper.onWireEvent(driverWire("turnFinished", buildJsonObject { put("success", true) }, ++fixture.sequence))
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(0, fixture.replayRequests)
        assertEquals(listOf("list", "artifactList", "artifactRead", "artifactRead", "artifactRead"), fixture.actions.map { it.text("kind") })
    }

    @Test fun replayAfterReconnectRetainsReadyAcrossNormalTaskInvalidations() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), initiallyReady = false)
        fixture.replayEvents = listOf(artifactWire(fixture.ready, 1),
            artifactWire(fixture.ready.updated("kind" to JsonPrimitive("invalidated")), 2),
            driverWire("turnFinished", buildJsonObject { put("success", true) }, 3))
        fixture.sequence = 3
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(1, fixture.replayRequests)
    }

    @Test fun aCompletedCursorWithReadyDeletedBy220RecoversTheOriginalAnnouncement() = runBlocking {
        val oldCheckpoint = WakuControlCheckpoint(WakuReplayPosition(runtime, epoch, 3), taskReady = null)
        val fixture = Fixture(temporary.newFolder(), initiallyReady = false, cachedCheckpoint = oldCheckpoint)
        fixture.replayEvents = listOf(artifactWire(fixture.ready, 1),
            artifactWire(fixture.ready.updated("kind" to JsonPrimitive("invalidated")), 2),
            driverWire("turnFinished", buildJsonObject { put("success", true) }, 3))
        fixture.sequence = 3
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(listOf(3L, 0L), fixture.replayedAfter)
        assertEquals(fixture.replayEvents.first(), fixture.savedCheckpoints.last().taskReady)
    }

    @Test fun invalidationDuringAReadStillAllowsItsCorrelatedReply() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), invalidateBeforeReply = true)
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(0, fixture.replayRequests)
    }

    @Test fun aConversationWithNoArtifactsCompletesTheReadNormally() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), noArtifacts = true)
        fixture.helper.onWireEvent(artifactWire(fixture.ready.updated("kind" to JsonPrimitive("invalidated")), ++fixture.sequence))
        assertTrue(fixture.helper.loadArtifacts(session, runtime).isEmpty())
        assertEquals(listOf("list", "artifactList"), fixture.actions.map { it.text("kind") })
        assertEquals(0, fixture.replayRequests)
    }

    @Test fun processExitEndsReadsAndOldReadyCannotRestoreThatRuntime() = runBlocking {
        val fixture = Fixture(temporary.newFolder())
        fixture.helper.onWireEvent(driverWire("processExited", JsonNull, ++fixture.sequence))
        fixture.helper.onAttached(session, WakuRuntimeAttachment(runtime, true, fixture.ready, null))
        fixture.helper.onWireEvent(artifactWire(fixture.ready, 1), replayed = true)
        val failure = runCatching { fixture.helper.loadArtifacts(session, runtime) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("运行时已结束"))
        assertTrue(fixture.actions.isEmpty())
        assertEquals(0, fixture.replayRequests)
    }

    private fun driverWire(kind: String, payload: JsonElement, sequence: Long) = buildJsonObject {
        put("type", "event"); put("sessionId", session); put("runtimeId", runtime); put("epoch", epoch); put("sequence", sequence)
        put("event", buildJsonObject { put("kind", kind); put("payload", payload) })
    }

    private fun artifactWire(payload: JsonObject, sequence: Long) = buildJsonObject {
        put("type", "event"); put("sessionId", session); put("runtimeId", runtime); put("epoch", epoch); put("sequence", sequence)
        put("event", buildJsonObject { put("kind", "deerflowTasks"); put("payload", payload) })
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
        assertEquals(1, fixture.actions.size)
    }

    @Test fun lostTransportAckUsesCorrelatedReplyWithoutDuplicateReads() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), loseAck = true)
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(5, fixture.actions.size)
        assertEquals(listOf(0L, 4L, 8L), fixture.actions.filter { it.text("kind") == "artifactRead" }.map { it.long("offset") })
    }

    @Test fun timedOutChunkRetriesSamePinnedOffsetAndDoesNotRedownloadPrefix() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), dropOffsetOnce = 4)
        val file = fixture.helper.loadArtifacts(session, runtime).single()
        assertArrayEquals(fixture.bytes, File(java.net.URI(file.url)).readBytes())
        val reads = fixture.actions.filter { it.text("kind") == "artifactRead" }
        assertEquals(listOf(0L, 4L, 4L, 8L), reads.map { it.long("offset") })
        assertTrue(reads.drop(1).all { it.text("expectedSha256") == fixture.hash })
        assertEquals(fixture.commandIds.size, fixture.commandIds.toSet().size)
    }

    @Test fun unknownDeliveryOfReplayReadRetriesWithoutStartingProviderWork() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), initiallyReady = false, loseReplayOnce = true)
        assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
        assertEquals(2, fixture.replayRequests)
        assertTrue(fixture.actions.all { it.text("kind") in setOf("list", "artifactList", "artifactRead") })
    }

    @Test fun explicitFailureDoesNotRetryAndCompletedFilesRemainAvailable() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), failSecondArtifact = true)
        val completed = mutableListOf<com.deerflow.app.domain.model.AgentArtifact>()
        val result = runCatching { fixture.helper.loadArtifacts(session, runtime, completed::add) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("明确拒绝"))
        assertEquals(1, completed.size)
        assertArrayEquals(fixture.bytes, File(java.net.URI(completed.single().url)).readBytes())
        assertEquals(1, fixture.actions.count { it.text("kind") == "artifactRead" && it.text("artifactId") == "asset-2" })
    }

    @Test fun losingForegroundPriorityCancelsReadWithoutRetryOrErrorConversion() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), cancelBeforeOffset = 4)
        val error = runCatching { fixture.helper.loadArtifacts(session, runtime) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, fixture.actions.count { it.text("kind") == "artifactRead" })
        assertTrue(File(fixture.directory, "waku-artifacts").listFiles().orEmpty().isEmpty())
    }

    @Test fun onlyExplicitBusyAndTimeoutCodesAllowRetryOfReadOnlyControls() = runBlocking {
        for (code in listOf("busy", "timeout")) {
            val fixture = Fixture(temporary.newFolder(), failureCode = code)
            assertEquals(1, fixture.helper.loadArtifacts(session, runtime).size)
            assertEquals(2, fixture.actions.count { it.text("kind") == "list" })
        }
        for (code in listOf("invalid_request", "invalid_response", "connection_closed", "-32603")) {
            val fixture = Fixture(temporary.newFolder(), failureCode = code)
            assertTrue(runCatching { fixture.helper.loadArtifacts(session, runtime) }.isFailure)
            assertEquals(1, fixture.actions.size)
        }
    }

    @Test fun busyRetryIsBoundedAndReportsFailure() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), failureCode = "busy", failuresRemaining = 20)
        val error = runCatching { fixture.helper.loadArtifacts(session, runtime) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("繁忙"))
        assertTrue(error?.message.orEmpty().contains("3 次"))
        assertEquals(3, fixture.actions.size)
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
        val loseAck: Boolean = false,
        val dropOffsetOnce: Long? = null,
        val loseReplayOnce: Boolean = false,
        val failSecondArtifact: Boolean = false,
        val cancelBeforeOffset: Long? = null,
        val failureCode: String? = null,
        var failuresRemaining: Int = 1,
        val noArtifacts: Boolean = false,
        val invalidateBeforeReply: Boolean = false,
        val cachedCheckpoint: WakuControlCheckpoint? = null,
    ) {
        val bytes = "hello world".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val actions = mutableListOf<JsonObject>()
        val commandIds = mutableListOf<String>()
        private var droppedOffset = false
        val admitted = kotlinx.coroutines.CompletableDeferred<Unit>()
        var replayRequests = 0
        val replayedAfter = mutableListOf<Long>()
        val savedCheckpoints = mutableListOf<WakuControlCheckpoint>()
        var sequence = 1L
        val ready = objectOf("kind" to JsonPrimitive("ready"), "connectionId" to JsonPrimitive("connection-1"), "sessionId" to JsonPrimitive("acp-1"),
            "capabilities" to objectOf("tree" to JsonPrimitive(true), "artifactList" to JsonPrimitive(true), "artifactRead" to JsonPrimitive(true)))
        var replayEvents = listOf(artifactWire(ready, 1))
        val metadata = objectOf("id" to JsonPrimitive("asset-1"), "name" to JsonPrimitive("../../secret.txt"), "mimeType" to JsonPrimitive("text/plain"), "size" to JsonPrimitive(bytes.size))
        val helper = WakuTaskArtifacts(directory, maxFileBytes = maxFileBytes, maxTotalBytes = maxOf(2048, maxFileBytes), chunkBytes = 4,
            requestTimeoutMs = 300, retryDelayMs = 5,
            readCheckpoint = { _, _ -> cachedCheckpoint },
            saveCheckpoint = { _, checkpoint -> savedCheckpoints += checkpoint },
            beforeRead = {
                if (cancelBeforeOffset != null && actions.any { it.text("kind") == "artifactRead" && it.long("offset") + 4 >= cancelBeforeOffset }) {
                    throw CancellationException("会话已切换")
                }
            },
        ) { command, sid, rid ->
            assertEquals(session, sid); assertEquals(runtime, rid)
            if (command.text("type") == "replayEvents") {
                replayRequests++
                replayedAfter += command.long("afterSequence")
                if (loseReplayOnce && replayRequests == 1) throw WakuRequestException(WakuDelivery.RESULT_UNKNOWN, "replay-read", "generic unknown result")
                val through = replayEvents.last().long("sequence")
                objectOf("type" to JsonPrimitive("replayEvents"), "epoch" to JsonPrimitive(epoch), "throughSequence" to JsonPrimitive(through),
                    "nextSequence" to JsonPrimitive(through), "hasMore" to JsonPrimitive(false), "events" to JsonArray(replayEvents.filter { it.long("sequence") > command.long("afterSequence") }))
            } else {
                assertEquals("deerFlowTasks", command.text("type"))
                val control = command["command"]!!.jsonObject
                assertEquals("connection-1", control.text("connectionId"))
                assertEquals("acp-1", control.text("sessionId"))
                val action = control["action"]!!.jsonObject
                commandIds += control.text("requestId")!!
                actions += action
                admitted.complete(Unit)
                val drop = action.text("kind") == "artifactRead" && dropOffsetOnce == action.long("offset") && !droppedOffset
                if (drop) droppedOffset = true
                if (answer && !drop) answerControl(control, action)
                if (loseAck) awaitCancellation()
                objectOf("type" to JsonPrimitive("ack"))
            }
        }

        init { if (initiallyReady) helper.onWireEvent(wire(ready, 1)) }

        private fun answerControl(command: JsonObject, action: JsonObject) {
            if (invalidateBeforeReply) helper.onWireEvent(wire(ready.updated("kind" to JsonPrimitive("invalidated")), ++sequence))
            if (failureCode != null && failuresRemaining-- > 0) {
                helper.onWireEvent(wire(objectOf("kind" to JsonPrimitive("failed"), "command" to command,
                    "code" to JsonPrimitive(failureCode), "message" to JsonPrimitive("synthetic failure")), ++sequence))
                return
            }
            if (failSecondArtifact && action.text("kind") == "artifactRead" && action.text("artifactId") == "asset-2") {
                helper.onWireEvent(wire(objectOf("kind" to JsonPrimitive("failed"), "command" to command, "message" to JsonPrimitive("明确拒绝读取")), ++sequence))
                return
            }
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
                        "hasMore" to JsonPrimitive(false), "artifacts" to JsonArray(if (noArtifacts) emptyList() else if (failSecondArtifact) listOf(metadata,
                            JsonObject(metadata.toMutableMap().apply { put("id", JsonPrimitive("asset-2")) })) else listOf(metadata)))
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
