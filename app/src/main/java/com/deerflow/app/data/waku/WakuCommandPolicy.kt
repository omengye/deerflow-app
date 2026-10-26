package com.deerflow.app.data.waku

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A request is never retried by these rules. They only admit a new, explicit user action. */
object WakuCommandPolicy {
    fun canSubmitPrompt(commands: List<JsonObject>, requestId: String): Boolean =
        commands.indexOfFirst { it.value("requestId") == requestId } == 0 &&
            commands.firstOrNull()?.value("state") == "prepared"

    fun canSubmitControl(commands: List<JsonObject>, requestId: String, kind: String, target: String?): Boolean =
        commands.none { pending ->
            pending.value("requestId") != requestId && pending.value("state") !in setOf("notSent", "rejected") &&
                if (kind == "cancel") pending.value("kind") == "cancel"
                else pending.value("kind") in setOf("respond", "respondUserInput") && pending.value("targetRequestId") == target
        }

    fun acknowledge(commands: List<JsonObject>): List<JsonObject> = commands.filter {
        it.value("state") in setOf("prepared", "sending") ||
            (it.value("state") == "accepted" && it.value("kind") in setOf("respond", "respondUserInput", "cancel"))
    }

    private fun JsonObject.value(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
