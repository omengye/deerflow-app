package com.deerflow.app.data.proposal

import android.content.Context
import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.data.settings.SettingsStore
import com.deerflow.app.data.waku.WakuRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ProposalState(
    val proposals: List<SkillProposal> = emptyList(),
    val selected: SkillProposal? = null,
    val loading: Boolean = false,
    val loadingDetail: Boolean = false,
    val actionProposalId: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val pendingCount: Int get() = proposals.count { it.status == "pending_review" && it.approval == null }
}

class ProposalRepository private constructor(
    context: Context,
    private val settings: SettingsStore = SettingsStore(context.applicationContext),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow(ProposalState())
    val state: StateFlow<ProposalState> = mutableState.asStateFlow()
    private var profile: AppSettings? = null
    private var generation = 0L
    private var refreshJob: Job? = null
    private var detailJob: Job? = null
    private var actionJob: Job? = null
    init {
        scope.launch {
            settings.flow.collect { current ->
                if (current != profile) {
                    profile = current
                    generation++
                    refreshJob?.cancel(); detailJob?.cancel(); actionJob?.cancel()
                    mutableState.value = ProposalState()
                    if (current.isConfigured) refresh()
                }
            }
        }
    }
    fun refresh() {
        if (refreshJob?.isActive == true) return
        val current = profile ?: return
        if (!current.isConfigured) return
        val expected = generation
        refreshJob = scope.launch {
            mutableState.value = mutableState.value.copy(loading = true)
            try {
                val proposals = ProposalApi(current).listProposals()
                if (expected == generation) {
                    val selected = mutableState.value.selected?.let { old ->
                        proposals.find { it.id == old.id }?.let { updated ->
                            if (updated.stateVersion == old.stateVersion) old else updated
                        }
                    }
                    mutableState.value = mutableState.value.copy(proposals = proposals, selected = selected, loading = false, error = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (expected == generation) mutableState.value = mutableState.value.copy(loading = false, error = e.userMessage()) }
        }
    }
    fun select(proposalId: String) {
        detailJob?.cancel()
        val current = profile ?: return
        val expected = generation
        mutableState.value = mutableState.value.copy(selected = mutableState.value.proposals.find { it.id == proposalId }, loadingDetail = true, error = null)
        detailJob = scope.launch {
            try {
                val proposal = ProposalApi(current).getProposal(proposalId)
                if (expected == generation) mutableState.value = mutableState.value.copy(selected = proposal, loadingDetail = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (expected == generation) mutableState.value = mutableState.value.copy(loadingDetail = false, error = e.userMessage()) }
        }
    }
    fun clearSelection() { detailJob?.cancel(); mutableState.value = mutableState.value.copy(selected = null, loadingDetail = false) }
    fun approve(proposalId: String, note: String? = null) = review(proposalId, note, true)
    fun reject(proposalId: String, note: String? = null) = review(proposalId, note, false)
    private fun review(id: String, note: String?, approve: Boolean) {
        if (actionJob?.isActive == true || mutableState.value.loadingDetail) return
        val proposal = mutableState.value.selected?.takeIf { it.id == id && it.stateVersion > 0 && it.approval == null } ?: return
        if (proposal.status != "pending_review") return
        val current = profile ?: return
        val expected = generation
        actionJob = scope.launch {
            mutableState.value = mutableState.value.copy(actionProposalId = id, error = null, notice = null)
            try {
                val api = ProposalApi(current)
                val reviewed = if (approve) api.approve(proposal, note) else api.reject(proposal, note)
                if (expected == generation) mutableState.value = mutableState.value.copy(
                    proposals = mutableState.value.proposals.map { if (it.id == id) reviewed else it },
                    selected = reviewed, actionProposalId = null,
                    notice = if (!approve) "提案已拒绝" else publicationNotice(reviewed),
                )
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (expected == generation) {
                    mutableState.value = mutableState.value.copy(actionProposalId = null, error = e.userMessage())
                    // Read-only reconciliation; never replay an unconfirmed approval mutation.
                    select(id)
                }
            }
        }
    }
    fun clearMessage() { mutableState.value = mutableState.value.copy(notice = null) }
    private fun Throwable.userMessage(): String = when (this) {
        is ProposalApiException -> when (code) {
            "conflict" -> "提案版本已变化，请查看最新详情后再操作"
            "permission_denied" -> "服务器未授予技能管理权限"
            "not_found" -> "提案已不存在，请刷新列表"
            else -> message ?: "管理操作未完成"
        }
        is WakuRequestException -> "请求未确认完成，正在重新读取提案状态；请勿重复批准"
        else -> message ?: "无法连接技能管理服务"
    }
    companion object {
        @Volatile private var instance: ProposalRepository? = null
        fun get(context: Context): ProposalRepository = instance ?: synchronized(this) {
            instance ?: ProposalRepository(context.applicationContext).also { instance = it }
        }
    }
}

internal fun publicationNotice(proposal: SkillProposal): String = when ((proposal.publication?.get("state") as? JsonPrimitive)?.contentOrNull) {
    "published" -> "提案已批准并发布"
    "approved_pending", "publishing", "needs_reconciliation" -> "批准已保存，发布尚未完成；请刷新状态或在桌面继续处理"
    "blocked" -> "批准已保存，但发布被阻止；请查看详情"
    else -> if (proposal.status == "published") "提案已批准并发布" else "操作已返回，请核对最新提案状态"
}