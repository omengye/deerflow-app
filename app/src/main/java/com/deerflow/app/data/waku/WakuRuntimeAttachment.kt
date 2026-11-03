package com.deerflow.app.data.waku

import kotlinx.serialization.json.*

/** v12 attach metadata belongs to this connection, without replaying provider work. */
data class WakuRuntimeAttachment(
    val runtimeId: String?,
    val conversationCapability: Boolean?,
    val deerflowReady: JsonObject?,
    val pendingHistory: JsonObject?,
) {
    fun applyTo(snapshot: WakuSnapshot): WakuSnapshot {
        val bound = runtimeId?.let { WakuProjection.bindRuntime(snapshot, it) } ?: snapshot
        return bound.copy(
            session = bound.session.updated("conversation_capability" to
                (conversationCapability?.let(::JsonPrimitive) ?: JsonNull)),
            pendingHistory = pendingHistory,
        )
    }

    companion object {
        fun parse(payload: JsonObject): WakuRuntimeAttachment {
            require(payload.string("type") == "sessionRuntime") { "无法识别服务器运行时" }
            fun optionalObject(key: String): JsonObject? = when (val value = payload[key]) {
                null, JsonNull -> null
                is JsonObject -> value
                else -> error("服务器返回的 $key 无效")
            }
            val runtimeId = payload.string("runtimeId")?.also { requireWakuUuid(it, "runtimeId") }
            val ready = optionalObject("deerflowReady")
            require(ready == null || (runtimeId != null && ready.string("kind") == "ready" &&
                !ready.string("connectionId").isNullOrBlank() && !ready.string("sessionId").isNullOrBlank() &&
                ready["capabilities"] is JsonObject)) {
                "服务器返回的产物连接信息无效"
            }
            val pending = optionalObject("pendingHistory")
            val count = pending?.get("turnCount") as? JsonPrimitive
            require(pending == null || (pending.string("kind") in setOf("fork", "rewind") &&
                pending.string("operationId") != null && count?.isString == false && (count.longOrNull ?: -1) >= 0)) {
                "服务器返回的历史恢复信息无效"
            }
            pending?.string("operationId")?.let { requireWakuUuid(it, "operationId") }
            return WakuRuntimeAttachment(runtimeId,
                (payload["conversationCapability"] as? JsonPrimitive)?.booleanOrNull, ready, pending)
        }
    }
}
