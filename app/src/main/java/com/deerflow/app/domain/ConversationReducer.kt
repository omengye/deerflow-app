package com.deerflow.app.domain

import com.deerflow.app.data.agui.EventParser
import com.deerflow.app.data.agui.nestedStr
import com.deerflow.app.data.agui.str
import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.AguiEvent
import com.deerflow.app.domain.model.ChatMessage
import com.deerflow.app.domain.model.Interrupt
import com.deerflow.app.domain.model.Roles
import com.deerflow.app.domain.model.ToolCall
import com.deerflow.app.domain.model.ToolFunction
import com.deerflow.app.domain.model.asMessageText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Pure event-to-state reducer. Kotlin port of tui.handleEvent + supporting
 * methods. Given the current [ConversationState] and an [AguiEvent], returns
 * the next state. No I/O, no Android - directly unit-testable.
 */
object ConversationReducer {

    fun reduce(s: ConversationState, e: AguiEvent): ConversationState {
        val type = e.type.trim().ifEmpty { "RAW" }
        return when (type) {
            "RUN_STARTED" ->
                s.copy(running = true, status = "Running")

            "RUN_FINISHED" -> {
                val finalized = s.finalizeRun()
                val interrupts = EventParser.interruptsFromRunFinished(e)
                if (interrupts.isNotEmpty()) {
                    finalized.copy(
                        running = false,
                        status = "Requires interrupt response",
                        interrupts = interrupts,
                    ).upsertInterruptBlock(interrupts)
                } else {
                    finalized.copy(running = false, status = "Idle")
                }
            }

            "RUN_CANCELLED" ->
                s.finalizeRun().copy(running = false, status = "Cancelled")

            "RUN_ERROR" ->
                s.finalizeRun().copy(running = false, status = "Run error")
                    .appendSystem(BlockKind.ERROR, "[RUN_ERROR]", e.raw.str("message").orEmpty())

            // -- text ----------------------------------------------------------
            "TEXT_MESSAGE_START" -> {
                val (s1, id) = s.resolveTextId(e.messageId, start = true)
                if (s1.shouldIgnoreTextStart(id)) s1.copy(replay = s1.replay.ignore(id))
                else s1.recordAgentName(id, e)
                    .ensureText(id)
                    .upsert(textKey(id), BlockKind.ASSISTANT, s1.textHeader(id), "")
            }

            "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CHUNK" -> {
                val (s1, id) = s.resolveTextId(e.messageId, start = false)
                if (id in s1.replay.ignoredTextIds) return s1
                val s2 = s1.recordAgentName(id, e)
                val delta = e.textDelta()
                if (delta.isEmpty()) return s2
                val buf = appendBounded(s2.textBuffers[id].orEmpty(), delta, MAX_ASSISTANT_TEXT_CHARS)
                val visible = s2.filterVisibleAssistantText(buf)
                s2.copy(textBuffers = s2.textBuffers + (id to buf))
                    .let { state ->
                        if (visible.isEmpty()) state.removeBlock(textKey(id))
                        else state.upsert(textKey(id), BlockKind.ASSISTANT, s2.textHeader(id), visible)
                    }
            }

            "TEXT_MESSAGE_END" -> {
                val (s1, id) = s.resolveTextId(e.messageId, start = false)
                if (id in s1.replay.ignoredTextIds) {
                    return s1.copy(
                        textBuffers = s1.textBuffers - id,
                        textAgentNames = s1.textAgentNames - id,
                        replay = s1.replay.unignore(id),
                    ).removeBlock(textKey(id)).clearActiveText(id)
                }
                val s2 = s1.recordAgentName(id, e)
                val filtered = s2.filterVisibleAssistantText(s2.textBuffers[id].orEmpty()).trim()
                if (filtered.isEmpty()) {
                    s2.copy(textBuffers = s2.textBuffers - id, textAgentNames = s2.textAgentNames - id)
                        .removeBlock(textKey(id)).clearActiveText(id)
                } else {
                    s2.upsert(textKey(id), BlockKind.ASSISTANT, s2.textHeader(id), filtered)
                        .copy(
                            history = s2.history + ChatMessage(
                                role = Roles.ASSISTANT,
                                content = kotlinx.serialization.json.JsonPrimitive(filtered),
                                id = id,
                                name = s2.textAgentNames[id],
                            ),
                            textBuffers = s2.textBuffers - id,
                            textAgentNames = s2.textAgentNames - id,
                            replay = s2.replay.markCompleted(id),
                        ).clearActiveText(id)
                }
            }

            // -- thinking ------------------------------------------------------
            "THINKING_START", "THINKING_TEXT_MESSAGE_START" ->
                s.copy(thinking = "").upsert(THINKING_KEY, BlockKind.THINKING, "[THINKING]", e.raw.str("title").orEmpty())

            "THINKING_TEXT_MESSAGE_CONTENT" -> {
                val buf = appendBounded(s.thinking.orEmpty(), e.textDelta(), MAX_REASONING_TEXT_CHARS)
                s.copy(thinking = buf).upsert(THINKING_KEY, BlockKind.THINKING, "[THINKING]", buf)
            }

            "THINKING_TEXT_MESSAGE_END", "THINKING_END" -> {
                val t = s.thinking?.trim().orEmpty()
                if (t.isEmpty()) s.copy(thinking = null).removeBlock(THINKING_KEY)
                else s.copy(thinking = null).upsert(THINKING_KEY, BlockKind.THINKING, "[THINKING]", t)
            }

            // -- reasoning -----------------------------------------------------
            "REASONING_START", "REASONING_MESSAGE_START" -> {
                val (s1, id) = s.resolveReasonId(e.messageId, start = true)
                s1.copy(reasoningBuffers = s1.reasoningBuffers + (id to (s1.reasoningBuffers[id].orEmpty())))
                    .upsert(reasonKey(id), BlockKind.REASONING, header("REASONING", id), "")
            }

            "REASONING_MESSAGE_CONTENT" -> {
                val (s1, id) = s.resolveReasonId(e.messageId, start = false)
                val buf = appendBounded(s1.reasoningBuffers[id].orEmpty(), e.textDelta(), MAX_REASONING_TEXT_CHARS)
                s1.copy(reasoningBuffers = s1.reasoningBuffers + (id to buf))
                    .upsert(reasonKey(id), BlockKind.REASONING, header("REASONING", id), buf)
            }

            "REASONING_MESSAGE_END", "REASONING_END" -> {
                val (s1, id) = s.resolveReasonId(e.messageId, start = false)
                val reasoning = s1.replay.filter(s1.reasoningBuffers[id].orEmpty()).trim()
                val s2 = s1.copy(reasoningBuffers = s1.reasoningBuffers - id).clearActiveReason(id)
                if (reasoning.isEmpty()) s2.removeBlock(reasonKey(id))
                else s2.upsert(reasonKey(id), BlockKind.REASONING, header("REASONING", id), reasoning)
            }

            // -- tool calls ----------------------------------------------------
            "TOOL_CALL_START" -> {
                val (s1, id) = s.resolveToolId(e.raw.str("toolCallId"))
                val name = e.raw.str("toolCallName").orEmpty()
                val buf = ToolBuffer(id = id, name = name)
                s1.copy(toolBuffers = s1.toolBuffers + (id to buf))
                    .upsert(toolKey(id), BlockKind.TOOL, header("TOOL_CALL", id), name.trim())
                    .recordToolCallStart(id, name)
            }

            "TOOL_CALL_ARGS", "TOOL_CALL_CHUNK" -> {
                // Tool arguments can arrive as many large chunks, especially for image/file tools.
                // Keep the UI responsive by not emitting state for every argument chunk.
                s
            }

            "TOOL_CALL_END" -> {
                val (s1, id) = s.resolveToolId(e.raw.str("toolCallId"))
                val buf = (s1.toolBuffers[id] ?: ToolBuffer(id = id)).copy(ended = true)
                s1.copy(toolBuffers = s1.toolBuffers + (id to buf))
                    .upsert(toolKey(id), BlockKind.TOOL, header("TOOL_CALL", id), formatTool(buf))
            }

            "TOOL_CALL_RESULT" -> {
                val (s1, id) = s.resolveToolId(e.raw.str("toolCallId"))
                val content = e.content?.takeIf { it.isNotEmpty() }
                    ?: e.raw.str("result")?.takeIf { it.isNotEmpty() }
                    ?: e.raw.str("output")?.takeIf { it.isNotEmpty() }
                    ?: summarizeToolResult(e.raw)
                val contentForState = truncateToolDisplay(content, MAX_TOOL_RESULT_DISPLAY_CHARS)
                val role = e.raw.str("role")
                val isError = (e.raw.str("isError") == "true") || (role != null && role != "tool" && role.isNotEmpty())
                val buf = (s1.toolBuffers[id] ?: ToolBuffer(id = id, name = "tool"))
                    .copy(result = contentForState, isError = isError)
                s1.copy(toolBuffers = s1.toolBuffers + (id to buf))
                    .upsert(toolKey(id), BlockKind.TOOL, header("TOOL_CALL", id), formatTool(buf))
                    .recordToolResult(id, contentForState, isError)
            }

            // -- history / silent ---------------------------------------------
            "MESSAGES_SNAPSHOT" -> s.importSnapshot(e.raw["messages"])

            "CUSTOM" -> {
                if (e.raw.str("name") == "deerflow.artifacts") {
                    s.appendArtifacts(parseArtifacts(e.raw))
                } else {
                    s
                }
            }

            // AG-UI protocol events that carry no user-visible content.
            // Silently absorb them so they don't appear as raw JSON in the chat.
            "STATE_SNAPSHOT", "STATE_DELTA",
            "SYSTEM",
            "MESSAGE_SNAPSHOT",                         // per-message snapshot (duplicate of streamed text)
            "STEP_STARTED", "STEP_FINISHED",            // agent step lifecycle
            "STEP_ERROR",                               // step-level error (RUN_ERROR already shown)
            "ACTION_STARTED", "ACTION_FINISHED",        // action lifecycle
            "ACTION_ERROR",                             // action-level error
            "METADATA",                                 // run/agent metadata
            "RAW_EVENT",                                // passthrough raw event wrapper
            "HEARTBEAT", "PING", "PONG",                // keep-alive / health
            "LOG", "DEBUG",                             // debugging / log messages
            -> s

            "RAW" -> s.appendSystem(BlockKind.SYSTEM, "[RAW] ${e.raw.str("source").orEmpty()}", summarizeRawEvent(e.raw))
            else -> s  // silently ignore any other unknown event types
        }
    }

    // -- run lifecycle -------------------------------------------------------

    /** Flush all in-flight buffers into final blocks/history. Port of finalizeRun. */
    fun ConversationState.finalizeRun(): ConversationState {
        var state = this
        // text
        for ((id, raw) in textBuffers) {
            val text = state.filterVisibleAssistantText(raw).trim()
            state = if (text.isEmpty()) {
                state.removeBlock(textKey(id))
            } else {
                state.upsert(textKey(id), BlockKind.ASSISTANT, state.textHeader(id), text).copy(
                    history = state.history + ChatMessage(
                        role = Roles.ASSISTANT,
                        content = kotlinx.serialization.json.JsonPrimitive(text),
                        id = id,
                        name = state.textAgentNames[id],
                    ),
                )
            }
        }
        state = state.copy(textBuffers = emptyMap(), textAgentNames = emptyMap(), activeTextId = null)

        // reasoning
        for ((id, raw) in state.reasoningBuffers) {
            val reasoning = state.replay.filter(raw).trim()
            state = if (reasoning.isEmpty()) state.removeBlock(reasonKey(id))
            else state.upsert(reasonKey(id), BlockKind.REASONING, header("REASONING", id), reasoning)
        }
        state = state.copy(reasoningBuffers = emptyMap(), activeReasonId = null)

        // thinking
        val thinking = state.thinking?.trim().orEmpty()
        state = if (thinking.isEmpty()) state.copy(thinking = null).removeBlock(THINKING_KEY)
        else state.upsert(THINKING_KEY, BlockKind.THINKING, "[THINKING]", thinking).copy(thinking = thinking)

        return state.copy(toolBuffers = emptyMap())
    }

    // -- text/reason/tool id resolution -------------------------------------

    private fun ConversationState.resolveTextId(id: String?, start: Boolean): Pair<ConversationState, String> {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return (if (start) copy(activeTextId = trimmed) else this) to trimmed
        return if (start || activeTextId == null) {
            val next = "text-${textCounter + 1}"
            copy(textCounter = textCounter + 1, activeTextId = next) to next
        } else this to activeTextId!!
    }

    private fun ConversationState.resolveReasonId(id: String?, start: Boolean): Pair<ConversationState, String> {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return (if (start) copy(activeReasonId = trimmed) else this) to trimmed
        return if (start || activeReasonId == null) {
            val next = "reasoning-${reasonCounter + 1}"
            copy(reasonCounter = reasonCounter + 1, activeReasonId = next) to next
        } else this to activeReasonId!!
    }

    private fun ConversationState.resolveToolId(id: String?): Pair<ConversationState, String> {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return this to trimmed
        val next = "tool-${toolCounter + 1}"
        return copy(toolCounter = toolCounter + 1) to next
    }

    private fun ConversationState.clearActiveText(id: String) =
        if (activeTextId == id) copy(activeTextId = null) else this

    private fun ConversationState.clearActiveReason(id: String) =
        if (activeReasonId == id) copy(activeReasonId = null) else this

    private fun ConversationState.ensureText(id: String) =
        if (textBuffers.containsKey(id)) this else copy(textBuffers = textBuffers + (id to ""))

    private fun ConversationState.recordAgentName(id: String, e: AguiEvent): ConversationState {
        if (id.isEmpty()) return this
        val name = e.raw.nestedStr("raw_event", "name")?.trim().orEmpty()
        return if (name.isEmpty()) this else copy(textAgentNames = textAgentNames + (id to name))
    }

    private fun ConversationState.filterVisibleAssistantText(text: String): String {
        val replayFiltered = replay.filter(text)
        val normalized = ReplayState.normalize(replayFiltered)
        if (normalized.isEmpty()) return ""

        for (hist in currentRunAssistantTexts()) {
            if (hist == normalized || hist.startsWith(normalized)) return ""
            if (normalized.startsWith(hist)) {
                return normalized.substring(hist.length).trim()
            }
        }
        return replayFiltered
    }

    private fun ConversationState.currentRunAssistantTexts(): List<String> =
        history.mapNotNull { msg ->
            if (Roles.normalize(msg.role) != Roles.ASSISTANT) return@mapNotNull null
            val text = ReplayState.normalize(msg.content.asMessageText())
            text.takeIf { it.isNotEmpty() && it !in replay.historicalTexts }
        }

    private fun ConversationState.shouldIgnoreTextStart(id: String): Boolean {
        if (id.isEmpty()) return false
        if (ReplayState.normalizeHistoricalId(id) in replay.historicalIds) return true
        return id in replay.completedTextIds
    }

    private fun ConversationState.textHeader(id: String): String {
        val base = header("TEXT_MESSAGE", id)
        val agent = textAgentNames[id]?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ").orEmpty()
        return if (agent.isEmpty()) base else "$base agent: $agent"
    }

    // -- history sync --------------------------------------------------------

    private fun ConversationState.recordToolCallStart(toolId: String, name: String): ConversationState {
        if (toolId.isEmpty()) return this
        val args = toolBuffers[toolId]?.args?.trim()?.ifEmpty { "{}" } ?: "{}"
        val call = ToolCall(toolId, "function", ToolFunction(name.ifEmpty { "tool" }, args))
        val idx = history.indexOfLast { it.role == Roles.ASSISTANT }
        return if (idx >= 0) {
            val msg = history[idx]
            if (msg.toolCalls?.any { it.id == toolId } == true) this
            else copy(history = history.toMutableList().also {
                it[idx] = msg.copy(toolCalls = (msg.toolCalls.orEmpty()) + call)
            })
        } else {
            copy(history = history + ChatMessage(role = Roles.ASSISTANT, id = "$toolId:assistant", toolCalls = listOf(call)))
        }
    }

    private fun ConversationState.updateToolCallArgs(toolId: String, name: String, args: String): ConversationState {
        if (toolId.isEmpty()) return this
        val idx = history.indexOfLast { it.role == Roles.ASSISTANT && it.toolCalls?.any { c -> c.id == toolId } == true }
        if (idx < 0) return recordToolCallStart(toolId, name)
        val msg = history[idx]
        val calls = msg.toolCalls!!.map { c ->
            if (c.id == toolId) ToolCall(toolId, "function", ToolFunction(name.ifEmpty { c.function.name }, args)) else c
        }
        return copy(history = history.toMutableList().also { it[idx] = msg.copy(toolCalls = calls) })
    }

    private fun ConversationState.recordToolResult(toolId: String, content: String, isError: Boolean): ConversationState {
        val toolMessage = ChatMessage(
            role = Roles.TOOL,
            content = kotlinx.serialization.json.JsonPrimitive(content),
            toolCallId = toolId,
            error = if (isError) content else null,
        )
        return copy(history = history + toolMessage)
    }

    private fun ConversationState.importSnapshot(messages: Any?): ConversationState {
        val arr = messages as? JsonArray ?: return this
        var state = copy(blocks = emptyList(), systemCounter = 0)
        val parsed = mutableListOf<ChatMessage>()
        val assistantTextsInTurn = mutableSetOf<String>()
        for (raw in arr) {
            val obj = raw as? JsonObject ?: continue
            val role = Roles.normalize(obj.str("role"))
            val content = if (role == Roles.TOOL) {
                kotlinx.serialization.json.JsonPrimitive(
                    truncateToolDisplay(obj["content"].asMessageText(), MAX_TOOL_RESULT_DISPLAY_CHARS),
                )
            } else {
                obj["content"]
            }
            val msg = ChatMessage(
                role = role,
                content = content,
                id = obj.str("id"),
                name = obj.str("name"),
                toolCallId = obj.str("toolCallId") ?: obj.str("tool_call_id"),
                error = obj.str("error"),
            )
            if (role == Roles.USER) assistantTextsInTurn.clear()
            if (shouldSkipSnapshotMessage(assistantTextsInTurn, msg)) continue
            msg.assistantTextKey()?.let { assistantTextsInTurn.add(it) }
            parsed.add(msg)
            state = state.renderHistoryMessage(msg)
        }
        val mergedBlocks = mergeSnapshotBlocks(
            oldBlocks = blocks,
            renderedBlocks = state.blocks,
        )
        return state.copy(
            blocks = mergedBlocks,
            history = parsed,
        )
    }

    private fun DisplayBlock.isSnapshotIndependentBlock(): Boolean =
        !key.startsWith("sys:") && when (kind) {
            BlockKind.REASONING, BlockKind.THINKING, BlockKind.ARTIFACT -> true
            else -> false
        }

    private fun mergeSnapshotBlocks(
        oldBlocks: List<DisplayBlock>,
        renderedBlocks: List<DisplayBlock>,
    ): List<DisplayBlock> {
        if (oldBlocks.isEmpty()) return renderedBlocks

        val renderedByKey = renderedBlocks.associateBy { it.key }
        val usedRenderedKeys = mutableSetOf<String>()
        val merged = mutableListOf<DisplayBlock>()

        for (old in oldBlocks) {
            val rendered = renderedByKey[old.key]
            if (rendered != null) {
                merged.add(rendered)
                usedRenderedKeys.add(rendered.key)
            } else if (old.isSnapshotIndependentBlock()) {
                merged.add(old)
            }
        }

        renderedBlocks.forEach { rendered ->
            if (rendered.key !in usedRenderedKeys) merged.add(rendered)
        }

        return merged
    }

    private fun shouldSkipSnapshotMessage(
        assistantTextsInTurn: Set<String>,
        current: ChatMessage,
    ): Boolean =
        current.assistantTextKey()?.let { it in assistantTextsInTurn } == true

    private fun ChatMessage.assistantTextKey(): String? {
        if (Roles.normalize(role) != Roles.ASSISTANT) return null
        return ReplayState.normalize(content.asMessageText()).takeIf { it.isNotEmpty() }
    }

    private fun ConversationState.renderHistoryMessage(m: ChatMessage): ConversationState =
        when (Roles.normalize(m.role)) {
            Roles.USER -> appendSystem(BlockKind.USER, "You", UserDisplayText.clean(m.content.asMessageText()))
            Roles.ASSISTANT -> {
                var st = this
                val text = m.content.asMessageText()
                if (text.isNotEmpty()) {
                    val spk = m.name?.let { "Assistant ($it)" } ?: "Assistant"
                    st = st.appendSystem(BlockKind.ASSISTANT, spk, text)
                }
                m.toolCalls?.forEach { call ->
                    val buf = ToolBuffer(id = call.id, name = call.function.name, args = call.function.arguments)
                    st = st.upsert(toolKey(call.id), BlockKind.TOOL, header("TOOL_CALL", call.id), formatTool(buf))
                }
                st
            }
            Roles.TOOL -> appendSystem(
                BlockKind.TOOL,
                "TOOL #${shortId(m.toolCallId.orEmpty())}",
                truncateToolDisplay(m.content.asMessageText(), MAX_TOOL_RESULT_DISPLAY_CHARS),
            )
            Roles.REASONING -> appendSystem(BlockKind.REASONING, "Reasoning Output", m.content.asMessageText())
            else -> this
        }

    private fun ConversationState.upsertInterruptBlock(interrupts: List<Interrupt>): ConversationState {
        val lines = buildList {
            interrupts.forEach { i ->
                val parts = buildList {
                    add("${i.id} (${i.reason})")
                    i.message?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    i.toolCallId?.takeIf { it.isNotEmpty() }?.let { add("toolCall=$it") }
                }
                add(parts.joinToString(" - "))
            }
            add("Reply with text to resolve all interrupts, or a JSON array of {interruptId,status,payload}.")
        }
        return upsert(INTERRUPT_KEY, BlockKind.INTERRUPT, "[INTERRUPT]", lines.joinToString("\n"))
    }

    // -- formatting ----------------------------------------------------------

    private fun formatTool(buf: ToolBuffer): String {
        val parts = buildList {
            buf.name.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            buf.args.trim().takeIf { it.isNotEmpty() }?.let {
                add("args: ${truncateToolDisplay(it, MAX_TOOL_ARGS_DISPLAY_CHARS)}")
            }
            buf.result.trim().takeIf { it.isNotEmpty() }?.let {
                add("result: ${truncateToolDisplay(it, MAX_TOOL_RESULT_DISPLAY_CHARS)}")
                if (buf.isError) add("(error)")
            }
        }
        return parts.joinToString(" | ")
    }

    private fun appendBounded(current: String, delta: String, maxChars: Int): String {
        if (delta.isEmpty()) return current
        if (current.length >= maxChars) return current
        val remaining = maxChars - current.length
        return if (delta.length <= remaining) current + delta else current + delta.take(remaining)
    }

    private fun truncateToolDisplay(value: String, maxChars: Int): String {
        val clean = value.trim()
        if (clean.length <= maxChars) return clean
        val omitted = clean.length - maxChars
        return clean.take(maxChars) + "\n\n[truncated $omitted chars for display]"
    }

    private fun parseArtifacts(raw: JsonObject): List<AgentArtifact> {
        val value = raw["value"] as? JsonObject ?: return emptyList()
        val arr = value["artifacts"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val path = obj.str("path")?.trim().orEmpty()
            val url = obj.str("url")?.trim().orEmpty()
            if (path.isEmpty() || url.isEmpty()) return@mapNotNull null
            val name = obj.str("name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: path.substringAfterLast('/').ifEmpty { "artifact" }
            val mimeType = obj.str("mimeType") ?: obj.str("mime_type")
            val kind = obj.str("kind")?.lowercase()?.takeIf { it == "image" || it == "file" }
                ?: if (mimeType?.startsWith("image/") == true) "image" else "file"
            val size = obj.str("size")?.toLongOrNull()
            AgentArtifact(
                path = path,
                name = name,
                url = url,
                mimeType = mimeType,
                kind = kind,
                size = size,
            )
        }
    }

    private fun summarizeToolResult(raw: JsonObject): String {
        val keys = raw.keys.take(12).joinToString(", ")
        return if (keys.isEmpty()) "[tool result received]" else "[tool result received; fields: $keys]"
    }

    private fun summarizeRawEvent(raw: JsonObject): String {
        val eventText = raw.str("event")
        if (!eventText.isNullOrBlank()) return truncateToolDisplay(eventText, MAX_RAW_DISPLAY_CHARS)
        val keys = raw.keys.take(12).joinToString(", ")
        return if (keys.isEmpty()) "[raw event received]" else "[raw event received; fields: $keys]"
    }

    private fun header(type: String, id: String): String {
        val t = type.trim().ifEmpty { "EVENT" }
        val i = id.trim()
        return if (i.isEmpty()) "[$t]" else "[$t] #${shortId(i)}"
    }

    private fun shortId(id: String): String = id.trim().let { if (it.length <= 8) it else it.take(8) }

    private fun AguiEvent.textDelta(): String = delta?.takeIf { it.isNotEmpty() } ?: content.orEmpty()

    // -- block keys ----------------------------------------------------------
    private const val THINKING_KEY = "thinking"
    private const val INTERRUPT_KEY = "interrupts"
    private fun textKey(id: String) = "text:$id"
    private fun reasonKey(id: String) = "reasoning:$id"
    private fun toolKey(id: String) = "tool:$id"

    private const val MAX_ASSISTANT_TEXT_CHARS = 64_000
    private const val MAX_REASONING_TEXT_CHARS = 32_000
    private const val MAX_TOOL_ARGS_DISPLAY_CHARS = 8_000
    private const val MAX_TOOL_RESULT_DISPLAY_CHARS = 12_000
    private const val MAX_RAW_DISPLAY_CHARS = 4_000
}

// ReplayState mutation helpers (kept here to stay close to their usage).
private fun ReplayState.ignore(id: String) = copy(ignoredTextIds = ignoredTextIds + id)
private fun ReplayState.unignore(id: String) = copy(ignoredTextIds = ignoredTextIds - id)
private fun ReplayState.markCompleted(id: String) = copy(completedTextIds = completedTextIds + id)
