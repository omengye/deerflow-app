package com.deerflow.app.data.waku

import com.deerflow.app.domain.BlockKind
import com.deerflow.app.domain.ConversationState
import com.deerflow.app.domain.DisplayBlock
import com.deerflow.app.domain.ToolDetails
import com.deerflow.app.domain.model.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

/** A single durable unit: never persist a replay cursor separately from its projection. */
@Serializable
data class WakuSnapshot(
    val session: JsonObject,
    val permissions: List<JsonObject> = emptyList(),
    val userInputs: List<JsonObject> = emptyList(),
    val cursor: JsonObject? = null,
    val pendingCommands: List<JsonObject> = emptyList(),
    val runtimeId: String? = null,
    val retiredEpochs: List<String> = emptyList(),
    val remoteKnown: Boolean = false,
    val interactionsVerified: Boolean = false,
    val interactionReplay: WakuControlCheckpoint? = null,
    val artifactReplay: WakuControlCheckpoint? = null,
    val taskReady: JsonObject? = null,
)

/** Waku v10 wire projection. Unknown session fields survive round trips unchanged. */
object WakuProjection {
    fun createProject(id: String, name: String, path: String, nowMillis: Long = System.currentTimeMillis()) = buildJsonObject {
        put("id", id); put("name", name); put("path", path); put("created_at", nowMillis / 1000)
    }

    fun createSession(id: String, projectId: String, model: String? = null, nowMillis: Long = System.currentTimeMillis()) = buildJsonObject {
        put("id", id); put("title", "New task"); put("auto_title", JsonNull)
        put("project_id", projectId); put("workspace", obj("kind" to JsonPrimitive("local")))
        put("provider", "deerFlow"); put("model", primitive(model)); put("runtime_mode", WakuToolApproval.RUNTIME_MODE)
        put("reasoning_effort", JsonNull); put("thinking_mode", JsonNull); put("service_tier", JsonNull)
        put("context_window", JsonNull); put("agent_preset", JsonNull); put("status", "idle")
        put("created_at", nowMillis / 1000); put("updated_at", nowMillis / 1000); put("last_reply_at", JsonNull)
        put("provider_cursor", JsonNull); put("available_commands", JsonArray(emptyList()))
        put("messages", JsonArray(emptyList())); put("transcript_blocks", JsonArray(emptyList()))
        put("turns", JsonArray(emptyList())); put("queued_messages", JsonArray(emptyList()))
    }

    fun fromSession(session: JsonObject): WakuSnapshot {
        val stored = session["runtime_event_cursor"] as? JsonObject
        val cursor = stored?.let {
            obj("sessionId" to primitive(session.text("id")), "runtimeId" to primitive(it.text("runtime_id")),
                "epoch" to primitive(it.text("epoch")), "sequence" to (it["sequence"] ?: JsonPrimitive(0)))
        }
        return WakuSnapshot(WakuSessionTitles.withFallback(session), cursor = cursor, runtimeId = stored?.text("runtime_id"),
            interactionsVerified = session.text("status") != "waiting")
    }

    /** Call after AttachSession. Late events from an older runtime must not replace the active one. */
    fun bindRuntime(snapshot: WakuSnapshot, runtimeId: String): WakuSnapshot {
        if (snapshot.runtimeId == runtimeId) return snapshot
        return snapshot.copy(runtimeId = runtimeId, cursor = null, permissions = emptyList(), userInputs = emptyList(),
            retiredEpochs = emptyList(), session = snapshot.session.changed("runtime_event_cursor" to JsonNull),
            interactionsVerified = false, interactionReplay = null, artifactReplay = null, taskReady = null)
    }

    fun reduce(snapshot: WakuSnapshot, wire: JsonObject, nowMillis: Long = System.currentTimeMillis()): WakuSnapshot {
        val sessionId = wire.text("sessionId") ?: return snapshot
        val runtimeId = wire.text("runtimeId") ?: return snapshot
        val epoch = wire.text("epoch") ?: return snapshot
        val sequence = wire.number("sequence") ?: return snapshot
        if (sessionId != snapshot.session.text("id") || sequence <= 0 || wire.flag("replayable") == false) return snapshot
        if (snapshot.runtimeId != null && snapshot.runtimeId != runtimeId) return snapshot
        if (epoch in snapshot.retiredEpochs) return snapshot
        val previous = snapshot.cursor
        if (previous?.text("runtimeId") == runtimeId && previous.text("epoch") == epoch && sequence <= (previous.number("sequence") ?: 0)) return snapshot
        val event = wire["event"] as? JsonObject ?: return snapshot
        val kind = event.text("kind") ?: return snapshot
        val payload = event["payload"] ?: JsonNull
        val data = payload as? JsonObject
        val epochChanged = previous != null && previous.text("epoch") != epoch
        val mutable = MutableProjection(snapshot.session, nowMillis, "$sessionId/$runtimeId/$epoch/$sequence")
        var permissions = if (epochChanged) emptyList() else snapshot.permissions
        var userInputs = if (epochChanged) emptyList() else snapshot.userInputs
        if (epochChanged) mutable.finishStreams()
        when (kind) {
            "connected" -> {
                mutable.values["provider_cursor"] = payload
                if (mutable.status == "connecting") mutable.status = "working"
            }
            "autoTitleUpdated" -> mutable.values["auto_title"] = primitive(payload.stringOrNull()?.trim()?.takeIf(String::isNotEmpty))
            "agentPresetSelected" -> mutable.values["agent_preset"] = payload
            "toolApprovalChanged" -> when (payload.stringOrNull()) {
                "allow_always", "off" -> mutable.values["runtime_mode"] = JsonPrimitive(WakuToolApproval.RUNTIME_MODE)
                "ask", "reject_always" -> mutable.values["runtime_mode"] = JsonPrimitive("ask")
            }
            "availableCommands" -> if (payload is JsonArray) mutable.values["available_commands"] = payload
            "goalUpdated" -> mutable.values["thread_goal"] = payload
            "deerflowModelsChanged" -> if (data != null) {
                mutable.values["model"] = data["model"] ?: JsonNull
                mutable.values["reasoning_effort"] = data["reasoningEffort"] ?: JsonNull
                mutable.values["thinking_mode"] = data["thinkingMode"] ?: JsonNull
            }
            "usageUpdated" -> if (data != null) {
                val usage = (mutable.values["context_usage"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                data["contextTokens"]?.let { usage["tokens"] = it }
                data["contextWindow"]?.let { usage["window"] = it }
                mutable.values["context_usage"] = JsonObject(usage)
            }
            "promptSubmitted" -> if (data != null) mutable.prompt(data)
            "turnStarted" -> mutable.startTurn()
            "textDelta" -> payload.stringOrNull()?.let { mutable.text(it) }
            "reasoningDelta" -> payload.stringOrNull()?.let { mutable.reasoning(it) }
            "activity", "richActivity" -> if (data != null) mutable.activity(data)
            "permission" -> if (data?.text("requestId") != null) {
                permissions = permissions.filterNot { it.text("requestId") == data.text("requestId") } + data
                mutable.status = "waiting"
            }
            "userInputRequested" -> if (data?.text("requestId") != null) {
                userInputs = userInputs.filterNot { it.text("requestId") == data.text("requestId") } + data
                mutable.status = "waiting"
            }
            // Supported for a future server capability; current v10 resolves at the turn boundary.
            "permissionResolved" -> permissions = permissions.filterNot { it.text("requestId") == data?.text("requestId") }
            "userInputResolved" -> userInputs = userInputs.filterNot { it.text("requestId") == data?.text("requestId") }
            "turnParked" -> { mutable.finishStreams(); mutable.status = "background" }
            "turnFinished" -> {
                mutable.finishTurn(if (data?.flag("success") == true) "completed" else "failed", data?.text("summary"))
                permissions = emptyList(); userInputs = emptyList()
            }
            "processExited" -> {
                if (mutable.runningTurn != null) mutable.finishTurn("failed", "Agent process exited before completion.")
                permissions = emptyList(); userInputs = emptyList()
            }
            "error" -> payload.stringOrNull()?.let { mutable.error(it) }
        }
        val cursor = obj("sessionId" to JsonPrimitive(sessionId), "runtimeId" to JsonPrimitive(runtimeId),
            "epoch" to JsonPrimitive(epoch), "sequence" to JsonPrimitive(sequence))
        mutable.values["runtime_event_cursor"] = obj("runtime_id" to JsonPrimitive(runtimeId), "epoch" to JsonPrimitive(epoch), "sequence" to JsonPrimitive(sequence))
        return snapshot.copy(session = WakuSessionTitles.withFallback(mutable.build()), permissions = permissions, userInputs = userInputs, cursor = cursor,
            runtimeId = runtimeId, retiredEpochs = if (epochChanged) (snapshot.retiredEpochs + listOfNotNull(previous?.text("epoch"))).distinct() else snapshot.retiredEpochs,
            interactionsVerified = (snapshot.interactionsVerified && !epochChanged) || kind in setOf("promptSubmitted", "turnStarted", "turnFinished", "processExited"),
            interactionReplay = if (epochChanged) null else snapshot.interactionReplay,
            artifactReplay = if (epochChanged) null else snapshot.artifactReplay,
            taskReady = if (kind == "deerflowTasks" && data?.text("kind") == "ready") wire else if (epochChanged) null else snapshot.taskReady)
    }

    fun display(snapshot: WakuSnapshot): ConversationState {
        val session = snapshot.session
        val messages = session.objects("messages")
        val turns = session.objects("turns").mapIndexedNotNull { index, turn -> turn.text("id")?.let { it to (index + 1) } }.toMap()
        val blocks = mutableListOf<DisplayBlock>()
        val transcript = session.objects("transcript_blocks")
        fun appendWork(after: Int) {
            transcript.forEachIndexed { index, block ->
                if ((block.number("after_message") ?: 0).toInt().coerceIn(0, messages.size) != after) return@forEachIndexed
                val content = block["content"] as? JsonObject ?: return@forEachIndexed
                val turnIndex = turns[block.text("turn_id")]
                if (content.text("kind") == "reasoning") {
                    val reasoning = content["data"] as? JsonObject
                    blocks += DisplayBlock("waku:reasoning:$index", BlockKind.REASONING, "Reasoning", reasoning?.text("content").orEmpty(), turnIndex = turnIndex)
                } else {
                    (content["data"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEachIndexed { activityIndex, activity ->
                        val key = "waku:activity:${activity.text("id") ?: "$index:$activityIndex"}"
                        val reasoning = activity["reasoning"] as? JsonObject
                        if (activity.text("kind") == "reasoning" || reasoning != null) {
                            blocks += DisplayBlock(key, BlockKind.REASONING, "Reasoning", reasoning?.text("content") ?: activity.text("detail").orEmpty(), turnIndex = turnIndex)
                        } else {
                            val name = WakuToolPresentation.name(activity)
                            val detail = activity.text("detail").orEmpty()
                            blocks += DisplayBlock(key, BlockKind.TOOL, name, detail,
                                tool = ToolDetails(
                                    name = name,
                                    args = activity.text("arguments").orEmpty(),
                                    result = activity.text("output") ?: detail,
                                    isError = activity.flag("failed") == true,
                                    isRunning = activity.flag("complete") == false && activity.flag("failed") != true &&
                                        session.text("status") in setOf("connecting", "working", "waiting", "background"),
                                ), turnIndex = turnIndex)
                        }
                    }
                }
            }
        }
        appendWork(0)
        messages.forEachIndexed { index, message ->
            val role = message.text("role") ?: "assistant"
            val id = message.text("id") ?: stableId("${session.text("id")}/message/$index")
            val kind = when (role) { "user" -> BlockKind.USER; "system" -> BlockKind.SYSTEM; else -> BlockKind.ASSISTANT }
            blocks += DisplayBlock("waku:message:$id", kind, if (role == "user") "You" else "Assistant",
                message.text("display_content") ?: message.text("content").orEmpty(), messageId = id, turnIndex = turns[message.text("turn_id")])
            appendWork(index + 1)
        }
        val status = session.text("status") ?: "idle"
        return ConversationState(threadId = session.text("id").orEmpty(), status = status,
            running = status in setOf("connecting", "working", "waiting", "background"), blocks = blocks,
            history = messages.map { ChatMessage(role = it.text("role") ?: "assistant", id = it.text("id"), content = JsonPrimitive(it.text("display_content") ?: it.text("content").orEmpty())) })
    }

    private class MutableProjection(session: JsonObject, val now: Long, val eventKey: String) {
        val values = session.toMutableMap()
        val messages = session.objects("messages").toMutableList()
        val turns = session.objects("turns").toMutableList()
        val blocks = session.objects("transcript_blocks").toMutableList()
        var status: String
            get() = (values["status"] as? JsonPrimitive)?.contentOrNull ?: "idle"
            set(value) { values["status"] = JsonPrimitive(value) }
        val runningTurn: JsonObject? get() = turns.lastOrNull()?.takeIf { it.text("status") == "running" }
        fun id(suffix: String) = stableId("$eventKey/$suffix")
        fun prompt(data: JsonObject) {
            val content = data.text("message") ?: return
            val turnId = data.text("turnId") ?: id("turn")
            val messageId = data.text("messageId") ?: id("user")
            if (messages.any { it.text("id") == messageId }) return
            finishStreams()
            if (turns.none { it.text("id") == turnId }) turns += newTurn(turnId, false)
            messages += message(messageId, turnId, "user", content, false)
            status = "connecting"
            values["last_reply_at"] = JsonPrimitive(now / 1000)
        }
        fun newTurn(turnId: String, started: Boolean) = obj("id" to JsonPrimitive(turnId), "turn_count" to JsonPrimitive(turns.size + 1),
            "status" to JsonPrimitive("running"), "provider_turn_started" to JsonPrimitive(started), "provider_resume_at" to JsonNull,
            "started_at" to JsonPrimitive(now / 1000), "completed_at" to JsonNull, "checkpoint" to JsonNull)
        fun startTurn() {
            if (runningTurn == null) turns += newTurn(id("turn"), true)
            else turns[turns.lastIndex] = turns.last().changed("provider_turn_started" to JsonPrimitive(true))
            status = "working"
        }
        fun message(id: String, turn: String?, role: String, content: String, streaming: Boolean) = obj(
            "id" to JsonPrimitive(id), "turn_id" to primitive(turn), "role" to JsonPrimitive(role), "content" to JsonPrimitive(content),
            "created_at" to JsonPrimitive(now / 1000), "streaming" to JsonPrimitive(streaming), "attachments" to JsonArray(emptyList()))
        fun text(delta: String) {
            if (delta.isEmpty() || runningTurn == null) return
            finishReasoning()
            val last = messages.lastOrNull()
            if (last?.text("role") == "assistant" && last.flag("streaming") == true && last.text("turn_id") == runningTurn?.text("id")) {
                messages[messages.lastIndex] = last.changed("content" to JsonPrimitive(last.text("content").orEmpty() + delta))
            } else messages += message(id("assistant"), runningTurn?.text("id"), "assistant", delta, true)
            status = "working"
        }
        fun reasoning(delta: String) {
            if (delta.isEmpty() || runningTurn == null) return
            finishMessages()
            val blockIndex = blocks.lastIndex
            val last = blocks.lastOrNull()
            val content = last?.get("content") as? JsonObject
            val activities = (content?.get("data") as? JsonArray)?.filterIsInstance<JsonObject>()
            val existing = activities?.lastOrNull()
            val reasoning = existing?.get("reasoning") as? JsonObject
            if (last != null && content != null && activities != null && existing != null && existing.text("kind") == "reasoning" && existing.flag("complete") == false && reasoning != null && last.text("turn_id") == runningTurn?.text("id")) {
                val updated = existing.changed("reasoning" to reasoning.changed("content" to JsonPrimitive(reasoning.text("content").orEmpty() + delta), "finished_at_ms" to JsonPrimitive(now)))
                blocks[blockIndex] = last.changed("content" to content.changed("data" to JsonArray(activities.dropLast(1) + updated)))
            } else pushActivity(defaultActivity(id("reasoning")).changed("kind" to JsonPrimitive("reasoning"), "title" to JsonPrimitive("Reasoning"),
                "reasoning" to obj("content" to JsonPrimitive(delta), "started_at_ms" to JsonPrimitive(now), "finished_at_ms" to JsonPrimitive(now))))
        }
        fun defaultActivity(activityId: String) = obj("id" to JsonPrimitive(activityId), "source_id" to JsonNull,
            "kind" to JsonPrimitive("tool"), "title" to JsonPrimitive("Tool"), "detail" to JsonNull, "arguments" to JsonNull, "output" to JsonNull,
            "failed" to JsonPrimitive(false), "complete" to JsonPrimitive(false), "image_urls" to JsonArray(emptyList()),
            "file_changes" to JsonArray(emptyList()), "reasoning" to JsonNull)
        fun activity(incoming: JsonObject) {
            if (runningTurn == null) return
            finishMessages(); finishReasoning()
            val sourceId = incoming.text("source_id") ?: incoming.text("id")
            val normalized = JsonObject(defaultActivity(id("activity")).toMutableMap().apply {
                putAll(incoming); put("id", JsonPrimitive(id("activity"))); put("source_id", primitive(sourceId))
            })
            if (sourceId != null) for (index in blocks.indices.reversed()) {
                val block = blocks[index]
                if (block.text("turn_id") != runningTurn?.text("id")) continue
                val content = block["content"] as? JsonObject ?: continue
                val activities = (content["data"] as? JsonArray)?.filterIsInstance<JsonObject>()?.toMutableList() ?: continue
                val match = activities.indexOfFirst { it.text("source_id") == sourceId || it.text("id") == sourceId }
                if (match >= 0) {
                    val previous = activities[match]
                    activities[match] = JsonObject(previous.toMutableMap().apply { putAll(incoming.filterValues { it !is JsonNull }); put("id", previous["id"] ?: JsonPrimitive(id("activity"))); put("source_id", JsonPrimitive(sourceId)) })
                    blocks[index] = block.changed("content" to content.changed("data" to JsonArray(activities)))
                    return
                }
            }
            pushActivity(normalized)
        }
        fun pushActivity(activity: JsonObject) {
            val turn = runningTurn?.text("id")
            val last = blocks.lastOrNull()
            val content = last?.get("content") as? JsonObject
            if (last != null && content != null && last.number("after_message") == messages.size.toLong() && last.text("turn_id") == turn && content.text("kind") == "activities") {
                blocks[blocks.lastIndex] = last.changed("content" to content.changed("data" to JsonArray((content["data"] as? JsonArray).orEmpty() + activity)))
            } else blocks += obj("after_message" to JsonPrimitive(messages.size), "turn_id" to primitive(turn),
                "content" to obj("kind" to JsonPrimitive("activities"), "data" to JsonArray(listOf(activity))))
        }
        fun finishMessages() {
            for (i in messages.indices) if (messages[i].flag("streaming") == true) messages[i] = messages[i].changed("streaming" to JsonPrimitive(false))
        }
        fun finishReasoning() = completeActivities(reasoningOnly = true)
        fun completeActivities(reasoningOnly: Boolean = false) {
            for (i in blocks.indices) {
                val block = blocks[i]
                val content = block["content"] as? JsonObject ?: continue
                val activities = content["data"] as? JsonArray ?: continue
                blocks[i] = block.changed("content" to content.changed("data" to JsonArray(activities.map { item ->
                    val activity = item as? JsonObject ?: return@map item
                    if (!reasoningOnly || activity.text("kind") == "reasoning") activity.changed("complete" to JsonPrimitive(true)) else activity
                })))
            }
        }
        fun finishStreams() { finishMessages(); completeActivities() }
        fun finishTurn(result: String, summary: String?) {
            finishStreams()
            val turn = runningTurn
            if (turn != null) {
                val turnId = turn.text("id")
                if (messages.none { it.text("turn_id") == turnId && it.text("role") == "assistant" }) messages += message(id("summary"), turnId, "assistant", summary ?: if (result == "completed") "The turn completed without a text response." else "The turn stopped before a response.", false)
                turns[turns.lastIndex] = turn.changed("status" to JsonPrimitive(result), "completed_at" to JsonPrimitive(now / 1000))
            }
            values["last_reply_at"] = JsonPrimitive(now / 1000)
            status = if (result == "completed") "idle" else "failed"
        }
        fun error(message: String) {
            pushActivity(defaultActivity(id("error")).changed("title" to JsonPrimitive("Error"), "detail" to JsonPrimitive(message), "output" to JsonPrimitive(message), "failed" to JsonPrimitive(true), "complete" to JsonPrimitive(true)))
        }
        fun build(): JsonObject {
            values["messages"] = JsonArray(messages); values["turns"] = JsonArray(turns); values["transcript_blocks"] = JsonArray(blocks)
            values["updated_at"] = JsonPrimitive(now / 1000)
            return JsonObject(values)
        }
    }
}

private fun JsonObject.text(key: String): String? = this[key].stringOrNull()
private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
private fun JsonObject.changed(vararg entries: Pair<String, JsonElement>) = JsonObject(toMutableMap().apply { putAll(entries) })
private fun obj(vararg entries: Pair<String, JsonElement>) = JsonObject(mapOf(*entries))
private fun primitive(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
private fun stableId(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
