package com.deerflow.app.domain.model

/** Role constants and normalization. Kotlin port of agui role handling. */
object Roles {
    const val DEVELOPER = "developer"
    const val SYSTEM = "system"
    const val ASSISTANT = "assistant"
    const val USER = "user"
    const val TOOL = "tool"
    const val ACTIVITY = "activity"
    const val REASONING = "reasoning"

    private val allowed = setOf(DEVELOPER, SYSTEM, ASSISTANT, USER, TOOL, ACTIVITY, REASONING)
    private val remaps = mapOf("ai" to ASSISTANT, "human" to USER, "function" to TOOL)

    fun normalize(role: String?): String {
        val trimmed = role?.trim().orEmpty()
        if (trimmed.isEmpty()) return USER
        if (trimmed in allowed) return trimmed
        return remaps[trimmed] ?: USER
    }
}
