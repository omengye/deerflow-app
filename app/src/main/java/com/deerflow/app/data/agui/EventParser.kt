package com.deerflow.app.data.agui

import com.deerflow.app.domain.model.AguiEvent
import com.deerflow.app.domain.model.Interrupt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Decodes a single SSE `data:` payload into an [AguiEvent]. Port of ParseEventEnvelope. */
object EventParser {

    fun parse(rawJson: String): AguiEvent {
        val fastType = extractJsonString(rawJson, "type")
        if (fastType == "TOOL_CALL_ARGS" || fastType == "TOOL_CALL_CHUNK") {
            return parseLargeEvent(rawJson, rawJson.length)
        }
        if (rawJson.length > MAX_FULL_JSON_PARSE_CHARS) {
            return parseLargeEvent(rawJson, rawJson.length)
        }

        val obj = runCatching { AguiJson.parseToJsonElement(rawJson).jsonObject }
            .getOrElse {
                return rawEvent("non-json-sse", rawJson.length)
            }

        return AguiEvent(
            type = obj.str("type") ?: "RAW",
            raw = obj,
            messageId = obj.str("messageId"),
            delta = obj.str("delta"),
            toolCallId = obj.str("toolCallId") ?: obj.str("tool_call_id"),
            toolCallName = obj.str("toolCallName"),
            content = obj.str("content"),
        )
    }

    fun parseTruncated(rawPrefix: String, originalLength: Int): AguiEvent =
        parseLargeEvent(rawPrefix, originalLength)

    private fun parseLargeEvent(rawJson: String, originalLength: Int): AguiEvent {
        val type = extractJsonString(rawJson, "type") ?: "RAW"
        val messageId = extractJsonString(rawJson, "messageId")
        val toolCallId = extractJsonString(rawJson, "toolCallId") ?: extractJsonString(rawJson, "tool_call_id")
        val toolCallName = extractJsonString(rawJson, "toolCallName")
        val summary = "[large $type event omitted: $originalLength chars]"
        val raw = JsonObject(
            buildMap {
                put("type", JsonPrimitive(type))
                put("source", JsonPrimitive("large-sse"))
                put("originalLength", JsonPrimitive(originalLength))
                put("summary", JsonPrimitive(summary))
                messageId?.let { put("messageId", JsonPrimitive(it)) }
                toolCallId?.let { put("toolCallId", JsonPrimitive(it)) }
                toolCallName?.let { put("toolCallName", JsonPrimitive(it)) }
                if (type == "TOOL_CALL_RESULT") put("content", JsonPrimitive(summary))
                if (type == "TEXT_MESSAGE_CONTENT" || type == "TEXT_MESSAGE_CHUNK") put("delta", JsonPrimitive(summary))
            },
        )
        return AguiEvent(
            type = type,
            raw = raw,
            messageId = messageId,
            delta = raw.str("delta"),
            toolCallId = toolCallId,
            toolCallName = toolCallName,
            content = raw.str("content"),
        )
    }

    private fun rawEvent(source: String, originalLength: Int): AguiEvent {
        val summary = "[raw event omitted: $originalLength chars]"
        return AguiEvent(
            type = "RAW",
            raw = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("RAW"),
                    "event" to JsonPrimitive(summary),
                    "source" to JsonPrimitive(source),
                    "originalLength" to JsonPrimitive(originalLength),
                ),
            ),
        )
    }

    private fun extractJsonString(json: String, field: String): String? {
        val pattern = Regex("\\\"${Regex.escape(field)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.let(::unescapeJsonLite)
    }

    private fun unescapeJsonLite(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\/", "/")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")

    /** Extract interrupts from a RUN_FINISHED event. Port of InterruptsFromRunFinished. */
    fun interruptsFromRunFinished(event: AguiEvent): List<Interrupt> {
        if (event.type != "RUN_FINISHED") return emptyList()
        val outcome = event.raw["outcome"]?.jsonObjectOrNull() ?: return emptyList()
        if (outcome.str("type") != "interrupt") return emptyList()
        val list = outcome["interrupts"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return list.mapNotNull { raw ->
            val o = raw.jsonObjectOrNull() ?: return@mapNotNull null
            val id = o.str("id").orEmpty()
            val reason = o.str("reason").orEmpty()
            if (id.isEmpty() || reason.isEmpty()) return@mapNotNull null
            Interrupt(
                id = id,
                reason = reason,
                message = o.str("message"),
                toolCallId = o.str("toolCallId"),
                expiresAt = o.str("expiresAt"),
            )
        }
    }

    private const val MAX_FULL_JSON_PARSE_CHARS = 64 * 1024
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.let { if (it.isString) it.contentOrNull else it.toString() }

internal fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
    this as? JsonObject

internal fun JsonObject.nestedStr(key: String, nestedKey: String): String? =
    (this[key] as? JsonObject)?.str(nestedKey)