package com.deerflow.app.domain

import com.deerflow.app.domain.model.ChatMessage
import com.deerflow.app.domain.model.Roles
import com.deerflow.app.domain.model.asMessageText

/**
 * Tracks history so re-streamed assistant text can be deduplicated.
 * Kotlin port of tui.replayState / filterReplayText.
 */
data class ReplayState(
    val historicalIds: Set<String> = emptySet(),
    val historicalTexts: List<String> = emptyList(),
    val ignoredTextIds: Set<String> = emptySet(),
    val completedTextIds: Set<String> = emptySet(),
) {
    /** Returns the text to display, or empty if it duplicates known history. */
    fun filter(text: String): String {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return ""
        for (hist in historicalTexts) {
            if (hist.isEmpty()) continue
            if (hist == normalized || hist.startsWith(normalized)) return ""
            if (normalized.startsWith(hist)) {
                val remainder = normalized.substring(hist.length).trim()
                return remainder // "" means fully covered
            }
        }
        return text
    }

    companion object {
        fun from(history: List<ChatMessage>): ReplayState {
            val ids = mutableSetOf<String>()
            val texts = mutableListOf<String>()
            for (m in history) {
                if (Roles.normalize(m.role) != Roles.ASSISTANT) continue
                m.id?.takeIf { it.isNotEmpty() }?.let { ids.add(normalizeHistoricalId(it)) }
                val t = normalize(m.content.asMessageText())
                if (t.isNotEmpty()) texts.add(t)
            }
            return ReplayState(historicalIds = ids, historicalTexts = texts)
        }

        fun normalize(text: String): String = text.replace("\r\n", "\n").trim()

        fun normalizeHistoricalId(id: String): String {
            val parts = id.split(":")
            return if (parts.size >= 3) parts.subList(2, parts.size).joinToString(":") else id
        }
    }
}
