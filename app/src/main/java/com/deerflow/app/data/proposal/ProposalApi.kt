package com.deerflow.app.data.proposal

import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.data.waku.WakuClient
import kotlinx.serialization.json.Json
import com.deerflow.app.data.waku.wakuCommand
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

internal val ProposalJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

class ProposalApiException(val code: String, message: String) : IOException(message)

/** Skill governance uses the same authenticated Waku gateway as chat. */
class ProposalApi(private val settings: AppSettings) {
    suspend fun listProposals(): List<SkillProposal> = withClient { client ->
        val proposals = mutableListOf<SkillProposal>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = manage(client, buildJsonObject {
                put("operation", "proposal.list")
                put("limit", 100)
                cursor?.let { put("cursor", it) }
            })
            proposals += ProposalJson.decodeFromJsonElement<ProposalListResponse>(page).proposals
            cursor = (page["next_cursor"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            check(cursor == null || (seenCursors.add(cursor!!) && seenCursors.size < 100)) { "提案列表分页无效，请刷新" }
        } while (cursor != null)
        proposals.distinctBy { it.id }
    }

    suspend fun getProposal(proposalId: String): SkillProposal = withClient { client ->
        ProposalJson.decodeFromJsonElement(manage(client, buildJsonObject {
            put("operation", "proposal.get")
            put("proposal_id", proposalId)
        }))
    }
    suspend fun approve(proposal: SkillProposal, note: String?): SkillProposal = review(proposal, "approve", note)
    suspend fun reject(proposal: SkillProposal, note: String?): SkillProposal = review(proposal, "reject", note)

    private suspend fun review(proposal: SkillProposal, decision: String, note: String?): SkillProposal = withClient { client ->
        require(proposal.stateVersion > 0) { "请刷新提案详情后再操作" }
        require(proposal.approval == null) { "批准已保存，请刷新确认发布结果" }
        val data = manage(client, proposalReviewCommand(proposal, decision, note))
        ProposalJson.decodeFromJsonElement<SkillProposal>(data["proposal"] ?: error("服务器没有返回提案状态"))
    }

    private suspend fun manage(client: WakuClient, input: JsonObject): JsonObject {
        val response = client.request(wakuCommand("deerFlow") {
            put("operation", "manage-envelope")
            put("input", input)
        })
        val envelope = response["data"] as? JsonObject ?: error("无效的管理响应")
        if ((envelope["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            val code = (envelope["code"] as? JsonPrimitive)?.contentOrNull ?: "management_error"
            throw ProposalApiException(code, (envelope["error"] as? JsonPrimitive)?.contentOrNull ?: "管理操作未完成")
        }
        return envelope["data"] as? JsonObject ?: error("无效的提案响应")
    }

    private suspend fun <T> withClient(block: suspend (WakuClient) -> T): T {
        require(settings.isConfigured) { "请先配置 Waku 服务连接" }
        val client = WakuClient(settings.endpoint, settings.token, UUID.randomUUID().toString())
        return coroutineScope {
            val drain = launch { for (ignored in client.incoming) { /* management does not project chat events */ } }
            try { client.connect(); block(client) }
            finally { client.close(); drain.cancel() }
        }
    }
}

internal fun proposalReviewCommand(proposal: SkillProposal, decision: String, note: String?): JsonObject = buildJsonObject {
    require(decision == "approve" || decision == "reject")
    require(proposal.stateVersion > 0)
    put("operation", "proposal.$decision")
    put("proposal_id", proposal.id)
    put("expected_state_version", proposal.stateVersion)
    put("expected_base_sha256", proposal.baseSha256?.let(::JsonPrimitive) ?: JsonNull)
    note?.trim()?.takeIf(String::isNotBlank)?.let { put("note", it) }
    if (decision == "approve" && proposal.scriptReviewRequired) put("acknowledge_unverified_scripts", true)
}