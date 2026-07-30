package com.deerflow.app.domain

import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.AguiEvent
import com.deerflow.app.domain.model.ChatMessage
import com.deerflow.app.domain.model.Roles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationArtifactPlacementTest {

    @Test
    fun customArtifactIsAnchoredToCurrentAssistantMessage() {
        val state = firstTurnState()
        val event = artifactEvent("/mnt/user-data/outputs/chart.png")

        val next = ConversationReducer.reduce(state, event)

        val artifact = next.artifacts.single()
        assertEquals("assistant-1", artifact.anchorMessageId)
        assertEquals(1, artifact.anchorTurnIndex)
        assertEquals(
            listOf(BlockKind.USER, BlockKind.ASSISTANT, BlockKind.ARTIFACT),
            next.blocks.map { it.kind },
        )
    }

    @Test
    fun laterBlocksInSameTurnStayBeforeArtifact() {
        val withArtifact = ConversationReducer.reduce(
            firstTurnState(),
            artifactEvent("/mnt/user-data/outputs/chart.png"),
        )

        val next = withArtifact.upsert(
            key = "tool:late",
            kind = BlockKind.TOOL,
            header = "Tool",
            content = "done",
            turnIndex = 1,
        )

        assertEquals(
            listOf(BlockKind.USER, BlockKind.ASSISTANT, BlockKind.TOOL, BlockKind.ARTIFACT),
            next.blocks.map { it.kind },
        )
    }

    @Test
    fun anchoredArtifactReturnsToItsTurnAfterSnapshotRebuild() {
        val artifact = artifact("/mnt/user-data/outputs/chart.png").copy(
            anchorMessageId = "assistant-1",
            anchorTurnIndex = 1,
        )
        val state = ConversationState(
            threadId = "thread-1",
            artifacts = listOf(artifact),
        )
        val snapshot = AguiEvent(
            type = "MESSAGES_SNAPSHOT",
            raw = JsonObject(
                mapOf(
                    "messages" to JsonArray(
                        listOf(
                            message(Roles.USER, "question 1", "user-1"),
                            message(Roles.ASSISTANT, "answer 1", "assistant-1"),
                            message(Roles.USER, "question 2", "user-2"),
                            message(Roles.ASSISTANT, "answer 2", "assistant-2"),
                        ),
                    ),
                ),
            ),
        )

        val rebuilt = ConversationReducer.reduce(state, snapshot)

        assertEquals(
            listOf(
                BlockKind.USER,
                BlockKind.ASSISTANT,
                BlockKind.ARTIFACT,
                BlockKind.USER,
                BlockKind.ASSISTANT,
            ),
            rebuilt.blocks.map { it.kind },
        )
        assertEquals("assistant-1", rebuilt.blocks[2].messageId)
    }

    @Test
    fun legacyArtifactWithoutAnchorSafelyFallsBackToBottom() {
        val state = ConversationState(
            threadId = "thread-1",
            history = listOf(
                ChatMessage(Roles.USER, JsonPrimitive("question"), id = "user-1"),
                ChatMessage(Roles.ASSISTANT, JsonPrimitive("answer"), id = "assistant-1"),
            ),
        )
            .appendSystem(BlockKind.USER, "You", "question", turnIndex = 1)
            .upsert(
                key = "text:assistant-1",
                kind = BlockKind.ASSISTANT,
                header = "Assistant",
                content = "answer",
                messageId = "assistant-1",
                turnIndex = 1,
            )
            .appendArtifacts(listOf(artifact("/mnt/user-data/outputs/legacy.png")))

        assertEquals(BlockKind.ARTIFACT, state.blocks.last().kind)
        assertTrue(state.artifacts.single().anchorMessageId == null)
        assertTrue(state.artifacts.single().anchorTurnIndex == null)
    }

    private fun firstTurnState(): ConversationState {
        val history = listOf(
            ChatMessage(Roles.USER, JsonPrimitive("question"), id = "user-1"),
            ChatMessage(Roles.ASSISTANT, JsonPrimitive("answer"), id = "assistant-1"),
        )
        return ConversationState(threadId = "thread-1", history = history)
            .appendSystem(
                BlockKind.USER,
                "You",
                "question",
                messageId = "user-1",
                turnIndex = 1,
            )
            .upsert(
                key = "text:assistant-1",
                kind = BlockKind.ASSISTANT,
                header = "Assistant",
                content = "answer",
                messageId = "assistant-1",
                turnIndex = 1,
            )
    }

    private fun artifactEvent(path: String): AguiEvent = AguiEvent(
        type = "CUSTOM",
        raw = JsonObject(
            mapOf(
                "type" to JsonPrimitive("CUSTOM"),
                "name" to JsonPrimitive("deerflow.artifacts"),
                "value" to JsonObject(
                    mapOf(
                        "artifacts" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "path" to JsonPrimitive(path),
                                        "name" to JsonPrimitive(path.substringAfterLast('/')),
                                        "url" to JsonPrimitive("https://example.test/artifact"),
                                        "kind" to JsonPrimitive("image"),
                                        "mimeType" to JsonPrimitive("image/png"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun artifact(path: String) = AgentArtifact(
        path = path,
        name = path.substringAfterLast('/'),
        url = "https://example.test/artifact",
        mimeType = "image/png",
        kind = "image",
    )

    private fun message(role: String, content: String, id: String) = JsonObject(
        mapOf(
            "role" to JsonPrimitive(role),
            "content" to JsonPrimitive(content),
            "id" to JsonPrimitive(id),
        ),
    )
}
