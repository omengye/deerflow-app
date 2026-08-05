package com.deerflow.app.ui.proposal

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deerflow.app.data.proposal.SkillProposal

@Composable
fun ProposalCard(
    proposal: SkillProposal,
    busy: Boolean,
    onView: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showViewAction: Boolean = true,
) {
    var confirmation by remember(proposal.id) { mutableStateOf<ReviewDecision?>(null) }
    val scheme = MaterialTheme.colorScheme
    val pending = proposal.status == "pending_review"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, riskColor(proposal.risk).copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = proposal.skillName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${actionLabel(proposal.action)} · ${formatProposalTime(proposal.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProposalBadge(riskLabel(proposal.risk), riskColor(proposal.risk))
                    ProposalBadge(statusLabel(proposal.status), statusColor(proposal.status))
                }
            }

            val description = proposal.reason.ifBlank { proposal.trigger.summary }
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (compact) 3 else 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val isRejectedOrFailed = proposal.status in listOf("rejected", "failed", "stale")
            val noteText = proposal.reviewNote?.ifBlank { null } ?: proposal.error?.ifBlank { null }
            if (!pending && isRejectedOrFailed) {
                val isAutoReject = noteText?.contains("auto", ignoreCase = true) == true
                    || noteText?.contains("timeout", ignoreCase = true) == true
                    || noteText?.contains("system", ignoreCase = true) == true
                    || noteText?.contains("自动", ignoreCase = true) == true
                    || (proposal.reviewedAt == null && proposal.reviewNote == null)
                val title = if (isAutoReject) "🤖 系统自动拒绝" else "已拒绝"

                Surface(
                    color = scheme.errorContainer.copy(alpha = 0.4f),
                    contentColor = scheme.onErrorContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!noteText.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = noteText,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onErrorContainer.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            } else if (!pending && proposal.status == "published") {
                Surface(
                    color = scheme.primaryContainer.copy(alpha = 0.35f),
                    contentColor = scheme.onPrimaryContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                ) {
                    Text(
                        text = "✅ 已批准并发布生效",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (showViewAction) {
                    TextButton(onClick = onView, enabled = !busy) {
                        Text("查看变更")
                    }
                }
                if (pending) {
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(
                        onClick = { confirmation = ReviewDecision.REJECT },
                        enabled = !busy,
                    ) {
                        Text("拒绝")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { confirmation = ReviewDecision.APPROVE },
                        enabled = !busy,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("批准并发布")
                    }
                }
            }
        }
    }

    confirmation?.let { decision ->
        val approving = decision == ReviewDecision.APPROVE
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (approving) "确认批准并发布？" else "确认拒绝？") },
            text = {
                Text(
                    when {
                        approving && proposal.risk == "high" ->
                            "这是高风险 Proposal。批准后会立即发布并修改当前 Skill。"
                        approving -> "批准后会立即发布并修改当前 Skill。"
                        else -> "拒绝后不会修改当前 Skill。"
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmation = null
                        if (approving) onApprove() else onReject()
                    },
                ) {
                    Text(if (approving) "批准并发布" else "拒绝")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProposalBadge(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

private enum class ReviewDecision { APPROVE, REJECT }

@Composable
private fun riskColor(risk: String): Color = when (risk) {
    "high" -> MaterialTheme.colorScheme.error
    "medium" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "published" -> MaterialTheme.colorScheme.primary
    "rejected", "failed", "stale" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}

internal fun riskLabel(risk: String): String = when (risk) {
    "high" -> "高风险"
    "medium" -> "中风险"
    "low" -> "低风险"
    else -> risk
}

internal fun statusLabel(status: String): String = when (status) {
    "generating" -> "生成中"
    "validating" -> "校验中"
    "pending_review" -> "待审批"
    "publishing" -> "发布中"
    "published" -> "已发布"
    "rejected" -> "已拒绝"
    "failed" -> "失败"
    "stale" -> "已过期"
    else -> status
}

internal fun actionLabel(action: String): String = when (action) {
    "create" -> "创建 Skill"
    "edit" -> "编辑 Skill"
    "patch" -> "修改 Skill"
    "delete" -> "删除 Skill"
    "write_file" -> "写入文件"
    "remove_file" -> "删除文件"
    else -> action
}

internal fun formatProposalTime(value: String): String =
    value.replace('T', ' ').removeSuffix("Z").take(19).ifBlank { "--" }
