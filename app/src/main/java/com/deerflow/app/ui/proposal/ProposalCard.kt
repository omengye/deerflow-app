package com.deerflow.app.ui.proposal

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deerflow.app.data.proposal.EvolutionGroup
import com.deerflow.app.data.proposal.SkillProposal
import com.deerflow.app.data.proposal.evolutionGroup
import com.deerflow.app.data.proposal.progressDescription
import com.deerflow.app.data.proposal.statusLabel
import com.deerflow.app.ui.theme.deerflowColors
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
    val semantic = MaterialTheme.deerflowColors
    val statusColor = when {
        proposal.evolutionGroup == EvolutionGroup.WITHDRAWN || proposal.status == "failed" -> scheme.error
        proposal.evolutionGroup == EvolutionGroup.COMPLETED -> semantic.success
        proposal.evolutionGroup == EvolutionGroup.UNKNOWN -> scheme.onSurfaceVariant
        else -> scheme.primary
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .then(if (showViewAction) Modifier.clickable(onClick = onView) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = proposal.skillName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${actionLabel(proposal.action)} · ${formatProposalTime(proposal.updatedAt.ifBlank { proposal.createdAt })}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = statusColor.copy(alpha = 0.14f),
                    contentColor = statusColor,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = proposal.statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            val description = proposal.reason.ifBlank { proposal.trigger.summary }
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    maxLines = if (compact) 2 else 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (proposal.progressDescription.isNotBlank()) {
                Text(
                    text = proposal.progressDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = if (compact) 2 else 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showViewAction) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "查看演进详情",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
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
