package com.deerflow.app.data.waku

import kotlinx.serialization.json.*

/** User names win over generated names; unnamed tasks use the first visible user message. */
object WakuSessionTitles {
    private const val DEFAULT_TITLE = "New task"

    fun display(session: JsonObject): String = explicit(session) ?: automatic(session) ?: fromHistory(session) ?: DEFAULT_TITLE

    fun withFallback(session: JsonObject): JsonObject {
        if (explicit(session) != null || automatic(session) != null) return session
        val fallback = fromHistory(session) ?: return session
        return session.updated("auto_title" to JsonPrimitive(fallback))
    }

    /** Thin catalog rows have no event cursor. Only full hydration can resolve automatic-title races. */
    fun mergeMetadata(local: JsonObject, remote: JsonObject, fullSnapshot: Boolean = false): JsonObject {
        val values = local.toMutableMap()
        val source = if (fullSnapshot) withFallback(remote) else remote
        source["title"]?.let { values["title"] = it }
        if (fullSnapshot && !WakuRecovery.localStreamAhead(local, remote)) {
            source["auto_title"]?.let { values["auto_title"] = it }
        }
        return withFallback(JsonObject(values))
    }

    fun needsDetails(local: JsonObject?, summary: JsonObject): Boolean {
        if (local == null) return explicit(summary) == null && automatic(summary) == null
        return automatic(local) != automatic(summary) ||
            (explicit(local) == null && automatic(local) == null && fromHistory(local) == null)
    }

    private fun explicit(session: JsonObject) = label(session, "title")?.takeUnless { it == DEFAULT_TITLE }
    private fun automatic(session: JsonObject) = label(session, "auto_title")
    private fun label(value: JsonObject, key: String) = (value[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)

    private fun fromHistory(session: JsonObject): String? {
        val message = session.array("messages").filterIsInstance<JsonObject>().firstOrNull { it.string("role") == "user" }
            ?: return null
        // An intentionally empty display_content hides provider-added attachment wrappers.
        val visible = (message["display_content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: label(message, "content")
        val source = visible?.takeIf { it.isNotBlank() }
            ?: message.array("attachments").filterIsInstance<JsonObject>().firstNotNullOfOrNull { label(it, "name") }
            ?: return null
        val title = source.splitToSequence(Regex("[\\s\\p{Z}]+")).filter(String::isNotBlank).take(7).joinToString(" ").trim()
        if (title.isEmpty()) return null
        // Match the desktop's 54 Unicode code-point limit without splitting emoji surrogates.
        return if (title.codePointCount(0, title.length) > 54) title.substring(0, title.offsetByCodePoints(0, 53)) + "…" else title
    }
}
