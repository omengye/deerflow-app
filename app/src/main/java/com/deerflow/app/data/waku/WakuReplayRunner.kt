package com.deerflow.app.data.waku

import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class ReplayEpochChanged(val actualEpoch: String) : IOException("服务器已重启，正在重新校验会话")

@Serializable
data class WakuReplayPosition(val runtimeId: String, val epoch: String, val sequence: Long)

data class WakuReplayPage(val position: WakuReplayPosition, val through: Long, val events: List<JsonObject>, val hasMore: Boolean) {
    val percent: Int get() = if (through == 0L) 100 else (position.sequence.toDouble() / through * 100).toInt().coerceIn(0, 100)
}

/** Read-only recovery. A page is committed before advancing; cancellation never retries commands. */
class WakuReplayRunner(private val pageSize: Int = 64, private val pageTimeoutMs: Long = 30_000) {
    init { require(pageSize in 1..256 && pageTimeoutMs > 0) }

    suspend fun run(
        sessionId: String,
        runtimeId: String,
        start: WakuReplayPosition?,
        through: Long? = null,
        request: suspend (JsonObject) -> JsonObject,
        commit: suspend (WakuReplayPage) -> Unit,
    ): WakuReplayPosition {
        require(start == null || start.runtimeId == runtimeId)
        var epoch = start?.epoch
        var after = start?.sequence ?: 0L
        var upper = through
        while (true) {
            currentCoroutineContext().ensureActive()
            val response = try {
                withTimeout(pageTimeoutMs) {
                    request(wakuCommand("replayEvents") {
                        put("epoch", epoch?.let(::JsonPrimitive) ?: JsonNull)
                        put("afterSequence", after)
                        put("throughSequence", upper?.let(::JsonPrimitive) ?: JsonNull)
                        put("limit", pageSize)
                    })
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IOException("同步响应超时，已保留恢复进度，将自动重试")
            }
            currentCoroutineContext().ensureActive()
            val nextEpoch = response.string("epoch") ?: throw IOException("事件页缺少服务器版本")
            if (epoch != null && epoch != nextEpoch) throw ReplayEpochChanged(nextEpoch)
            val boundary = WakuRecovery.validateReplayPage(response, runtimeId, sessionId, after, upper)
            val position = WakuReplayPosition(runtimeId, nextEpoch, response.long("nextSequence"))
            val page = WakuReplayPage(position, boundary, response.array("events").filterIsInstance<JsonObject>(), response.bool("hasMore"))
            commit(page)
            currentCoroutineContext().ensureActive()
            if (!page.hasMore) return position
            epoch = nextEpoch; after = position.sequence; upper = boundary
        }
    }
}

/** Separate checkpoint for v10 control history, which a hydrated transcript does not contain. */
@Serializable
data class WakuControlCheckpoint(
    val position: WakuReplayPosition? = null,
    val permissions: List<JsonObject> = emptyList(),
    val questions: List<JsonObject> = emptyList(),
    val taskReady: JsonObject? = null,
) {
    fun advance(page: WakuReplayPage): WakuControlCheckpoint {
        require(position == null || (position.runtimeId == page.position.runtimeId && position.epoch == page.position.epoch))
        var permissions = permissions
        var questions = questions
        var ready = taskReady
        for (wire in page.events) {
            val event = wire["event"] as? JsonObject ?: continue
            val payload = event["payload"] as? JsonObject
            when (event.string("kind")) {
                "permission" -> if (payload?.string("requestId") != null) permissions = permissions.filterNot { it.string("requestId") == payload.string("requestId") } + payload
                "userInputRequested" -> if (payload?.string("requestId") != null) questions = questions.filterNot { it.string("requestId") == payload.string("requestId") } + payload
                "promptSubmitted", "turnStarted", "turnFinished", "processExited" -> { permissions = emptyList(); questions = emptyList() }
                "permissionResolved" -> permissions = permissions.filterNot { it.string("requestId") == payload?.string("requestId") }
                "userInputResolved" -> questions = questions.filterNot { it.string("requestId") == payload?.string("requestId") }
                "deerflowTasks" -> if (payload?.string("kind") == "ready") ready = wire
            }
        }
        return copy(position = page.position, permissions = permissions, questions = questions, taskReady = ready)
    }
}

fun WakuSnapshot.replayPosition(): WakuReplayPosition? {
    val c = cursor ?: return null
    val runtime = c.string("runtimeId") ?: return null
    val epoch = c.string("epoch")?.takeIf(String::isNotBlank) ?: return null
    val seq = (c["sequence"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: return null
    return WakuReplayPosition(runtime, epoch, seq).takeIf { runtime == runtimeId && c.string("sessionId") == session.string("id") }
}

fun WakuSnapshot.atReplayPosition(position: WakuReplayPosition): WakuSnapshot = copy(
    runtimeId = position.runtimeId,
    cursor = buildJsonObject {
        put("sessionId", session.string("id")); put("runtimeId", position.runtimeId)
        put("epoch", position.epoch); put("sequence", position.sequence)
    },
    session = session.updated("runtime_event_cursor" to buildJsonObject {
        put("runtime_id", position.runtimeId); put("epoch", position.epoch); put("sequence", position.sequence)
    }),
)
