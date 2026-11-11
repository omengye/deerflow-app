package com.deerflow.app.data.proposal

import android.content.Context
import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.data.settings.SettingsStore
import com.deerflow.app.data.waku.WakuDelivery
import com.deerflow.app.data.waku.WakuConnectionException
import com.deerflow.app.data.waku.WakuRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProposalState(
    val configured: Boolean = false,
    val proposals: List<SkillProposal> = emptyList(),
    val selectedId: String? = null,
    val selected: SkillProposal? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loadingDetail: Boolean = false,
    val detailFresh: Boolean = false,
    val detailError: String? = null,
    val nextCursor: String? = null,
    val loadedPages: Int = 0,
    val actionProposalId: String? = null,
    val unconfirmedCancellations: Set<String> = emptySet(),
    val error: String? = null,
    val listError: String? = null,
    val notice: String? = null,
)

class ProposalRepository internal constructor(
    settings: Flow<AppSettings>,
    private val scope: CoroutineScope,
    private val apiFactory: (AppSettings) -> ProposalService = ::ProposalApi,
) {
    private val mutableState = MutableStateFlow(ProposalState())
    val state: StateFlow<ProposalState> = mutableState.asStateFlow()
    private var profile: AppSettings? = null
    private var generation = 0L
    private var listRevision = 0L
    private var detailRevision = 0L
    private var refreshJob: Job? = null
    private var pageJob: Job? = null
    private var detailJob: Job? = null
    private var actionJob: Job? = null
    private val seenCursors = mutableSetOf<String>()

    init {
        scope.launch {
            settings.collect { current ->
                val previous = profile
                profile = current
                if (previous == null || previous.endpoint != current.endpoint || previous.token != current.token ||
                    previous.isConfigured != current.isConfigured) {
                    generation++
                    listRevision++
                    detailRevision++
                    refreshJob?.cancel(); pageJob?.cancel(); detailJob?.cancel(); actionJob?.cancel()
                    seenCursors.clear()
                    mutableState.value = ProposalState(configured = current.isConfigured)
                    if (current.isConfigured) refresh()
                }
            }
        }
    }

    /** Refresh starts at the newest page, but never replaces detail with a list summary. */
    fun refresh() {
        if (refreshJob?.isActive == true || actionJob?.isActive == true) return
        val current = profile?.takeIf { it.isConfigured } ?: return
        pageJob?.cancel()
        val expected = generation
        val revision = ++listRevision
        refreshJob = scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, loadingMore = false, error = null)
            try {
                val page = apiFactory(current).listProposals()
                if (expected != generation || revision != listRevision) return@launch
                seenCursors.clear()
                val existing = mutableState.value.proposals.associateBy { it.id }
                mutableState.value = mutableState.value.copy(
                    proposals = page.proposals.distinctBy { it.id }.map { incoming ->
                        existing[incoming.id]?.takeIf { it.stateVersion > incoming.stateVersion } ?: incoming
                    }, nextCursor = page.nextCursor?.takeIf(String::isNotBlank),
                    loading = false, loadedPages = 1, listError = null,
                )
                reconcileCancellations(page.proposals)
                if (mutableState.value.selectedId != null) refreshDetail()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (expected == generation && revision == listRevision)
                    mutableState.value = mutableState.value.copy(loading = false, listError = e.userMessage())
            }
        }
    }

    fun loadMore() {
        if (refreshJob?.isActive == true || pageJob?.isActive == true || actionJob?.isActive == true) return
        val cursor = mutableState.value.nextCursor ?: return
        val current = profile?.takeIf { it.isConfigured } ?: return
        val expected = generation
        val revision = listRevision
        pageJob = scope.launch {
            mutableState.value = mutableState.value.copy(loadingMore = true, error = null)
            try {
                val page = apiFactory(current).listProposals(cursor)
                if (expected != generation || revision != listRevision) return@launch
                val next = page.nextCursor?.takeIf(String::isNotBlank)
                check(next == null || next != cursor && next !in seenCursors) { "分页状态已变化，请刷新列表" }
                seenCursors += cursor
                // Moving rows can appear on two pages; a later page cannot replace a newer row.
                val rows = mutableState.value.proposals.toMutableList()
                page.proposals.forEach { row ->
                    val index = rows.indexOfFirst { it.id == row.id }
                    if (index < 0) rows += row
                    else if (row.stateVersion > rows[index].stateVersion) rows[index] = row
                }
                mutableState.value = mutableState.value.copy(proposals = rows, nextCursor = next,
                    loadingMore = false, loadedPages = mutableState.value.loadedPages + 1, listError = null)
                reconcileCancellations(page.proposals)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (expected == generation && revision == listRevision)
                    mutableState.value = mutableState.value.copy(loadingMore = false, listError = e.userMessage())
            }
        }
    }

    /** Poll only the visible detail or first page, preserving a reader's older pages. */
    fun refreshVisible() {
        if (mutableState.value.selectedId != null) refreshDetail()
        else if (mutableState.value.loadedPages <= 1) refresh()
    }

    fun select(proposalId: String) {
        detailJob?.cancel()
        mutableState.value = mutableState.value.copy(selectedId = proposalId,
            selected = mutableState.value.proposals.find { it.id == proposalId }, detailFresh = false, detailError = null)
        refreshDetail()
    }

    fun refreshDetail() {
        if (actionJob?.isActive == true) return
        val id = mutableState.value.selectedId ?: return
        val current = profile?.takeIf { it.isConfigured } ?: return
        detailJob?.cancel()
        val expected = generation
        val revision = ++detailRevision
        detailJob = scope.launch {
            mutableState.value = mutableState.value.copy(loadingDetail = true, detailFresh = false, detailError = null)
            try {
                val proposal = apiFactory(current).getProposal(id)
                if (expected != generation || revision != detailRevision || mutableState.value.selectedId != id) return@launch
                check(proposal.id == id) { "服务器返回了不同的候选，请重新读取" }
                val knownVersion = maxOf(mutableState.value.selected?.stateVersion ?: 0,
                    mutableState.value.proposals.find { it.id == id }?.stateVersion ?: 0)
                check(proposal.stateVersion >= knownVersion) { "服务器状态尚未同步，请稍后刷新" }
                mutableState.value = mutableState.value.copy(selected = proposal, loadingDetail = false, detailFresh = true,
                    proposals = mutableState.value.proposals.map { if (it.id == id) proposal else it })
                reconcileCancellations(listOf(proposal))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (expected == generation && revision == detailRevision && mutableState.value.selectedId == id)
                    mutableState.value = mutableState.value.copy(loadingDetail = false, detailFresh = false, detailError = e.userMessage())
            }
        }
    }

    fun clearSelection() {
        detailJob?.cancel(); detailRevision++
        mutableState.value = mutableState.value.copy(selectedId = null, selected = null, loadingDetail = false,
            detailFresh = false, detailError = null)
    }

    fun cancelCandidate(id: String, note: String? = null) {
        val state = mutableState.value
        if (actionJob?.isActive == true || !state.detailFresh || id in state.unconfirmedCancellations) return
        val proposal = state.selected?.takeIf { it.id == id && it.canCancel } ?: return
        val current = profile?.takeIf { it.isConfigured } ?: return
        val expected = generation
        refreshJob?.cancel(); pageJob?.cancel(); detailJob?.cancel()
        listRevision++; detailRevision++
        actionJob = scope.launch {
            mutableState.value = mutableState.value.copy(actionProposalId = id, loading = false,
                loadingMore = false, loadingDetail = false, detailFresh = false, error = null, notice = null)
            try {
                val cancelled = apiFactory(current).cancel(proposal, note)
                check(cancelled.id == id) { "取消结果无法核对，请刷新状态" }
                check(cancelled.stateVersion >= proposal.stateVersion && cancelled.status == "rejected") {
                    "服务器尚未确认取消完成，请刷新核对状态"
                }
                if (expected == generation) {
                    val selected = mutableState.value.selectedId == id
                    mutableState.value = mutableState.value.copy(
                        proposals = mutableState.value.proposals.map { if (it.id == id) cancelled else it },
                        selected = if (selected) cancelled else mutableState.value.selected,
                        detailFresh = selected, actionProposalId = null,
                        notice = if (cancelled.status == "rejected") "本次候选已取消" else "请求已返回，请核对候选状态",
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (expected == generation) {
                    val unknown = e !is ProposalApiException && e !is WakuConnectionException &&
                        !(e is WakuRequestException && e.delivery != WakuDelivery.RESULT_UNKNOWN)
                    mutableState.value = mutableState.value.copy(actionProposalId = null, error = e.userMessage(),
                        unconfirmedCancellations = if (unknown) mutableState.value.unconfirmedCancellations + id else mutableState.value.unconfirmedCancellations)
                    // Only read after an uncertain mutation. Never replay the cancellation.
                    try {
                        val fresh = apiFactory(current).getProposal(id)
                        if (expected == generation && fresh.id == id) {
                            val selected = mutableState.value.selectedId == id
                            mutableState.value = mutableState.value.copy(
                                proposals = mutableState.value.proposals.map { if (it.id == id) fresh else it },
                                selected = if (selected) fresh else mutableState.value.selected, detailFresh = selected)
                            reconcileCancellations(listOf(fresh))
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep the failure and the last known detail. */ }
                }
            } finally {
                if (expected == generation) {
                    actionJob = null
                    if (mutableState.value.selectedId != null && mutableState.value.selectedId != id) refreshDetail()
                }
            }
        }
    }

    private fun reconcileCancellations(rows: List<SkillProposal>) {
        val resolved = rows.filter { it.approval != null || it.status in setOf("rejected", "failed", "stale", "published", "publishing") }.map { it.id }.toSet()
        mutableState.value = mutableState.value.copy(unconfirmedCancellations = mutableState.value.unconfirmedCancellations - resolved)
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(error = null, notice = null) }

    private fun Throwable.userMessage(): String = when (this) {
        is ProposalApiException -> when (code) {
            "conflict" -> "候选状态已变化，请查看最新详情"
            "permission_denied" -> "服务器未授予技能管理权限"
            "not_found" -> "候选已不存在，请刷新列表"
            else -> message ?: "技能管理操作未完成"
        }
        is WakuRequestException -> when (delivery) {
            WakuDelivery.NOT_SENT -> "请求未发出，请检查连接后重试"
            WakuDelivery.REJECTED -> message ?: "服务器拒绝了请求"
            WakuDelivery.RESULT_UNKNOWN -> "请求结果尚未确认，请刷新核对状态"
        }
        else -> message ?: "无法连接技能管理服务"
    }

    companion object {
        @Volatile private var instance: ProposalRepository? = null
        fun get(context: Context): ProposalRepository = instance ?: synchronized(this) {
            instance ?: ProposalRepository(SettingsStore(context.applicationContext).flow,
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { instance = it }
        }
    }
}
