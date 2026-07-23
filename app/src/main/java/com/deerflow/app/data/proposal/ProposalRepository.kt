package com.deerflow.app.data.proposal

import android.content.Context
import com.deerflow.app.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProposalState(
    val proposals: List<SkillProposal> = emptyList(),
    val selected: SkillProposal? = null,
    val loading: Boolean = false,
    val loadingDetail: Boolean = false,
    val actionProposalId: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val pendingCount: Int get() = proposals.count { it.status == "pending_review" }
}

/** App-scoped source of truth for Proposal list/detail/action state. */
class ProposalRepository private constructor(
    context: Context,
    private val settings: SettingsStore = SettingsStore(context.applicationContext),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(ProposalState())
    val state: StateFlow<ProposalState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var detailJob: Job? = null
    private var actionJob: Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching { api().listProposals() }
                .onSuccess { proposals ->
                    val selected = _state.value.selected?.let { current ->
                        proposals.firstOrNull { it.id == current.id }?.mergeDetailFrom(current)
                            ?: current
                    }
                    _state.value = _state.value.copy(
                        proposals = proposals,
                        selected = selected,
                        loading = false,
                        error = null,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(loading = false, error = error.userMessage())
                }
        }
    }

    fun select(proposalId: String) {
        detailJob?.cancel()
        val summary = _state.value.proposals.firstOrNull { it.id == proposalId }
        _state.value = _state.value.copy(
            selected = summary,
            loadingDetail = true,
            error = null,
        )
        detailJob = scope.launch {
            runCatching { api().getProposal(proposalId) }
                .onSuccess { proposal ->
                    _state.value = _state.value.copy(selected = proposal, loadingDetail = false)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loadingDetail = false,
                        error = error.userMessage(),
                    )
                }
        }
    }

    fun clearSelection() {
        detailJob?.cancel()
        _state.value = _state.value.copy(selected = null, loadingDetail = false)
    }

    fun approve(proposalId: String, note: String? = null) = review(proposalId, note, approve = true)

    fun reject(proposalId: String, note: String? = null) = review(proposalId, note, approve = false)

    private fun review(proposalId: String, note: String?, approve: Boolean) {
        if (actionJob?.isActive == true) return
        val proposal = _state.value.selected?.takeIf { it.id == proposalId }
            ?: _state.value.proposals.firstOrNull { it.id == proposalId }
            ?: return
        if (proposal.status != "pending_review") return

        actionJob = scope.launch {
            _state.value = _state.value.copy(
                actionProposalId = proposalId,
                error = null,
                notice = null,
            )
            runCatching {
                if (approve) api().approve(proposal, note) else api().reject(proposal, note)
            }.onSuccess { reviewed ->
                val previousDetail = _state.value.selected?.takeIf { it.id == reviewed.id }
                val merged = reviewed.mergeDetailFrom(previousDetail)
                _state.value = _state.value.copy(
                    proposals = _state.value.proposals.map { if (it.id == reviewed.id) merged else it },
                    selected = if (previousDetail != null) merged else _state.value.selected,
                    actionProposalId = null,
                    notice = if (approve) "Proposal 已批准并发布" else "Proposal 已拒绝",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    actionProposalId = null,
                    error = error.userMessage(),
                )
                if (error is ProposalApiException && error.statusCode == 409) refresh()
            }
        }
    }

    fun clearMessage() {
        // Keep errors stable until a successful retry or a new user action.
        // This prevents the foreground poller from showing the same failure
        // every 30 seconds while still allowing success notices to be consumed.
        _state.value = _state.value.copy(notice = null)
    }

    private suspend fun api(): ProposalApi {
        val current = settings.current()
        return ProposalApi(current.endpoint, current.headers())
    }

    private fun SkillProposal.mergeDetailFrom(detail: SkillProposal?): SkillProposal =
        if (diff != null || detail?.diff == null) this else copy(diff = detail.diff)

    private fun Throwable.userMessage(): String = when (this) {
        is ProposalApiException -> when (statusCode) {
            401 -> "认证失败，请检查 Settings 中的 Bearer Token"
            404 -> "Proposal API 不可用，请确认后端已启用认证和 Admin API"
            409 -> "Proposal 状态或基础版本已变化，请刷新后重试"
            else -> message.orEmpty()
        }
        else -> message ?: javaClass.simpleName
    }

    companion object {
        @Volatile
        private var instance: ProposalRepository? = null

        fun get(context: Context): ProposalRepository =
            instance ?: synchronized(this) {
                instance ?: ProposalRepository(context.applicationContext).also { instance = it }
            }
    }
}
