package com.deerflow.app.ui.proposal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deerflow.app.data.proposal.SkillProposal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalScreen(
    vm: ProposalViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearMessage()
    }

    val selected = state.selected
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selected == null) "Proposal 审批" else selected.skillName)
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selected != null) vm.clearSelection() else onBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        if (selected == null) {
            ProposalList(
                proposals = state.proposals,
                loading = state.loading,
                actionProposalId = state.actionProposalId,
                onView = vm::select,
                onApprove = vm::approve,
                onReject = vm::reject,
                modifier = Modifier.padding(padding),
            )
        } else {
            ProposalDetail(
                proposal = selected,
                loading = state.loadingDetail,
                busy = state.actionProposalId == selected.id,
                onApprove = vm::approve,
                onReject = vm::reject,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ProposalList(
    proposals: List<SkillProposal>,
    loading: Boolean,
    actionProposalId: String?,
    onView: (String) -> Unit,
    onApprove: (String, String?) -> Unit,
    onReject: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (loading && proposals.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (proposals.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "当前没有 Proposal",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        return
    }

    val pending = proposals.filter { it.status == "pending_review" }
    val completed = proposals.filterNot { it.status == "pending_review" }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (pending.isNotEmpty()) {
            item { SectionTitle("待审批 · ${pending.size}") }
            items(pending, key = { it.id }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    busy = actionProposalId == proposal.id,
                    onView = { onView(proposal.id) },
                    onApprove = { onApprove(proposal.id, null) },
                    onReject = { onReject(proposal.id, null) },
                )
            }
        }
        if (completed.isNotEmpty()) {
            item {
                if (pending.isNotEmpty()) Spacer(Modifier.height(6.dp))
                SectionTitle("最近处理")
            }
            items(completed, key = { it.id }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    busy = false,
                    onView = { onView(proposal.id) },
                    onApprove = {},
                    onReject = {},
                )
            }
        }
    }
}

@Composable
private fun ProposalDetail(
    proposal: SkillProposal,
    loading: Boolean,
    busy: Boolean,
    onApprove: (String, String?) -> Unit,
    onReject: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var note by rememberSaveable(proposal.id) { mutableStateOf(proposal.reviewNote.orEmpty()) }
    val diff = proposal.diff.orEmpty()
    val renderedDiff = if (diff.length <= MAX_RENDERED_DIFF_CHARS) {
        diff
    } else {
        diff.take(MAX_RENDERED_DIFF_CHARS) + "\n\n… Diff 内容过长，已截断显示"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ProposalCard(
                proposal = proposal,
                busy = busy,
                onView = {},
                onApprove = { onApprove(proposal.id, note) },
                onReject = { onReject(proposal.id, note) },
                showViewAction = false,
            )
        }
        if (loading) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        item {
            DetailSection("详细信息") {
                DetailRow("Proposal", proposal.id)
                DetailRow("状态", statusLabel(proposal.status))
                DetailRow("操作", actionLabel(proposal.action))
                DetailRow("风险", riskLabel(proposal.risk))
                DetailRow("基础 Revision", proposal.baseRevision?.toString() ?: "新建")
                DetailRow("发布 Revision", proposal.publishedRevision?.toString() ?: "--")
                DetailRow("触发 Thread", proposal.trigger.threadId ?: "--")
                DetailRow("变更文件", proposal.changedFiles.joinToString().ifBlank { "--" })
                proposal.error?.takeIf { it.isNotBlank() }?.let { DetailRow("错误", it) }
            }
        }
        item {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(2000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("审批意见（可选）") },
                enabled = proposal.status == "pending_review" && !busy,
                minLines = 2,
                maxLines = 5,
            )
        }
        item {
            DetailSection("变更 Diff") {
                Surface(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = renderedDiff.ifBlank { "没有可显示的文本 Diff" },
                        modifier = Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            DetailSection("安全扫描") {
                Text(
                    text = if (proposal.scans.isEmpty()) "没有扫描结果" else proposal.scans.joinToString("\n\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        proposal.reviewNote?.takeIf { it.isNotBlank() }?.let { reviewNote ->
            item {
                DetailSection("审批记录") {
                    Text(reviewNote)
                    proposal.reviewedAt?.let {
                        Text(
                            formatProposalTime(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(text = value, modifier = Modifier.weight(0.65f), style = MaterialTheme.typography.bodyMedium)
    }
}

private const val MAX_RENDERED_DIFF_CHARS = 50_000
