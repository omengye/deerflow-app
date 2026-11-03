package com.deerflow.app.data.waku

import java.net.URI
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val WAKU_PROTOCOL_VERSION = 12
const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
internal const val MAX_WAKU_WIRE_BYTES = 48 * 1024 * 1024

val WakuJson = Json { ignoreUnknownKeys = true }

fun wakuCommand(type: String, block: JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
        put("type", type)
        block()
    }

/** Accept only the app-server endpoint; never reinterpret a legacy AG-UI URL. */
fun normalizeWakuEndpoint(endpoint: String): String {
    val value = endpoint.trim()
    val uri = try {
        URI(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("服务器地址格式无效")
    }
    val scheme = uri.scheme?.lowercase()
    require(scheme in setOf("ws", "wss", "http", "https")) {
        "服务器地址必须使用 ws、wss、http 或 https"
    }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "服务器地址不能包含用户名、密码、查询参数或片段"
    }
    require(uri.rawPath in setOf("", "/", "/v1")) {
        "请填写 Waku 服务器地址或 /v1 端点；旧 AG-UI 路径不受支持"
    }
    val httpScheme = if (scheme == "wss" || scheme == "https") "https" else "http"
    val url = (httpScheme + value.substring(value.indexOf(':'))).toHttpUrlOrNull()
        ?: throw IllegalArgumentException("服务器地址格式无效")
    require(url.host.isNotBlank() && url.username.isEmpty() && url.password.isEmpty()) {
        "服务器地址格式无效"
    }
    val normalized = url.newBuilder().encodedPath("/v1").build().toString()
    return (if (httpScheme == "https") "wss" else "ws") + normalized.substring(normalized.indexOf(':'))
}

internal fun requireWakuUuid(value: String, field: String) {
    require(runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)) {
        "$field 必须是 UUID"
    }
}

enum class WakuDelivery { NOT_SENT, RESULT_UNKNOWN, REJECTED }

class WakuRequestException(
    val delivery: WakuDelivery,
    val requestId: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

enum class WakuConnectionFailure {
    AUTHENTICATION, PROTOCOL, HANDSHAKE, TIMEOUT, NETWORK, CLOSED, OVERFLOW, ABORTED,
}

class WakuConnectionException(
    val kind: WakuConnectionFailure,
    message: String,
) : Exception(message) {
    val retryable: Boolean
        get() = kind in setOf(
            WakuConnectionFailure.TIMEOUT,
            WakuConnectionFailure.NETWORK,
            WakuConnectionFailure.CLOSED,
            WakuConnectionFailure.OVERFLOW,
        )
}

sealed interface WakuConnectionState {
    data class Disconnected(val failure: WakuConnectionException? = null) : WakuConnectionState
    data object Connecting : WakuConnectionState
    data object Connected : WakuConnectionState
}
