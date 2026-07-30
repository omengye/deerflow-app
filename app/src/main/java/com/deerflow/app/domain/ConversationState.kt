package com.deerflow.app.domain

import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.ChatMessage
import com.deerflow.app.domain.model.Interrupt
import com.deerflow.app.domain.model.Roles

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
    /** Stable protocol message id, when this block represents a message. */
    val messageId: String? = null,
    /** 1-based user turn to which this block belongs. */
    val turnIndex: Int? = null,
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
    val turnCount: Int get() = history.count { Roles.normalize(it.role) == Roles.USER }

    /** Most recent assistant message in the current user turn, if it has a stable id. */
    fun currentTurnAssistantMessageId(): String? {
        for (message in history.asReversed()) {
            when (Roles.normalize(message.role)) {
                Roles.USER -> return null
                Roles.ASSISTANT -> message.id?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    // -- block helpers -------------------------------------------------------

    fun upsert(
        key: String,
        kind: BlockKind,
        header: String,
        content: String,
        tool: ToolDetails? = null,
        messageId: String? = null,
        turnIndex: Int? = null,
    ): ConversationState {
        val idx = blocks.indexOfFirst { it.key == key }
        val previous = blocks.getOrNull(idx)
        val resolvedTurnIndex = turnIndex
            ?: previous?.turnIndex
            ?: turnCount.takeIf { it > 0 && kind.isTurnScoped() }
        val block = DisplayBlock(
            key = key,
            kind = kind,
            header = header,
            content = content.trim(),
            tool = tool,
            messageId = messageId ?: previous?.messageId,
            turnIndex = resolvedTurnIndex,
        )
        val newBlocks = if (idx >= 0) {
            blocks.toMutableList().also { it[idx] = block }
        } else {
            blocks.insertBeforeTurnArtifacts(block)
        }
        return copy(blocks = newBlocks)
    }

    fun removeBlock(key: String): ConversationState =
        if (blocks.any { it.key == key }) copy(blocks = blocks.filterNot { it.key == key }) else this

    fun appendSystem(
        kind: BlockKind,
        header: String,
        content: String,
        messageId: String? = null,
        turnIndex: Int? = null,
    ): ConversationState {
        val block = DisplayBlock(
            key = "sys:$systemCounter",
            kind = kind,
            header = header,
            content = content.trim(),
            messageId = messageId,
            turnIndex = turnIndex,
        )
        return copy(
            blocks = blocks.insertBeforeTurnArtifacts(block),
            systemCounter = systemCounter + 1,
        )
    }

    fun appendArtifacts(newArtifacts: List<AgentArtifact>): ConversationState {
        val visible = newArtifacts.filter { it.path.isNotBlank() && it.url.isNotBlank() }
        if (visible.isEmpty()) return this
        val incomingByPath = visible.associateBy { it.path }
        var placementChanged = false
        val updatedExisting = artifacts.map { existing ->
            val incoming = incomingByPath[existing.path] ?: return@map existing
            val updated = existing.copy(
                anchorMessageId = existing.anchorMessageId ?: incoming.anchorMessageId,
                anchorTurnIndex = existing.anchorTurnIndex ?: incoming.anchorTurnIndex,
            )
            if (updated != existing) placementChanged = true
            updated
        }
        val existingPaths = (this.artifacts.map { it.path } + blocks.asSequence()
            .flatMap { it.artifacts.asSequence() }
            .map { it.path })
            .toSet()
        val fresh = visible.filterNot { it.path in existingPaths }
        if (fresh.isEmpty() && !placementChanged) return this
        return copy(artifacts = updatedExisting + fresh).rebuildArtifactBlocks()
    }

    /** Recreate artifact blocks from their persisted anchors after a history snapshot. */
    fun rebuildArtifactBlocks(): ConversationState {
        val cleanArtifacts = artifacts.filter { it.path.isNotBlank() && it.url.isNotBlank() }
        val rebuilt = blocks.filterNot { it.kind == BlockKind.ARTIFACT }.toMutableList()
        val groups = linkedMapOf<ArtifactAnchor, MutableList<AgentArtifact>>()
        cleanArtifacts.forEach { artifact ->
            val anchor = ArtifactAnchor(
                messageId = artifact.anchorMessageId?.trim()?.takeIf { it.isNotEmpty() },
                turnIndex = artifact.anchorTurnIndex?.takeIf { it > 0 },
            )
            groups.getOrPut(anchor) { mutableListOf() }.add(artifact)
        }

        groups.forEach { (anchor, anchoredArtifacts) ->
            val block = DisplayBlock(
                key = anchor.blockKey(),
                kind = BlockKind.ARTIFACT,
                header = "Generated files",
                content = "",
                artifacts = anchoredArtifacts,
                messageId = anchor.messageId,
                turnIndex = anchor.turnIndex,
            )
            val messageIndex = anchor.messageId?.let { id ->
                rebuilt.indexOfLast { it.messageId == id }
            } ?: -1
            val turnIndex = if (messageIndex >= 0) {
                messageIndex
            } else {
                anchor.turnIndex?.let { turn -> rebuilt.indexOfLast { it.turnIndex == turn } } ?: -1
            }
            val insertionIndex = if (turnIndex >= 0) turnIndex + 1 else rebuilt.size
            rebuilt.add(insertionIndex, block)
        }

        return copy(artifacts = cleanArtifacts, blocks = rebuilt)
    }
}

private data class ArtifactAnchor(
    val messageId: String?,
    val turnIndex: Int?,
) {
    fun blockKey(): String = when {
        messageId != null -> "artifacts:message:$messageId"
        turnIndex != null -> "artifacts:turn:$turnIndex"
        else -> "artifacts:unplaced"
    }
}

private fun BlockKind.isTurnScoped(): Boolean = when (this) {
    BlockKind.ASSISTANT, BlockKind.REASONING, BlockKind.THINKING, BlockKind.TOOL -> true
    else -> false
}

private fun List<DisplayBlock>.insertBeforeTurnArtifacts(block: DisplayBlock): List<DisplayBlock> {
    val turn = block.turnIndex ?: return this + block
    if (block.kind == BlockKind.ARTIFACT) return this + block
    val artifactIndex = indexOfFirst { it.kind == BlockKind.ARTIFACT && it.turnIndex == turn }
    if (artifactIndex < 0) return this + block
    return toMutableList().also { it.add(artifactIndex, block) }
}
