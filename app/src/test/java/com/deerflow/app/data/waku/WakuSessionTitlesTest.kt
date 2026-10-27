package com.deerflow.app.data.waku

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WakuSessionTitlesTest {
    private fun session(title: String = "New task", automatic: String? = null, vararg messages: JsonObject) =
        WakuProjection.createSession("session", "project", nowMillis = 1_000).updated(
            "title" to JsonPrimitive(title), "auto_title" to (automatic?.let(::JsonPrimitive) ?: JsonNull),
            "messages" to JsonArray(messages.toList()),
        )

    private fun message(content: String, visible: String? = null, attachment: String? = null) = buildJsonObject {
        put("role", "user"); put("content", content)
        visible?.let { put("display_content", it) }
        attachment?.let { name -> put("attachments", buildJsonArray { add(buildJsonObject { put("name", name) }) }) }
    }

    private fun cursor(sequence: Int, epoch: String = "epoch") = buildJsonObject {
        put("runtime_id", "runtime"); put("epoch", epoch); put("sequence", sequence)
    }

    @Test fun explicitUserTitleWinsOverProviderAndPromptAndIsNotRewritten() {
        val value = session("  我的标题  ", "Provider title", message("First prompt"))
        assertEquals("我的标题", WakuSessionTitles.display(value))
        assertSame(value, WakuSessionTitles.withFallback(value))
    }

    @Test fun blankOrAbsentExplicitTitleStillShowsGeneratedName() {
        assertEquals("服务端标题", WakuSessionTitles.display(session("   ", " 服务端标题 ")))
        assertEquals("服务端标题", WakuSessionTitles.display(JsonObject(mapOf("auto_title" to JsonPrimitive("服务端标题")))))
    }

    @Test fun blankGeneratedTitleFallsBackToHistoryWhileEmptyDraftRemainsUnnamed() {
        assertEquals("首条问题", WakuSessionTitles.display(session(automatic = "  ", messages = arrayOf(message("首条问题")))))
        val draft = session()
        assertEquals("New task", WakuSessionTitles.display(draft))
        assertSame(draft, WakuSessionTitles.withFallback(draft))
    }

    @Test fun visibleContentWinsOverProviderAttachmentWrapper() {
        val value = session(messages = arrayOf(message("INTERNAL_WRAPPER /private/upload.txt", "分析这份报告", "report.txt")))
        assertEquals("分析这份报告", WakuSessionTitles.display(value))
        val onlyFile = session(messages = arrayOf(message("INTERNAL_WRAPPER /private/upload.txt", "", "报告.pdf")))
        assertEquals("报告.pdf", WakuSessionTitles.display(onlyFile))
    }

    @Test fun titleMatchesDesktopSevenWordRuleAndNormalizesUnicodeWhitespace() {
        val value = session(messages = arrayOf(message("  one\ntwo\tthree\u3000four five six seven eight  ")))
        assertEquals("one two three four five six seven", WakuSessionTitles.display(value))
    }

    @Test fun chineseAndEmojiTitlesUseCodePointsRatherThanUtf16Units() {
        val value = session(messages = arrayOf(message("中".repeat(52) + "😀" + "文".repeat(8))))
        val title = WakuSessionTitles.display(value)
        assertEquals("中".repeat(52) + "😀…", title)
        assertEquals(54, title.codePointCount(0, title.length))
    }

    @Test fun catalogRenameChangesOnlyMetadataAndCannotEraseTranscriptOrCursor() {
        val local = session(automatic = "生成的标题", messages = arrayOf(message("历史正文"))).updated(
            "runtime_event_cursor" to cursor(20), "transcript_blocks" to buildJsonArray { add("preserve") },
        )
        val summary = session("桌面改名", "过期自动标题").updated("runtime_event_cursor" to JsonNull)
        val merged = WakuSessionTitles.mergeMetadata(local, summary)
        assertEquals("桌面改名", WakuSessionTitles.display(merged))
        assertEquals(local["auto_title"], merged["auto_title"])
        assertEquals(local["messages"], merged["messages"])
        assertEquals(local["transcript_blocks"], merged["transcript_blocks"])
        assertEquals(local["runtime_event_cursor"], merged["runtime_event_cursor"])
        assertEquals(local["updated_at"], merged["updated_at"])
    }

    @Test fun oldUnnamedCachedRowCanRecoverTitleFromRemoteHistoryWithoutReplacingItsTranscript() {
        val local = session()
        val remote = session(messages = arrayOf(message("从旧会话历史恢复标题")))
        val merged = WakuSessionTitles.mergeMetadata(local, remote, fullSnapshot = true)
        assertEquals("从旧会话历史恢复标题", WakuSessionTitles.display(merged))
        assertEquals(local["messages"], merged["messages"])
        assertEquals(local["runtime_event_cursor"], merged["runtime_event_cursor"])
    }

    @Test fun olderFullSnapshotCannotRevertANewerRuntimeTitle() {
        val local = session(automatic = "新事件标题").updated("runtime_event_cursor" to cursor(20))
        val remote = session(automatic = "旧标题").updated("runtime_event_cursor" to cursor(19))
        assertEquals("新事件标题", WakuSessionTitles.display(WakuSessionTitles.mergeMetadata(local, remote, true)))
        assertEquals(local["auto_title"], WakuRecovery.chooseSnapshot(WakuProjection.fromSession(local), remote).session["auto_title"])
    }

    @Test fun equalOrNewerFullSnapshotUpdatesAutomaticTitleWithoutReplacingLocalHistory() {
        val local = session(automatic = "缓存旧标题", messages = arrayOf(message("完整正文"))).updated("runtime_event_cursor" to cursor(20))
        val remote = session(automatic = "最新标题").updated("runtime_event_cursor" to cursor(20))
        val merged = WakuSessionTitles.mergeMetadata(local, remote, true)
        assertEquals("最新标题", WakuSessionTitles.display(merged))
        assertEquals(local["messages"], merged["messages"])
        assertEquals(local["runtime_event_cursor"], merged["runtime_event_cursor"])
    }

    @Test fun restartedRuntimeCanSupplyTitleWithLowerSequence() {
        val local = session(automatic = "旧进程标题").updated("runtime_event_cursor" to cursor(99))
        val remote = session(automatic = "恢复后的标题").updated("runtime_event_cursor" to cursor(1, "new-epoch"))
        assertEquals("恢复后的标题", WakuSessionTitles.display(WakuSessionTitles.mergeMetadata(local, remote, true)))
    }

    @Test fun unnamedUncachedRowsAndChangedGeneratedTitlesRequestReadOnlyDetails() {
        assertTrue(WakuSessionTitles.needsDetails(null, session()))
        assertFalse(WakuSessionTitles.needsDetails(null, session("已有标题")))
        assertFalse(WakuSessionTitles.needsDetails(null, session(automatic = "已有自动标题")))
        assertTrue(WakuSessionTitles.needsDetails(session(automatic = "旧标题"), session(automatic = "新标题")))
        assertFalse(WakuSessionTitles.needsDetails(session(automatic = "同一标题"), session(automatic = "同一标题")))
    }
}
