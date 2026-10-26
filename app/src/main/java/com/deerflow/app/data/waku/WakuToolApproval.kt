package com.deerflow.app.data.waku

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One connection's authoritative policy; persisted session preferences are not evidence. */
class WakuToolApproval(
    private val confirmationTimeoutMs: Long = 30_000,
    private val send: suspend (JsonObject, String, String) -> JsonObject,
) {
    companion object {
        const val RUNTIME_MODE = "fullAccess"
        private val FULL_ACCESS = setOf("allow_always", "off")
        private val POLICIES = FULL_ACCESS + setOf("global", "ask", "reject_always", "read_only")
    }

    private data class Key(val sessionId: String, val runtimeId: String)
    private class Policy {
        val operation = Mutex()
        var epoch: String? = null
        var sequence = -1L
        var mode: String? = null
        var attemptedAtSequence: Long? = null
        var changed = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private val policies = mutableMapOf<Key, Policy>()
    private val disconnectSignal = CompletableDeferred<Unit>()
    private var closed = false

    init {
        require(confirmationTimeoutMs > 0)
    }

    /** Accept both live event envelopes and validated replay entries. Never answer permission cards. */
    fun onWireEvent(wire: JsonObject) {
        val type = wire.string("type")
        if (type != null && type != "event") return
        val event = wire["event"] as? JsonObject ?: return
        if (event.string("kind") != "toolApprovalChanged") return
        val payload = event["payload"] as? JsonPrimitive ?: return
        if (!payload.isString) return
        val mode = payload.contentOrNull?.takeIf { it in POLICIES } ?: return
        val sessionId = wire.string("sessionId") ?: return
        val runtimeId = wire.string("runtimeId") ?: return
        val epoch = wire.string("epoch") ?: return
        if (listOf(sessionId, runtimeId, epoch).any {
                runCatching { requireWakuUuid(it, "事件标识") }.isFailure
            }) return
        val sequenceValue = wire["sequence"] as? JsonPrimitive ?: return
        if (sequenceValue.isString) return
        val sequence = sequenceValue.longOrNull?.takeIf { it >= 0 } ?: return
        synchronized(lock) {
            if (closed) return
            val policy = policies.getOrPut(Key(sessionId, runtimeId)) { Policy() }
            // A daemon restart requires a fresh connection/helper. Late foreign epochs are stale.
            if (policy.epoch != null && policy.epoch != epoch) return
            if (sequence <= policy.sequence) return
            policy.epoch = epoch
            policy.sequence = sequence
            policy.mode = mode
            policy.changed.complete(Unit)
            policy.changed = CompletableDeferred()
        }
    }

    suspend fun ensureFullAccess(sessionId: String, runtimeId: String) {
        requireWakuUuid(sessionId, "会话标识")
        requireWakuUuid(runtimeId, "运行时标识")
        val completed = withTimeoutOrNull(confirmationTimeoutMs) {
            coroutineScope {
                val worker = async { confirm(Key(sessionId, runtimeId)) }
                try {
                    select<Unit> {
                        disconnectSignal.onAwait { throw disconnectedError() }
                        worker.onAwait { }
                    }
                } finally {
                    worker.cancel()
                }
            }
            true
        }
        if (completed != true) {
            throw IOException("等待 Full Access 权限确认超时，消息尚未发送；请重新连接后重试。")
        }
    }

    private suspend fun confirm(key: Key) {
        val policy = synchronized(lock) {
            checkConnected()
            policies.getOrPut(key) { Policy() }
        }
        policy.operation.withLock {
            while (true) {
                val pending = synchronized(lock) {
                    checkConnected()
                    if (policy.mode != null) null else policy.changed
                } ?: break
                pending.await()
            }
            val sequenceAtSend = synchronized(lock) {
                checkConnected()
                if (policy.mode in FULL_ACCESS) null
                else {
                    if (policy.attemptedAtSequence == policy.sequence) {
                        throw IOException("上次权限切换尚未得到确认，消息尚未发送；请重新连接后重试。")
                    }
                    policy.attemptedAtSequence = policy.sequence
                    policy.sequence
                }
            } ?: return@withLock

            // A failed/unknown request is never retried here, and never authorizes a prompt.
            val response = send(wakuCommand("setToolApproval") { put("mode", "allow_always") },
                key.sessionId, key.runtimeId)
            if (response.string("type") != "toolApproval" ||
                (response["mode"] as? JsonPrimitive)?.let { it.isString && it.content == "allow_always" } != true) {
                throw IOException("服务器未确认 Full Access 权限，消息尚未发送。")
            }
            synchronized(lock) {
                checkConnected()
                if (policy.sequence > sequenceAtSend && policy.mode !in FULL_ACCESS) {
                    throw IOException("会话权限在切换期间发生变化，消息尚未发送；请重新连接后重试。")
                }
                policy.mode = "allow_always"
                policy.changed.complete(Unit)
                policy.changed = CompletableDeferred()
            }
        }
    }

    /** Permanently fence this connection, including in-flight replies and late events. */
    fun disconnected() {
        synchronized(lock) {
            if (closed) return
            closed = true
            policies.clear()
        }
        disconnectSignal.complete(Unit)
    }

    private fun checkConnected() {
        if (closed) throw disconnectedError()
    }

    private fun disconnectedError() = IOException("连接已断开，Full Access 权限未确认，消息尚未发送。")
}
