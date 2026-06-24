package com.deerflow.app.domain

import com.deerflow.app.domain.model.ChatMessage
import com.deerflow.app.domain.model.Interrupt

/** One rendered unit in the transcript. Port of tui.displayBlock (+ a stable key). */
data class DisplayBlock(
    val key: String,
    val kind: BlockKind,
    val header: String,
    val content: String,
)

enum class BlockKind { USER, ASSISTANT, REASONING, THINKING, TOOL, SYSTEM, INTERRUPT, ERROR }

/** Buffer for an in-flight tool call. Port of tui.toolCallBuffer. */
data class ToolBuffer(
    val id: String,
    val name: String = "",
    val args: String = "",
    val result: String = "",
    val isError: Boolean = false,
    val ended: Boolean = false,
)

/**
 * Full conversation state. Immutable; [ConversationReducer] returns new copies.
 * Port of tui.model (display + protocol fields, terminal concerns removed).
 */
data class ConversationState(
    val threadId: String,
    val status: String = "Idle",
    val running: Boolean = false,
    val blocks: List<DisplayBlock> = emptyList(),
    val history: List<ChatMessage> = emptyList(),
    val interrupts: List<Interrupt> = emptyList(),

    // streaming buffers
    val textBuffers: Map<String, String> = emptyMap(),
    val textAgentNames: Map<String, String> = emptyMap(),
    val reasoningBuffers: Map<String, String> = emptyMap(),
    val toolBuffers: Map<String, ToolBuffer> = emptyMap(),
    val thinking: String? = null,

    // bookkeeping
    val replay: ReplayState = ReplayState(),
    val activeTextId: String? = null,
    val activeReasonId: String? = null,
    val textCounter: Int = 0,
    val reasonCounter: Int = 0,
    val toolCounter: Int = 0,
    val systemCounter: Int = 0,
) {
    val messageCount: Int get() = history.size
    val awaitingInterrupt: Boolean get() = interrupts.isNotEmpty()

    // -- block helpers -------------------------------------------------------

    fun upsert(key: String, kind: BlockKind, header: String, content: String): ConversationState {
        val block = DisplayBlock(key, kind, header, content.trim())
        val idx = blocks.indexOfFirst { it.key == key }
        val newBlocks = if (idx >= 0) {
            blocks.toMutableList().also { it[idx] = block }
        } else {
            blocks + block
        }
        return copy(blocks = newBlocks)
    }

    fun removeBlock(key: String): ConversationState =
        if (blocks.any { it.key == key }) copy(blocks = blocks.filterNot { it.key == key }) else this

    fun appendSystem(kind: BlockKind, header: String, content: String): ConversationState =
        copy(
            blocks = blocks + DisplayBlock("sys:$systemCounter", kind, header, content.trim()),
            systemCounter = systemCounter + 1,
        )
}
