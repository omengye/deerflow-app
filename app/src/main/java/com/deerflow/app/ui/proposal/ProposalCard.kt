package com.deerflow.app.ui.proposal

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deerflow.app.data.proposal.EvolutionGroup
import com.deerflow.app.data.proposal.SkillProposal
import com.deerflow.app.data.proposal.evolutionGroup
import com.deerflow.app.data.proposal.progressDescription
import com.deerflow.app.data.proposal.statusLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ProposalCard(
    proposal: SkillProposal,
    onView: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showViewAction: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val statusColor = when {
        proposal.evolutionGroup == EvolutionGroup.WITHDRAWN || proposal.status == "failed" -> scheme.error
        proposal.evolutionGroup == EvolutionGroup.UNKNOWN -> scheme.onSurfaceVariant
        else -> scheme.primary
    }
    Card(modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(proposal.skillName, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${actionLabel(proposal.action)} · ${formatProposalTime(proposal.updatedAt.ifBlank { proposal.createdAt })}",
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Surface(color = statusColor.copy(alpha = 0.12f), contentColor = statusColor, shape = MaterialTheme.shapes.small) {
                Text(proposal.statusLabel, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
            val description = proposal.reason.ifBlank { proposal.trigger.summary }
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (compact) 2 else 5, overflow = TextOverflow.Ellipsis)
            if (proposal.progressDescription.isNotBlank()) Text(proposal.progressDescription,
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                maxLines = if (compact) 2 else 5, overflow = TextOverflow.Ellipsis)
            if (showViewAction) TextButton(onClick = onView) { Text("查看演进详情") }
        }
    }
}

internal fun actionLabel(action: String): String = when (action) {
    "create" -> "创建技能"
    "edit", "patch" -> "更新技能"
    "delete" -> "删除技能"
    "write_file" -> "更新技能文件"
    "remove_file" -> "移除技能文件"
    else -> "技能变更"
}

internal fun formatProposalTime(value: String): String = if (value.isBlank()) "—" else runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
