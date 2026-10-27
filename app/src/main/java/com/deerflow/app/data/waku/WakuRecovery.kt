package com.deerflow.app.data.waku

import kotlinx.serialization.json.*
import kotlin.math.min
import kotlin.random.Random

/** Recovery decisions are independent of Android and never submit provider work. */
object WakuRecovery {
    fun retryDelayMillis(attempt: Int, random: Double = Random.nextDouble()): Long {
        val ceiling = min(30_000L, 1_000L * (1L shl attempt.coerceIn(0, 5)))
        return (ceiling * (0.5 + random.coerceIn(0.0, 1.0) * 0.5)).toLong()
    }

    fun chooseSnapshot(local: WakuSnapshot?, remote: JsonObject): WakuSnapshot {
        if (local == null) return WakuProjection.fromSession(remote)
        val serverCursor = remote["runtime_event_cursor"] as? JsonObject
        val localCursor = local.session["runtime_event_cursor"] as? JsonObject
        val sameStream = serverCursor != null && localCursor != null &&
            serverCursor["epoch"] == localCursor["epoch"] &&
            serverCursor["runtime_id"] == localCursor["runtime_id"]
        val localAhead = localStreamAhead(local.session, remote)
        // Only a strictly newer cursor on the same stream can beat a server snapshot.
        // Explicit task metadata can change independently of the runtime journal.
        if (localAhead) return local.copy(session = JsonObject(local.session.toMutableMap().apply {
            listOf("title", "project_id", "workspace").forEach { key -> remote[key]?.let { put(key, it) } }
        }))
        val preserveInteractions = sameStream && remote.string("status") == "waiting"
        return WakuProjection.fromSession(remote).copy(
            pendingCommands = local.pendingCommands,
            permissions = if (preserveInteractions) local.permissions else emptyList(),
            userInputs = if (preserveInteractions) local.userInputs else emptyList(),
        )
    }

    internal fun localStreamAhead(local: JsonObject, remote: JsonObject): Boolean {
        val server = remote["runtime_event_cursor"] as? JsonObject ?: return false
        val cached = local["runtime_event_cursor"] as? JsonObject ?: return false
        return server["epoch"] == cached["epoch"] && server["runtime_id"] == cached["runtime_id"] &&
            cached.long("sequence") > server.long("sequence")
    }

    fun resolveObservedCommands(snapshot: WakuSnapshot): WakuSnapshot {
        val ids = snapshot.session.array("messages").mapNotNull { (it as? JsonObject)?.string("id") }.toSet()
        val remaining = snapshot.pendingCommands.filterNot { command ->
            command.string("kind") == "prompt" && !command.string("messageId").isNullOrBlank() && command.string("messageId") in ids
        }
        return snapshot.copy(pendingCommands = remaining)
    }

    fun validateReplayPage(page: JsonObject, runtimeId: String, sessionId: String, after: Long, through: Long?): Long {
        require(page.string("type") == "replayEvents") { "服务器返回了无效的事件页" }
        require(after >= 0) { "事件游标无效" }
        val epoch = page.string("epoch")?.takeIf { it.isNotBlank() } ?: error("事件页缺少 epoch")
        fun number(key: String): Long {
            val primitive = page[key] as? JsonPrimitive ?: error("事件页缺少 $key")
            require(!primitive.isString) { "事件页 $key 类型错误" }
            return primitive.longOrNull ?: error("事件页 $key 无效")
        }
        val upper = number("throughSequence")
        require(through == null || through == upper) { "重放期间事件边界发生变化" }
        val next = number("nextSequence")
        require(next in after..upper) { "事件游标越界" }
        val hasMoreValue = page["hasMore"] as? JsonPrimitive ?: error("事件页缺少 hasMore")
        require(!hasMoreValue.isString) { "事件页 hasMore 类型错误" }
        val hasMore = hasMoreValue.booleanOrNull ?: error("事件页 hasMore 无效")
        val events = page["events"] as? JsonArray ?: error("事件页缺少 events")
        var previous = after
        for (element in events) {
            val event = element as? JsonObject ?: error("无效的事件")
            require(event.string("sessionId") == sessionId && event.string("runtimeId") == runtimeId) { "事件属于其他会话" }
            require(event.string("epoch") == epoch) { "事件版本不一致" }
            val sequenceValue = event["sequence"] as? JsonPrimitive ?: error("事件缺少游标")
            require(!sequenceValue.isString) { "事件游标类型错误" }
            val sequence = sequenceValue.longOrNull ?: error("事件游标无效")
            require(sequence > previous && sequence <= next) { "事件顺序无效" }
            previous = sequence
        }
        require(!hasMore || (next > after && events.isNotEmpty() && next == previous)) { "事件重放未推进" }
        require(hasMore || next == upper) { "最后一页未达到重放边界" }
        return upper
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.updated(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(toMutableMap().apply { putAll(entries) })
