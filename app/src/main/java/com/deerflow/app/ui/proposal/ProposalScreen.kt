package com.deerflow.app.ui.proposal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.deerflow.app.data.proposal.EvolutionGroup
import com.deerflow.app.data.proposal.ProposalState
import com.deerflow.app.data.proposal.SkillProposal
import com.deerflow.app.data.proposal.canCancel
import com.deerflow.app.data.proposal.currentLifecycle
import com.deerflow.app.data.proposal.evolutionGroup
import com.deerflow.app.data.proposal.userConfirmed
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalScreen(vm: ProposalViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshVisible()
                delay(30_000)
            }
        }
    }
    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearMessage()
    }
    BackHandler(enabled = state.selectedId != null) { vm.clearSelection() }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(if (state.selectedId == null) "技能演进" else state.selected?.skillName ?: "演进详情") },
            navigationIcon = { IconButton(onClick = { if (state.selectedId != null) vm.clearSelection() else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            } }, actions = {
                IconButton(onClick = { if (state.selectedId != null) vm.refreshDetail() else vm.refresh() },
                    enabled = !state.loading && !state.loadingDetail && state.actionProposalId == null) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新演进状态")
                }
            })
    }) { padding ->
        if (state.selectedId == null) {
            ProposalList(state, vm::select, vm::loadMore, vm::refresh, Modifier.padding(padding))
        } else if (state.selected == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.loadingDetail) CircularProgressIndicator()
                else Column(Modifier.padding(24.dp)) {
                    Text(state.detailError ?: "无法读取候选详情")
                    TextButton(onClick = vm::refreshDetail) { Text("重新读取") }
                }
            }
        } else {
            ProposalDetail(state.selected!!, state, vm::refreshDetail, vm::cancelCandidate, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ProposalList(state: ProposalState, onView: (String) -> Unit, onMore: () -> Unit,
    onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val groups = remember(state.proposals) { state.proposals.groupBy { it.evolutionGroup } }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("技能在后台自动验证与采用，无需审批。这里可以查看进度、验证结果和变更记录。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.loading) item { CircularProgressIndicator() }
        state.listError?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("重新加载") }
        } }
        if (state.proposals.isEmpty() && !state.loading && state.listError == null) item {
            Text(if (state.configured) "当前没有技能演进记录" else "请先在连接设置中配置服务器")
        }
        EvolutionGroup.entries.forEach { group ->
            val rows = groups[group].orEmpty()
            if (rows.isNotEmpty()) {
                item(key = "group:$group") { Text(group.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(rows, key = { it.id }) { proposal -> ProposalCard(proposal, onView = { onView(proposal.id) }) }
            }
        }
        if (state.proposals.isNotEmpty()) item {
            Text("已加载 ${state.proposals.size} 条记录" + if (state.loadedPages > 1) " · 查看历史时暂停自动刷新" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.nextCursor != null) item {
            OutlinedButton(onClick = onMore, enabled = !state.loadingMore && !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.loadingMore) "正在加载…" else "加载更多记录")
            }
        }
    }
}

@Composable
private fun ProposalDetail(proposal: SkillProposal, state: ProposalState, onRefresh: () -> Unit,
    onCancel: (String, String?) -> Unit, modifier: Modifier = Modifier) {
    var confirming by remember(proposal.id, proposal.stateVersion) { mutableStateOf(false) }
    var note by rememberSaveable(proposal.id) { mutableStateOf("") }
    val busy = state.actionProposalId == proposal.id
    val unknown = proposal.id in state.unconfirmedCancellations
    val canCancel = proposal.canCancel && state.detailFresh && !busy && !unknown
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ProposalCard(proposal, onView = {}, showViewAction = false) }
        if (state.loadingDetail) item { CircularProgressIndicator() }
        if (state.detailError != null || !state.detailFresh && !state.loadingDetail && !busy) item {
            Text(state.detailError ?: "当前显示上次读取的内容，请刷新状态", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefresh) { Text("重新读取详情") }
        }
        if (unknown) item {
            Text("取消请求的结果尚未确认。请刷新核对，避免重复操作。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefresh, enabled = !state.loadingDetail) { Text("核对取消结果") }
        }
        item { DetailSection("验证结果") {
            DetailRow("内容检查", checkLabel(proposal.evaluation.security))
            DetailRow("行为评测", checkLabel(proposal.evaluation.behavior))
            DetailRow("脚本隔离执行", if (proposal.evaluation.isolatedScripts) "已验证" else "未提供已验证结果")
            if (proposal.userConfirmed) Text("本次按会话指令发布，验证结果保留实际状态。", style = MaterialTheme.typography.bodySmall)
            proposal.evaluation.modelChecks.forEach { check ->
                DetailRow(if (check.stage == "moderation") "内容审查" else "质量审查",
                    (if (check.passed) "通过" else "未通过") + check.reason.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty())
            }
            proposal.evaluation.evidence.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            proposal.validationFailure?.let { failure ->
                val code = (failure["code"] as? JsonPrimitive)?.contentOrNull
                val stage = (failure["stage"] as? JsonPrimitive)?.contentOrNull
                if (code != null) DetailRow("失败原因", listOfNotNull(stage, code).joinToString(" · "))
            }
            proposal.error?.takeIf(String::isNotBlank)?.let { DetailRow("处理说明", it) }
            proposal.publicationError?.takeIf(String::isNotBlank)?.let { DetailRow("发布问题", it) }
        } }
        proposal.currentLifecycle?.observation?.let { observation -> item { DetailSection("采用后的观察") {
            DetailRow("有效成功 / 失败", "${observation.successes} / ${observation.failures}")
            DetailRow("未计入的结果", observation.ignored.toString())
            DetailRow("观察截止", formatProposalTime(observation.deadline))
            observation.rollbackRevision?.let { DetailRow("撤回记录版本", it.toString()) }
            if (observation.reason.isNotBlank()) Text(observation.reason, style = MaterialTheme.typography.bodySmall)
        } } }
        item { DetailSection("版本与来源") {
            DetailRow("来源", proposal.target?.sourceId?.takeIf(String::isNotBlank) ?: "未提供")
            DetailRow("基础版本", proposal.base?.let { if (it.exists) it.version else "新建技能" }
                ?: proposal.baseSha256 ?: "未提供")
            DetailRow("候选版本", proposal.candidateSha256 ?: "未提供")
            proposal.publishedRevision?.let { DetailRow("发布记录版本", it.toString()) }
            DetailRow("变更文件", proposal.changedFiles.joinToString().ifBlank { "未提供" })
            DetailRow("更新时间", formatProposalTime(proposal.updatedAt))
        } }
        item { DetailSection("变更内容") {
            val diff = proposal.diff.orEmpty()
            val rendered = diff.take(50_000) + if (diff.length > 50_000) "\n\n… 内容较长，已截断显示" else ""
            Surface(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small) {
                Text(rendered.ifBlank {
                    when {
                        state.loadingDetail -> "正在读取变更…"
                        proposal.contentExpired -> "历史变更内容已清理，版本和处理记录仍可查阅。"
                        !state.detailFresh -> "详情尚未读取成功，请刷新后查看变更。"
                        else -> "没有可显示的文本变更"
                    }
                },
                    Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        } }
        proposal.reviewNote?.takeIf(String::isNotBlank)?.let { noteText -> item { DetailSection("处理记录") {
            Text(noteText, style = MaterialTheme.typography.bodySmall)
            proposal.reviewedAt?.let { Text(formatProposalTime(it), style = MaterialTheme.typography.labelSmall) }
        } } }
        if (proposal.canCancel) item {
            OutlinedButton(onClick = { confirming = true }, enabled = canCancel, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "正在取消…" else "取消本次候选")
            }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("取消本次候选？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("取消后，系统将停止处理这个尚未采用的候选。后续新任务仍可产生新的技能改进。")
            OutlinedTextField(note, { note = it.take(2000) }, label = { Text("取消原因（可选）") }, maxLines = 4)
        } }, confirmButton = { Button(enabled = canCancel, onClick = { confirming = false; onCancel(proposal.id, note) }) { Text("取消候选") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("保留候选") } })
}

private fun checkLabel(value: String): String = when (value) {
    "passed" -> "通过"
    "failed" -> "未通过"
    "not_run" -> "尚未执行"
    "unavailable" -> "暂不可用"
    "" -> "未提供结果"
    else -> "未确认（$value）"
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        HorizontalDivider()
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
