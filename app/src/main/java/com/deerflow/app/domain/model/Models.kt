package com.deerflow.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// AG-UI protocol types. Kotlin port of internal/agui/types.go.
// ---------------------------------------------------------------------------

@Serializable
data class ToolFunction(
    val name: String = "",
    val arguments: String = "",
)

@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: ToolFunction = ToolFunction(),
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    val id: String? = null,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val error: String? = null,
)

@Serializable
data class ResumeEntry(
    val interruptId: String,
    val status: String,
    val payload: JsonElement? = null,
)

@Serializable
data class RunAgentInput(
    val runId: String,
    val threadId: String,
    val state: JsonObject? = null,
    val messages: List<ChatMessage>,
    val tools: List<JsonObject>? = null,
    val context: JsonObject? = null,
    @SerialName("forwardedProps")
    val forwardedProps: JsonObject? = null,
    val resume: List<ResumeEntry>? = null,
)

/** Plain (non-serialized) interrupt model parsed out of RUN_FINISHED.outcome. */
data class Interrupt(
    val id: String,
    val reason: String,
    val message: String? = null,
    val toolCallId: String? = null,
    val expiresAt: String? = null,
)

/**
 * Flattened view over a single decoded SSE event. Mirrors agui.EventEnvelope:
 * the commonly-used fields are hoisted out while [raw] keeps the full object.
 */
data class AguiEvent(
    val type: String,
    val raw: JsonObject,
    val messageId: String? = null,
    val delta: String? = null,
    val toolCallId: String? = null,
    val toolCallName: String? = null,
    val content: String? = null,
)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Build a string content message (the common user-input case). */
fun userMessage(text: String): ChatMessage =
    ChatMessage(role = Roles.USER, content = JsonPrimitive(text))

/**
 * Flatten message content into display text. Content may be a plain string or
 * an array of `{type:"text", text:"..."}` blocks. Mirrors tui.messageText.
 */
fun JsonElement?.asMessageText(): String {
    if (this == null) return ""
    return when (this) {
        is JsonPrimitive -> if (isString) content else toString()
        is JsonObject -> this["text"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        else -> {
            // JsonArray of blocks
            runCatching {
                jsonArray.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    if (obj["type"]?.jsonPrimitive?.contentOrNullSafe() != "text") return@mapNotNull null
                    obj["text"]?.jsonPrimitive?.contentOrNullSafe()
                }.joinToString("\n")
            }.getOrDefault("")
        }
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()

@Serializable
data class ThreadMeta(
    val id: String,
    val title: String,
    val lastActive: Long,
    val isTitleFetched: Boolean = false
)
