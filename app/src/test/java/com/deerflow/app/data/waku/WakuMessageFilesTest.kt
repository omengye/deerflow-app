package com.deerflow.app.data.waku

import com.deerflow.app.domain.BlockKind
import com.deerflow.app.domain.model.MessageFile
import com.deerflow.app.domain.model.S3Object
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakuMessageFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "10000000-0000-0000-0000-000000000001"
    private val file = MessageFile("f", "图片.png", "image/png", 3, serverReference = "waku-blob:abc", serverPath = "/tmp/image.png", sha256 = "a".repeat(64))
    private fun session() = WakuProjection.createSession(id, "p")
    private fun remote() = session().updated("messages" to buildJsonArray {
        add(buildJsonObject { put("id", "m"); put("role", "user"); put("content", "raw prompt and manifest") })
        add(buildJsonObject { put("id", "old"); put("role", "user"); put("content", "same text") })
    })

    @Test fun attachmentsSurviveServerEchoHydrationAndAppRestartByMessageId() {
        val pending = buildJsonObject { put("kind", "prompt"); put("messageId", "m") }
        val local = WakuMessageFiles.remember(WakuProjection.fromSession(session()), "m", "same text", listOf(file)).copy(pendingCommands = listOf(pending))
        val hydrated = WakuRecovery.resolveObservedCommands(WakuRecovery.chooseSnapshot(local, remote()))
        assertTrue(hydrated.pendingCommands.isEmpty())
        val store = WakuSessionStore(temporary.newFolder(), "wss://server/v1", "fake-token")
        store.save(hydrated)
        val display = WakuProjection.display(store.load(id)!!)
        val users = display.blocks.filter { it.kind == BlockKind.USER }
        assertEquals("same text", users.first().content)
        assertEquals(listOf(file), users.first().attachments)
        assertTrue(users.last().attachments.isEmpty())
        assertEquals("waku-blob:abc", hydrated.session.array("messages").first().jsonObject.array("attachments").first().jsonObject.string("blob_reference"))
    }

    @Test fun ossManifestStaysOutOfMessageBubbleAndDoesNotForgeNativeAttachments() {
        val remoteFile = file.copy(oss = S3Object("https://s3.cstcloud.cn", "bucket", "key", file.name, file.mimeType, 3, file.sha256))
        val local = WakuMessageFiles.remember(WakuProjection.fromSession(remote()), "m", "看看图片", listOf(remoteFile))
        val materialized = WakuMessageFiles.materialize(local)
        assertTrue(materialized.session.array("messages").first().jsonObject.array("attachments").isEmpty())
        assertEquals("看看图片", WakuProjection.display(materialized).blocks.first { it.kind == BlockKind.USER }.content)
    }

    @Test fun rewindDoesNotResurrectRemovedMessageAttachments() {
        val local = WakuMessageFiles.remember(WakuProjection.fromSession(remote()), "m", "same text", listOf(file))
        val chosen = WakuRecovery.chooseSnapshot(local, session().updated("history_revision" to JsonPrimitive(1)))
        assertTrue(WakuProjection.display(chosen).blocks.none { it.attachments.isNotEmpty() })
    }

    @Test fun downloadedArtifactIndexSurvivesRuntimeChangeButNotHistoryRewind() {
        val artifact = com.deerflow.app.domain.model.AgentArtifact("waku:root/node/file", "report.txt", "file:/cache/report.txt")
        val local = WakuProjection.fromSession(session()).copy(artifactFiles = listOf(artifact))
        val restarted = session().updated("runtime_event_cursor" to buildJsonObject {
            put("runtime_id", "new-runtime"); put("epoch", "new-epoch"); put("sequence", 1)
        })
        assertEquals(listOf(artifact), WakuRecovery.chooseSnapshot(local, restarted).artifactFiles)
        assertTrue(WakuRecovery.chooseSnapshot(local, restarted.updated("history_revision" to JsonPrimitive(1))).artifactFiles.isEmpty())
    }

    @Test fun ossEnvelopeNeverBecomesTheConversationTitleEvenWithNoTypedText() {
        val remoteFile = file.copy(oss = S3Object("https://s3.cstcloud.cn", "bucket", "key", file.name, file.mimeType, 3, file.sha256))
        for (text in listOf("看看图片", "")) {
            val local = WakuMessageFiles.remember(WakuProjection.fromSession(session()), "m", text, listOf(remoteFile))
            val wire = buildJsonObject {
                put("sessionId", id); put("runtimeId", "runtime"); put("epoch", "epoch"); put("sequence", 1)
                put("event", buildJsonObject { put("kind", "promptSubmitted"); put("payload", buildJsonObject {
                    put("messageId", "m"); put("turnId", "t"); put("message", "$text\n[DEERFLOW_OSS_ATTACHMENTS_V1]\n{technical manifest}")
                }) })
            }
            val projected = WakuProjection.reduce(local, wire)
            assertEquals(text.ifBlank { file.name }, WakuSessionTitles.display(projected.session))
            assertEquals(text, WakuProjection.display(projected).blocks.first { it.kind == BlockKind.USER }.content)
        }
    }
}
