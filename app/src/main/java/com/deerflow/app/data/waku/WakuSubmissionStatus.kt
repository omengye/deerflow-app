package com.deerflow.app.data.waku

import kotlinx.serialization.json.JsonObject

/** Normal delivery progress never asks the user to acknowledge or restore a message. */
data class WakuSubmissionStatus(
    val progress: String? = null,
    val issue: String? = null,
    val needsReview: Boolean = false,
    val canRestorePrompt: Boolean = false,
    val canResolve: Boolean = true,
) {
    companion object {
        fun from(commands: List<JsonObject>): WakuSubmissionStatus {
            val prompts = commands.filter { it.string("kind") == "prompt" }
            val issues = commands.filter { it.string("state") in setOf("unknown", "notSent", "rejected") }
            return WakuSubmissionStatus(
                progress = when {
                    prompts.any { it.string("state") in setOf("prepared", "sending") } -> "正在发送…"
                    prompts.any { it.string("state") == "accepted" } -> "已发送，正在同步…"
                    else -> null
                },
                issue = issues.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { command ->
                    val label = when (command.string("state")) {
                        "notSent" -> "操作未发送"
                        "rejected" -> "服务器拒绝了操作"
                        else -> "操作结果未知"
                    }
                    val reason = command.string("error")?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
                    val original = command.string("text")?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                    label + reason + original
                },
                needsReview = issues.any { it.string("state") == "unknown" },
                canRestorePrompt = issues.any { it.string("kind") == "prompt" },
                canResolve = commands.none { it.string("state") in setOf("prepared", "sending") },
            )
        }
    }
}
