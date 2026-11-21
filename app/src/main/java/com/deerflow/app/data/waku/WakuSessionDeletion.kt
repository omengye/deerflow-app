package com.deerflow.app.data.waku

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Prepare a durable record without uploading history; only an ACK permits local cleanup. */
class WakuSessionDeletion(
    private val prepare: suspend () -> Unit,
    private val record: suspend (JsonObject) -> Unit,
    private val settle: suspend (requestId: String, state: String?, error: String?) -> Unit,
    private val request: suspend (requestId: String, timeoutMs: Long) -> JsonObject,
) {
    suspend fun remove() {
        prepare()
        val requestId = UUID.randomUUID().toString()
        var dispatched = false
        try {
            record(buildJsonObject {
                put("kind", "removeSession"); put("requestId", requestId); put("state", "sending")
            })
            // The daemon can wait 35 seconds for native deletion before replying.
            dispatched = true
            val result = request(requestId, 60_000)
            if (result.string("type") != "ack") throw WakuRequestException(
                WakuDelivery.RESULT_UNKNOWN, requestId, "服务器未确认删除结果，请重新同步后核对",
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                settle(requestId, if (dispatched) "unknown" else null, "删除被中断，结果尚未确认，请重新同步后核对")
            }
            throw cancelled
        } catch (error: Exception) {
            val uncertain = dispatched && (error !is WakuRequestException || error.delivery == WakuDelivery.RESULT_UNKNOWN ||
                error.message.orEmpty().contains("删除结果尚未确认"))
            // A definite refusal is finished, so it must not block the user's next prompt or stop action.
            settle(requestId, if (uncertain) "unknown" else null, error.message)
            throw error
        }
    }
}
