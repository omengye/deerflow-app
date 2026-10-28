package com.deerflow.app.data.waku

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * One connection, no reconnect and no command retries. The repository owns replay and subscriptions.
 * A dropped connection leaves [incoming] open so the same client may be connected again. Consumers
 * must also observe [state]; [close] permanently closes this client and its incoming channel.
 */
class WakuClient(
    endpoint: String,
    private val token: String,
    val clientId: String,
    private val socketFactory: WebSocket.Factory = defaultSocketFactory,
    private val handshakeTimeoutMs: Long = 30_000,
    incomingCapacity: Int = 64,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val diagnostics: (WakuConnectionDiagnostic) -> Unit = {},
) {
    private class Attempt(val generation: Long, val startedAtMillis: Long) {
        val ready = CompletableDeferred<Unit>()
        var socket: WebSocket? = null
        var connected = false
        var connectedAtMillis: Long? = null
        var lastReceivedAtMillis: Long? = null
        var endedAtMillis: Long? = null
        var stage = "connecting"
    }

    private class Pending(
        val attempt: Attempt,
        val result: CompletableDeferred<JsonObject> = CompletableDeferred(),
        var sent: Boolean = false,
    )

    private val url = normalizeWakuEndpoint(endpoint)
    private val lock = Any()
    private var generation = 0L
    private var active: Attempt? = null
    private var latest: Attempt? = null
    private var permanentlyClosed = false
    private val pending = mutableMapOf<String, Pending>()
    private val mutableState = MutableStateFlow<WakuConnectionState>(WakuConnectionState.Disconnected())

    val state: StateFlow<WakuConnectionState> = mutableState.asStateFlow()
    val incoming: Channel<JsonObject> = Channel(incomingCapacity.also { require(it > 0) })
    val health: WakuConnectionHealth get() = synchronized(lock) {
        val attempt = latest
        WakuConnectionHealth(
            active?.connected == true,
            attempt?.lastReceivedAtMillis,
            attempt?.connectedAtMillis?.let { (attempt.endedAtMillis ?: clockMillis()) - it },
            mutableState.value == WakuConnectionState.Connecting,
        )
    }

    init {
        requireWakuUuid(clientId, "clientId")
        require(token.isNotBlank()) { "请配置服务器 Token" }
        require(handshakeTimeoutMs > 0)
    }

    suspend fun connect() {
        val attempt: Attempt
        val createSocket: Boolean
        synchronized(lock) {
            if (permanentlyClosed) throw connectionError(WakuConnectionFailure.ABORTED)
            val current = active
            if (current != null) {
                attempt = current
                createSocket = false
            } else {
                attempt = Attempt(++generation, clockMillis())
                active = attempt
                latest = attempt
                mutableState.value = WakuConnectionState.Connecting
                diagnostic(attempt)
                createSocket = true
            }
        }
        if (createSocket) {
            try {
                val socket = socketFactory.newWebSocket(Request.Builder().url(url).build(), listener(attempt))
                synchronized(lock) {
                    if (active === attempt) attempt.socket = socket else socket.cancel()
                }
            } catch (_: Exception) {
                fail(attempt, connectionError(WakuConnectionFailure.NETWORK))
            }
        }
        try {
            withTimeout(handshakeTimeoutMs) { attempt.ready.await() }
        } catch (_: TimeoutCancellationException) {
            val error = connectionError(WakuConnectionFailure.TIMEOUT)
            fail(attempt, error)
            throw error
        } catch (cancelled: CancellationException) {
            fail(attempt, connectionError(WakuConnectionFailure.ABORTED))
            throw cancelled
        }
    }

    suspend fun request(
        command: JsonObject,
        sessionId: String = NIL_UUID,
        runtimeId: String = NIL_UUID,
        requestId: String = UUID.randomUUID().toString(),
        timeoutMs: Long = 30_000,
    ): JsonObject {
        require(timeoutMs > 0)
        requireWakuUuid(requestId, "requestId")
        requireWakuUuid(sessionId, "sessionId")
        requireWakuUuid(runtimeId, "runtimeId")
        require(requestId != NIL_UUID) { "请求必须使用非空 UUID" }
        val wire = buildJsonObject {
            put("type", "request")
            put("requestId", requestId)
            put("sessionId", sessionId)
            put("runtimeId", runtimeId)
            put("command", command)
        }.toString()
        if (wire.toByteArray(Charsets.UTF_8).size > MAX_WAKU_WIRE_BYTES) {
            throw WakuRequestException(WakuDelivery.NOT_SENT, requestId, "请求超过服务器消息大小限制")
        }
        val entry: Pending
        synchronized(lock) {
            val attempt = active
            val socket = attempt?.socket
            if (attempt == null || !attempt.connected || socket == null) {
                throw WakuRequestException(WakuDelivery.NOT_SENT, requestId, "连接尚未就绪，请重新连接")
            }
            if (pending.containsKey(requestId)) {
                throw WakuRequestException(WakuDelivery.NOT_SENT, requestId, "同一请求正在等待结果")
            }
            entry = Pending(attempt)
            pending[requestId] = entry
            // Mark before send: once accepted by OkHttp, loss of a response is ambiguous.
            entry.sent = true
            try {
                if (!socket.send(wire)) {
                    pending.remove(requestId)
                    entry.sent = false
                    entry.result.completeExceptionally(
                        WakuRequestException(WakuDelivery.NOT_SENT, requestId, "连接未接受请求"),
                    )
                }
            } catch (_: Exception) {
                pending.remove(requestId)
                entry.result.completeExceptionally(
                    WakuRequestException(WakuDelivery.RESULT_UNKNOWN, requestId, "发送中断，请先核对服务器状态"),
                )
            }
        }
        return try {
            withTimeout(timeoutMs) { entry.result.await() }
        } catch (_: TimeoutCancellationException) {
            throw WakuRequestException(
                if (entry.sent) WakuDelivery.RESULT_UNKNOWN else WakuDelivery.NOT_SENT,
                requestId,
                "等待结果超时，请先核对服务器状态；请求未自动重发",
            )
        } finally {
            synchronized(lock) {
                if (pending[requestId] === entry) pending.remove(requestId)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            if (permanentlyClosed) return
            permanentlyClosed = true
            active?.let { fail(it, connectionError(WakuConnectionFailure.ABORTED)) }
            incoming.close()
        }
    }

    private fun listener(attempt: Attempt) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (!isActive(attempt)) {
                    webSocket.cancel()
                    return
                }
                attempt.socket = webSocket
                attempt.stage = "authenticating"
                diagnostic(attempt)
                val hello = buildJsonObject {
                    put("type", "hello")
                    put("protocolVersion", WAKU_PROTOCOL_VERSION)
                    put("token", token)
                    put("clientId", clientId)
                    put("resumeFrom", JsonArray(emptyList()))
                }
                if (!webSocket.send(hello.toString())) {
                    fail(attempt, connectionError(WakuConnectionFailure.NETWORK))
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = receive(attempt, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            fail(attempt, connectionError(WakuConnectionFailure.PROTOCOL))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            fail(attempt, closeError(code))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(attempt, closeError(code))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val kind = when (response?.code) {
                401, 403 -> WakuConnectionFailure.AUTHENTICATION
                else -> WakuConnectionFailure.NETWORK
            }
            val transport = when {
                response != null -> "http_${response.code}"
                t is java.net.UnknownHostException -> "dns"
                t is javax.net.ssl.SSLException -> "tls"
                t is java.net.SocketTimeoutException -> "transport_timeout"
                else -> "transport"
            }
            fail(attempt, connectionError(kind), transport)
        }
    }

    private fun receive(attempt: Attempt, text: String) {
        synchronized(lock) {
            if (!isActive(attempt)) return
            if (text.toByteArray(Charsets.UTF_8).size > MAX_WAKU_WIRE_BYTES) {
                fail(attempt, connectionError(WakuConnectionFailure.PROTOCOL))
                return
            }
            val message = runCatching { WakuJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (message == null) {
                fail(attempt, connectionError(if (attempt.connected) WakuConnectionFailure.PROTOCOL else WakuConnectionFailure.HANDSHAKE))
                return
            }
            val type = message.string("type")
            if (type == "rejected") {
                val reason = message.string("message").orEmpty()
                val kind = if (reason.contains("protocol", true) || reason.contains("version", true)) {
                    WakuConnectionFailure.PROTOCOL
                } else {
                    WakuConnectionFailure.AUTHENTICATION
                }
                fail(attempt, connectionError(kind))
                return
            }
            if (!attempt.connected) {
                if (type != "hello") {
                    fail(attempt, connectionError(WakuConnectionFailure.HANDSHAKE))
                } else if ((message["protocolVersion"] as? JsonPrimitive)?.intOrNull != WAKU_PROTOCOL_VERSION) {
                    fail(attempt, connectionError(WakuConnectionFailure.PROTOCOL))
                } else {
                    attempt.connected = true
                    attempt.connectedAtMillis = clockMillis()
                    attempt.lastReceivedAtMillis = attempt.connectedAtMillis
                    attempt.stage = "connected"
                    diagnostic(attempt)
                    mutableState.value = WakuConnectionState.Connected
                    attempt.ready.complete(Unit)
                }
                return
            }
            when (type) {
                "response" -> {
                    attempt.lastReceivedAtMillis = clockMillis()
                    response(attempt, message)
                }
                "event", "taskStateChanged", "shuttingDown" -> {
                    attempt.lastReceivedAtMillis = clockMillis()
                    if (incoming.trySend(message).isFailure) {
                        fail(attempt, connectionError(WakuConnectionFailure.OVERFLOW))
                    } else if (type == "shuttingDown") {
                        fail(attempt, connectionError(WakuConnectionFailure.CLOSED))
                    }
                }
                else -> fail(attempt, connectionError(WakuConnectionFailure.PROTOCOL))
            }
        }
    }

    private fun response(attempt: Attempt, message: JsonObject) {
        val requestId = message.string("requestId")
        val outcome = message["outcome"] as? JsonObject
        val status = outcome?.string("status")
        if (requestId == null || outcome == null || status !in setOf("ok", "error")) {
            fail(attempt, connectionError(WakuConnectionFailure.PROTOCOL))
            return
        }
        val payload = outcome["payload"] as? JsonObject
        val error = outcome["error"] as? JsonObject
        if ((status == "ok" && payload == null) || (status == "error" && error?.string("message") == null)) {
            fail(attempt, connectionError(WakuConnectionFailure.PROTOCOL))
            return
        }
        // A late response after an individual timeout is harmless; never replay its command.
        val entry = pending[requestId] ?: return
        if (entry.attempt !== attempt) return
        pending.remove(requestId)
        if (status == "ok") {
            entry.result.complete(payload!!)
        } else {
            entry.result.completeExceptionally(
                WakuRequestException(WakuDelivery.REJECTED, requestId, error!!.string("message")!!),
            )
        }
    }

    private fun fail(attempt: Attempt, error: WakuConnectionException, transport: String? = null) {
        synchronized(lock) {
            if (!isActive(attempt)) return
            attempt.endedAtMillis = clockMillis()
            diagnostic(attempt, error.kind, transport)
            active = null
            ++generation
            mutableState.value = WakuConnectionState.Disconnected(error)
            attempt.ready.completeExceptionally(error)
            val requests = pending.filterValues { it.attempt === attempt }.toMap()
            for ((id, entry) in requests) {
                pending.remove(id)
                entry.result.completeExceptionally(
                    WakuRequestException(
                        if (entry.sent) WakuDelivery.RESULT_UNKNOWN else WakuDelivery.NOT_SENT,
                        id,
                        if (entry.sent) "连接中断，结果未知；请求未自动重发" else "请求未发送",
                        error,
                    ),
                )
            }
            attempt.socket?.cancel()
        }
    }

    private fun isActive(attempt: Attempt) = active === attempt && generation == attempt.generation

    private fun diagnostic(attempt: Attempt, failure: WakuConnectionFailure? = null, transport: String? = null) {
        runCatching {
            diagnostics(WakuConnectionDiagnostic(attempt.stage, clockMillis() - attempt.startedAtMillis, failure, transport))
        }
    }

    private fun closeError(code: Int) = connectionError(
        when (code) {
            1002 -> WakuConnectionFailure.PROTOCOL
            1008 -> WakuConnectionFailure.AUTHENTICATION
            else -> WakuConnectionFailure.CLOSED
        },
    )

    private fun connectionError(kind: WakuConnectionFailure) = WakuConnectionException(
        kind,
        when (kind) {
            WakuConnectionFailure.AUTHENTICATION -> "服务器拒绝认证，请检查 Token"
            WakuConnectionFailure.PROTOCOL -> "服务器协议不兼容，需要 Waku v$WAKU_PROTOCOL_VERSION"
            WakuConnectionFailure.HANDSHAKE -> "端点未返回有效的 Waku 握手"
            WakuConnectionFailure.TIMEOUT -> "连接握手超时"
            WakuConnectionFailure.NETWORK -> "无法连接服务器"
            WakuConnectionFailure.CLOSED -> "服务器连接已断开"
            WakuConnectionFailure.OVERFLOW -> "事件缓冲区已满，已断开连接；需要重新同步"
            WakuConnectionFailure.ABORTED -> "连接已关闭"
        },
    )

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    companion object {
        private val defaultSocketFactory: WebSocket.Factory = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
