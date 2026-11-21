package com.deerflow.app.ui.chat

import com.deerflow.app.data.waku.*
import com.deerflow.app.domain.BlockKind
import java.io.File
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneratedImageHistoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val reference = "waku-blob:926dbfd237ce74b8.png"

    @Test fun streamedImagesKeepTheirOrderAndRecoverFromDurableHistoryWithAnEmptyImageCache() = runBlocking {
        val root = temporary.newFolder()
        var snapshot = WakuProjection.bindRuntime(WakuProjection.fromSession(WakuProjection.createSession("session", "project")), "runtime")
        var sequence = 0L
        fun event(kind: String, payload: JsonElement) {
            snapshot = WakuProjection.reduce(snapshot, buildJsonObject {
                put("sessionId", "session"); put("runtimeId", "runtime"); put("epoch", "epoch"); put("sequence", ++sequence); put("replayable", true)
                put("event", buildJsonObject { put("kind", kind); put("payload", payload) })
            })
        }
        event("promptSubmitted", buildJsonObject { put("turnId", "turn"); put("messageId", "user"); put("message", "生成图片") })
        event("turnStarted", JsonNull)
        for (delta in listOf("第一张", "\n\n![](", reference, ")\n\n", "第二张", "\n\n![预览](<$reference>)\n\n", "完成")) {
            event("textDelta", JsonPrimitive(delta))
        }
        event("turnFinished", buildJsonObject { put("success", true) })
        val live = WakuProjection.display(snapshot).blocks.single { it.kind == BlockKind.ASSISTANT }
        assertEquals(listOf(MarkdownBlock.Paragraph("第一张"), MarkdownBlock.ImageBlock("", reference),
            MarkdownBlock.Paragraph("第二张"), MarkdownBlock.ImageBlock("预览", reference), MarkdownBlock.Paragraph("完成")), parseBlocks(live.content))
        assertTrue(WakuProjection.containsImage(snapshot, live.messageId, reference))
        assertFalse(WakuProjection.containsImage(snapshot, "user", reference))
        assertFalse(WakuProjection.containsImage(snapshot, live.messageId, "waku-blob:other.png"))

        WakuSessionStore(root, "ws://fixture/v1", "fixture-token").save(snapshot)
        val restored = WakuSessionStore(root, "ws://fixture/v1", "fixture-token").load("session")!!
        val hydrated = WakuProjection.fromSession(restored.session)
        for (history in listOf(restored, hydrated)) {
            val block = WakuProjection.display(history).blocks.single { it.kind == BlockKind.ASSISTANT }
            assertEquals(live.content, block.content)
            assertEquals(parseBlocks(live.content), parseBlocks(block.content))
            assertTrue(WakuProjection.containsImage(history, block.messageId, reference))
            val cache = WakuImageCache(temporary.newFolder())
            val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV1sAAAAASUVORK5CYII=")
            val image = cache.resolve((parseBlocks(block.content)[1] as MarkdownBlock.ImageBlock).url) {
                buildJsonObject { put("type", "blobData"); put("bytes", Base64.getEncoder().encodeToString(png)) }
            }
            assertArrayEquals(png, File(URI(image.url)).readBytes())
        }
    }

    @Test fun incompleteImageChunksAndCodeExamplesDoNotBecomeImageRequests() {
        for (text in listOf("![]($reference", "```markdown\n![]($reference)\n```")) {
            assertTrue(parseBlocks(text).none { it is MarkdownBlock.ImageBlock })
        }
        assertEquals(listOf(MarkdownBlock.ImageBlock("existing", "https://example.test/image.png")),
            parseBlocks("![existing](https://example.test/image.png)"))
    }
}
