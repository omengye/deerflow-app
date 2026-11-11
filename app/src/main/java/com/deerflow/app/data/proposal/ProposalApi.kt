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
internal interface ProposalService {
    suspend fun listProposals(cursor: String? = null): ProposalListResponse
    suspend fun getProposal(proposalId: String): SkillProposal
    suspend fun cancel(proposal: SkillProposal, note: String?): SkillProposal
}

internal class ProposalApi(private val settings: AppSettings) : ProposalService {
    override suspend fun listProposals(cursor: String?): ProposalListResponse = withClient { client ->
        ProposalJson.decodeFromJsonElement(manage(client, buildJsonObject {
            put("operation", "proposal.list")
            put("limit", 30)
            cursor?.let { put("cursor", it) }
        }))
    }

    override suspend fun getProposal(proposalId: String): SkillProposal = withClient { client ->
        ProposalJson.decodeFromJsonElement(manage(client, buildJsonObject {
            put("operation", "proposal.get")
            put("proposal_id", proposalId)
        }))
    }
    override suspend fun cancel(proposal: SkillProposal, note: String?): SkillProposal = withClient { client ->
        val data = manage(client, proposalCancellationCommand(proposal, note))
        ProposalJson.decodeFromJsonElement<SkillProposal>(data["proposal"] ?: error("服务器没有返回候选状态"))
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

internal fun proposalCancellationCommand(proposal: SkillProposal, note: String?): JsonObject = buildJsonObject {
    require(proposal.canCancel) { "当前候选不能取消，请刷新状态" }
    put("operation", "proposal.reject")
    put("proposal_id", proposal.id)
    put("expected_state_version", proposal.stateVersion)
    put("expected_base_sha256", proposal.baseSha256?.let(::JsonPrimitive) ?: JsonNull)
    note?.trim()?.takeIf(String::isNotBlank)?.let { put("note", it) }
}
