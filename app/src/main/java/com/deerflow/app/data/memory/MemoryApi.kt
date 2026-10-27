package com.deerflow.app.data.memory

import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.data.waku.WakuClient
import com.deerflow.app.data.waku.wakuCommand
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val MemoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable data class PersonalMemory(
    val id: String, val key: String, val revision: Long, val content: String, val category: String,
    @SerialName("updated_at") val updatedAt: String = "",
    val source: JsonObject = buildJsonObject {},
)
@Serializable data class MemoryPage(
    @SerialName("profile_id") val profileId: String,
    val facts: List<PersonalMemory> = emptyList(),
    val versions: Map<String, Long> = emptyMap(),
    @SerialName("next_cursor") val nextCursor: String = "",
    val jobs: Map<String, Int> = emptyMap(),
)
@Serializable data class MemoryOperation(
    @SerialName("profile_id") val profileId: String,
    @SerialName("operation_id") val id: String = UUID.randomUUID().toString(),
    val action: String,
    val key: String,
    val content: String = "",
    val category: String = "profile",
    @SerialName("expected_revision") val expectedRevision: Long,
    val operation: String = "memory.personal.mutate",
)
@Serializable data class MemoryReceipt(
    @SerialName("operation_id") val id: String,
    val status: String,
    @SerialName("fact_id") val factId: String,
    val revision: Long,
    val key: String,
    val scope: String,
)
class MemoryApiException(val code: String, message: String) : IOException(message)

interface PersonalMemoryApi {
    suspend fun list(): MemoryPage
    suspend fun receipt(id: String): MemoryReceipt?
    suspend fun mutate(operation: MemoryOperation): MemoryReceipt
}

class MemoryApi(private val settings: AppSettings) : PersonalMemoryApi {
    override suspend fun list(): MemoryPage {
        var first: MemoryPage? = null
        val facts = mutableListOf<PersonalMemory>()
        val cursors = mutableSetOf<String>()
        var cursor = ""
        do {
            val page = MemoryJson.decodeFromJsonElement<MemoryPage>(manage(buildJsonObject {
                put("operation", "memory.personal.get")
                if (cursor.isNotBlank()) put("cursor", cursor)
            }))
            check(first == null || first.profileId == page.profileId) { "服务器记忆身份已变更，请刷新" }
            if (first == null) first = page
            facts += page.facts
            cursor = page.nextCursor
            check(cursor.isBlank() || cursors.add(cursor) && cursors.size < 100) { "记忆分页异常，请刷新" }
        } while (cursor.isNotBlank())
        return checkNotNull(first).copy(facts = facts.distinctBy { it.id }, nextCursor = "")
    }
    override suspend fun receipt(id: String): MemoryReceipt? {
        val data = manage(buildJsonObject { put("operation", "memory.personal.receipt"); put("operation_id", id) })
        return if (data["found"]?.jsonPrimitive?.booleanOrNull == true)
            MemoryJson.decodeFromJsonElement(data["receipt"] ?: error("保存回执缺失")) else null
    }
    override suspend fun mutate(operation: MemoryOperation): MemoryReceipt =
        MemoryJson.decodeFromJsonElement(manage(MemoryJson.encodeToJsonElement(operation).jsonObject))

    private suspend fun manage(input: JsonObject): JsonObject {
        require(settings.isConfigured) { "请先配置服务连接" }
        val client = WakuClient(settings.endpoint, settings.token, UUID.randomUUID().toString())
        return coroutineScope {
            val drain = launch { for (ignored in client.incoming) { } }
            try {
                client.connect()
                val response = client.request(wakuCommand("deerFlow") {
                    put("operation", "manage-envelope"); put("input", input)
                }, timeoutMs = 15_000)
                val envelope = response["data"] as? JsonObject ?: error("无效的记忆响应")
                if (envelope["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    val code = envelope["code"]?.jsonPrimitive?.contentOrNull ?: "management_error"
                    throw MemoryApiException(code, envelope["error"]?.jsonPrimitive?.contentOrNull ?: "记忆操作未完成")
                }
                envelope["data"] as? JsonObject ?: error("无效的记忆数据")
            } finally { client.close(); drain.cancel() }
        }
    }
}

/** A pending operation survives screen/process death. Only this idempotent memory
 * contract permits resubmitting the exact saved operation after checking receipts. */
class MemoryRecovery(
    private val api: PersonalMemoryApi,
    private val readPending: suspend () -> MemoryOperation?,
    private val savePending: suspend (MemoryOperation?) -> Unit,
) {
    suspend fun submit(operation: MemoryOperation): MemoryReceipt {
        check(readPending() == null) { "请先核对上一次保存结果" }
        savePending(operation)
        return checkNotNull(reconcile())
    }
    suspend fun reconcile(): MemoryReceipt? {
        val pending = readPending() ?: return null
        val snapshot = api.list()
        check(snapshot.profileId == pending.profileId) { "服务器记忆身份已变更，待核对操作已保留" }
        try {
            val receipt = api.receipt(pending.id) ?: api.mutate(pending)
            check(receipt.id == pending.id && receipt.key == pending.key && receipt.scope == "global" &&
                receipt.revision > 0 && receipt.status in setOf("saved", "updated", "forgotten", "unchanged")) {
                "服务器回执不匹配，正在核对保存结果"
            }
            savePending(null)
            return receipt
        } catch (error: MemoryApiException) {
            if (error.code in setOf("memory_conflict", "invalid_request")) savePending(null)
            throw error
        }
    }
}
