package com.deerflow.app.domain

import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.ChatMessage
import com.deerflow.app.domain.model.Interrupt

/** One rendered unit in the transcript. Port of tui.displayBlock (+ a stable key). */
data class DisplayBlock(
    val key: String,
    val kind: BlockKind,
    val header: String,
    val content: String,
    val artifacts: List<AgentArtifact> = emptyList(),
    /**
     * Structured tool-call fields, set for [BlockKind.TOOL] blocks that come
     * from a tool call (as opposed to a bare tool result replayed from history).
     *
     * [content] stays a human-readable one-line summary for the collapsed row,
     * but it joins these fields with " | " and therefore cannot be split back
     * apart: arguments and results legitimately contain "|" -- a grep pattern
     * like "foo|bar", a shell pipe, a Markdown table. Renderers must read these
     * fields rather than parsing [content].
     */
    val tool: ToolDetails? = null,
)

/** Name/arguments/result of a tool call, kept structured for rendering. */
data class ToolDetails(
    val name: String,
    val args: String = "",
    val result: String = "",
    val isError: Boolean = false,
)

enum class BlockKind { USER, ASSISTANT, REASONING, THINKING, TOOL, ARTIFACT, SYSTEM, INTERRUPT, ERROR }

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
    val artifacts: List<AgentArtifact> = emptyList(),
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

    fun upsert(
        key: String,
        kind: BlockKind,
        header: String,
        content: String,
        tool: ToolDetails? = null,
    ): ConversationState {
        val block = DisplayBlock(key, kind, header, content.trim(), tool = tool)
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

    fun appendArtifacts(newArtifacts: List<AgentArtifact>): ConversationState {
        val visible = newArtifacts.filter { it.path.isNotBlank() && it.url.isNotBlank() }
        if (visible.isEmpty()) return this
        val existingPaths = (this.artifacts.map { it.path } + blocks.asSequence()
            .flatMap { it.artifacts.asSequence() }
            .map { it.path })
            .toSet()
        val fresh = visible.filterNot { it.path in existingPaths }
        if (fresh.isEmpty()) return this
        return copy(
            artifacts = this.artifacts + fresh,
            blocks = blocks + DisplayBlock(
                key = "artifacts:$systemCounter",
                kind = BlockKind.ARTIFACT,
                header = "Generated files",
                content = "",
                artifacts = fresh,
            ),
            systemCounter = systemCounter + 1,
        )
    }
}
