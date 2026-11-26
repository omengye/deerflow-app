package com.deerflow.app.ui.chat

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.deerflow.app.data.waku.WakuImageReference
import com.deerflow.app.domain.BlockKind
import com.deerflow.app.domain.DisplayBlock
import com.deerflow.app.domain.ToolGroup
import com.deerflow.app.domain.ToolGroupStatus
import com.deerflow.app.domain.TranscriptGrouping
import com.deerflow.app.domain.UserDisplayText
import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.MessageFile
import com.deerflow.app.ui.theme.DeerflowSemanticColors
import com.deerflow.app.ui.theme.deerflowColors
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Renders one transcript block in a clean, full-width AI agent document stream. */
@Composable
fun BlockCard(
    block: DisplayBlock,
    modifier: Modifier = Modifier,
    artifactHeaders: Map<String, String> = emptyMap(),
    isStreaming: Boolean = false,
    resolveMessageFile: (suspend (MessageFile) -> AgentArtifact)? = null,
    resolveGeneratedImage: (suspend (String) -> AgentArtifact)? = null,
    imageReloadKey: Any? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.deerflowColors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        when (block.kind) {
            BlockKind.USER -> {
                val cleaned = UserDisplayText.clean(block.content)
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val bubbleMaxWidth = maxWidth * 0.84f
                    Surface(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .widthIn(max = bubbleMaxWidth),
                        shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                        color = semantic.userBubble,
                        contentColor = semantic.onUserBubble,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (cleaned.isNotBlank()) {
                                MarkdownText(
                                    text = cleaned,
                                    textColor = semantic.onUserBubble,
                                    artifactHeaders = artifactHeaders,
                                )
                            }
                            block.attachments.forEach { file ->
                                ArtifactCard(
                                    artifact = file.asArtifact(),
                                    autoPreview = file.isImage,
                                    resolve = resolveMessageFile?.let { resolver -> { resolver(file) } },
                                )
                            }
                        }
                    }
                }
            }

            BlockKind.ASSISTANT -> {
                val agentName = remember(block.header) {
                    val idx = block.header.indexOf("agent:")
                    if (idx >= 0) block.header.substring(idx + 6).trim().takeIf { it.isNotEmpty() } else null
                }
                val displayText = remember(block.content) { mapAssistantDisplayContent(block.content) }
                val clipboard = LocalClipboardManager.current
                var copied by remember(block.key) { mutableStateOf(false) }

                LaunchedEffect(copied) {
                    if (copied) {
                        delay(1_500)
                        copied = false
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    if (agentName != null) {
                        Surface(
                            color = scheme.primaryContainer.copy(alpha = 0.7f),
                            contentColor = scheme.onPrimaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            Text(
                                text = agentName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (displayText.isNotBlank()) {
                        MarkdownText(
                            text = displayText,
                            textColor = scheme.onBackground,
                            artifactHeaders = artifactHeaders,
                            resolveGeneratedImage = resolveGeneratedImage,
                            imageReloadKey = imageReloadKey,
                        )
                    }
                    if (isStreaming && displayText.isNotEmpty()) {
                        TypingCursor(
                            color = scheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    } else if (!isStreaming && displayText.isNotBlank()) {
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        clipboard.setText(AnnotatedString(displayText))
                                        copied = true
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "复制回复",
                                    tint = if (copied) semantic.success else scheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = if (copied) "已复制" else "复制",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (copied) semantic.success else scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            BlockKind.THINKING, BlockKind.REASONING -> {
                var expanded by remember { mutableStateOf(false) }
                val isThinking = block.kind == BlockKind.THINKING
                val explicitHeading = remember(block.content) {
                    TranscriptGrouping.extractExplicitReasoningHeading(block.content)
                }
                val title = when {
                    isStreaming -> "正在思考…"
                    explicitHeading != null -> explicitHeading
                    isThinking -> "思考过程"
                    else -> "推理过程"
                }
                val previewLine = remember(block.content, isStreaming, title) {
                    extractThinkingPreview(block.content, isStreaming).takeIf { it != title }
                }
                val borderColor by animateColorAsState(
                    targetValue = if (isStreaming) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant,
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                    label = "thinkingBorder",
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .animateContentSize(),
                    shape = RoundedCornerShape(14.dp),
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, borderColor),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (block.content.isNotBlank()) Modifier.clickable { expanded = !expanded } else Modifier)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StreamingIcon(
                                isStreaming = isStreaming,
                                staticIcon = Icons.Default.Psychology,
                                tint = if (isStreaming) scheme.primary else scheme.tertiary,
                                contentDescription = title,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurface,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!expanded && previewLine != null) {
                                    Text(
                                        text = previewLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (block.content.isNotBlank()) {
                                Icon(
                                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        if (expanded && block.content.isNotBlank()) {
                            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(scheme.tertiary.copy(alpha = 0.45f)),
                                )
                                Spacer(Modifier.width(10.dp))
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = block.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            BlockKind.TOOL -> {
                val tool = block.tool
                val failed = tool?.isError == true
                val running = tool?.isRunning == true && !failed
                val name = tool?.name?.trim()?.takeIf { it.isNotEmpty() } ?: "工具调用"
                val accent = when {
                    failed -> scheme.error
                    running -> scheme.primary
                    else -> semantic.success
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (failed) scheme.errorContainer.copy(alpha = 0.25f) else scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, if (failed) scheme.error.copy(alpha = 0.4f) else scheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (running) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = accent, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = if (failed) Icons.Default.Warning else Icons.Default.Build,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (failed) accent else scheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (tool != null) {
                            Text(
                                text = when {
                                    failed -> "失败"
                                    running -> "调用中"
                                    else -> "已完成"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (failed || running) accent else scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            BlockKind.ARTIFACT -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    block.artifacts.forEach { artifact ->
                        ArtifactCard(artifact = artifact, headers = artifactHeaders)
                    }
                }
            }

            BlockKind.INTERRUPT, BlockKind.ERROR -> {
                val isError = block.kind == BlockKind.ERROR
                val containerCol = if (isError) scheme.errorContainer else semantic.warningContainer
                val borderCol = if (isError) scheme.error else semantic.warning
                val textCol = if (isError) scheme.onErrorContainer else semantic.onWarningContainer

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = containerCol),
                    border = BorderStroke(1.dp, borderCol.copy(alpha = 0.6f)),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isError) Icons.Default.Warning else Icons.Default.Info,
                                contentDescription = null,
                                tint = borderCol,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = block.header,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = textCol,
                            )
                        }
                        if (block.content.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            SelectionContainer {
                                Text(
                                    text = block.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textCol.copy(alpha = 0.92f),
                                )
                            }
                        }
                    }
                }
            }

            BlockKind.SYSTEM -> {
                SelectionContainer {
                    Text(
                        text = "${block.header}: ${block.content}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

private fun mapAssistantDisplayContent(raw: String): String = when (raw) {
    "The turn completed without a text response." -> "本轮处理已完成（无额外文字回复）。"
    "The turn stopped before a response." -> "处理在生成回复前已停止。"
    else -> raw
}

private fun extractThinkingPreview(raw: String, isStreaming: Boolean): String? {
    val lines = raw.lineSequence()
        .map { it.trim().trimStart('#', '*', '-', ' ').trimEnd('*').trim() }
        .filter { it.isNotEmpty() }
        .toList()
    return if (isStreaming) lines.lastOrNull() else lines.firstOrNull()
}

@Composable
fun ToolGroupCard(
    group: ToolGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.deerflowColors
    val isRunning = group.status == ToolGroupStatus.RUNNING
    val isFailed = group.status == ToolGroupStatus.FAILED
    val dotAlpha = if (isRunning) {
        val transition = rememberInfiniteTransition(label = "toolGroupDotPulse")
        val animated by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "toolGroupDotAlpha",
        )
        animated
    } else {
        1f
    }
    val dotColor = when (group.status) {
        ToolGroupStatus.RUNNING -> scheme.primary.copy(alpha = dotAlpha)
        ToolGroupStatus.FAILED -> scheme.error
        ToolGroupStatus.COMPLETED -> semantic.success
    }
    val borderColor = if (isFailed) scheme.error.copy(alpha = 0.45f) else scheme.outlineVariant
    val containerColor = if (isFailed) scheme.errorContainer.copy(alpha = 0.22f) else scheme.surfaceContainerLow

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = if (group.expandable) Modifier.clickable(onClick = onToggle) else Modifier,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = group.taskTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (group.expandable) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) "收起工具列表" else "展开工具列表",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (!expanded) {
                val featured = group.featuredTool
                val statusText = when {
                    featured.isRunning -> "调用中"
                    featured.isError -> "失败"
                    else -> "已完成"
                }
                val statusColor = when {
                    featured.isRunning -> scheme.primary
                    featured.isError -> scheme.error
                    else -> scheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = featured.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        featured.agentName?.let { agent ->
                            Text(
                                text = agent,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                        if (group.expandable) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(scheme.surfaceContainerHigh)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "共 ${group.tools.size} 项",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (expanded && group.expandable) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                val listModifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (group.tools.size > 8) {
                            Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                Column(
                    modifier = listModifier,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.tools.forEach { step ->
                        val stepStatusText = when {
                            step.isRunning -> "调用中"
                            step.isError -> "失败"
                            else -> "已完成"
                        }
                        val stepStatusColor = when {
                            step.isRunning -> scheme.primary
                            step.isError -> scheme.error
                            else -> scheme.onSurfaceVariant
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when {
                                step.isRunning -> CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = scheme.primary,
                                    strokeWidth = 1.8.dp,
                                )
                                step.isError -> Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = scheme.error,
                                )
                                else -> Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = semantic.success,
                                )
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = step.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                step.agentName?.let { agent ->
                                    Text(
                                        text = agent,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(
                                text = stepStatusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = stepStatusColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArtifactCard(
    artifact: AgentArtifact,
    headers: Map<String, String> = emptyMap(),
    autoPreview: Boolean = false,
    resolve: (suspend () -> AgentArtifact)? = null,
    reloadKey: Any? = null,
    previewContentScale: ContentScale = ContentScale.Crop,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentResolve by rememberUpdatedState(resolve)
    var resolved by remember(artifact.path, artifact.name, artifact.mimeType, artifact.size) { mutableStateOf<AgentArtifact?>(null) }
    var previewOpen by remember(artifact.path) { mutableStateOf(false) }
    var busy by remember(artifact.path) { mutableStateOf<String?>(null) }
    var failure by remember(artifact.path) { mutableStateOf<String?>(null) }
    val isImage = artifact.kind == "image" || artifact.mimeType?.startsWith("image/") == true

    suspend fun content(): AgentArtifact {
        resolved?.takeIf { cached ->
            cached.url.isNotBlank() && (Uri.parse(cached.url).scheme != "file" || runCatching { checkedCacheFile(context, Uri.parse(cached.url)) }.isSuccess)
        }?.let { return it }
        return (currentResolve?.invoke() ?: artifact).also { resolved = it }
    }

    fun action(kind: String) {
        if (busy != null) return
        scope.launch {
            busy = kind
            failure = null
            try {
                val file = content()
                when (kind) {
                    "预览中…" -> previewOpen = true
                    "保存中…" -> {
                        saveToDownloads(context, file.name, file.mimeType, file.url, headers) { }
                        Toast.makeText(context, "已保存到 Download/deerflow/" + file.name, Toast.LENGTH_LONG).show()
                    }
                    "分享中…" -> {
                        val uri = downloadArtifact(context, file, headers)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = file.mimeType ?: "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "分享产物：" + file.name))
                    }
                    else -> {
                        val uri = downloadArtifact(context, file, headers)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, file.mimeType ?: "application/octet-stream")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                        )
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: ActivityNotFoundException) {
                failure = "没有可打开此文件的应用，请先保存文件"
            } catch (error: Exception) {
                failure = error.message ?: "文件读取失败，请重试"
            } finally {
                busy = null
            }
        }
    }

    LaunchedEffect(artifact.path, artifact.url, autoPreview, reloadKey) {
        if (autoPreview && isImage) {
            busy = "图片加载中…"
            failure = null
            try {
                content()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = "图片暂未加载，点击重试。" + error.message.orEmpty()
            } finally {
                busy = null
            }
        }
    }

    val shown = resolved ?: artifact
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = busy == null) {
                action(if (isImage) "预览中…" else "打开中…")
            },
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(10.dp)) {
            if (isImage && resolved != null) {
                NetworkImage(
                    model = authenticatedImageRequest(context, shown.url, headers),
                    contentDescription = shown.name,
                    contentScale = previewContentScale,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.surfaceContainer),
                )
                Spacer(Modifier.height(8.dp))
            } else if (isImage && autoPreview) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("正在加载图片…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    } else {
                        ImageLoadState("图片加载失败，点击重试")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.primaryContainer.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isImage) Icons.Default.Image else Icons.Default.AttachFile,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = artifact.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = busy ?: listOfNotNull(shown.mimeType, shown.size?.let { formatFileSize(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { action("分享中…") },
                    enabled = busy == null,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Share, contentDescription = "分享文件", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = { action("保存中…") },
                    enabled = busy == null,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = "保存文件", modifier = Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = { action(if (isImage) "预览中…" else "打开中…") },
                    enabled = busy == null,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = if (isImage) "预览图片" else "打开文件",
                        modifier = Modifier.size(18.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
            if (busy != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            failure?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (previewOpen && isImage && resolved != null) {
        AlertDialog(
            onDismissRequest = { previewOpen = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = scheme.surfaceContainerLow,
            confirmButton = { TextButton(onClick = { previewOpen = false }) { Text("关闭") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { action("保存中…") }, enabled = busy == null) {
                        Text(if (busy == "保存中…") "保存中…" else "保存")
                    }
                    TextButton(onClick = { action("打开中…") }, enabled = busy == null) { Text("外部打开") }
                }
            },
            title = { Text(shown.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    NetworkImage(
                        model = authenticatedImageRequest(context, shown.url, headers),
                        contentDescription = shown.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    failure?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
        )
    }
}

private fun formatFileSize(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MiB".format(size.toDouble() / (1024 * 1024))
    size >= 1024 -> "%.1f KiB".format(size.toDouble() / 1024)
    else -> "$size B"
}

private suspend fun downloadArtifact(
    context: Context,
    artifact: AgentArtifact,
    headers: Map<String, String>,
): Uri = downloadUrl(context, artifact.url, artifact.name, headers)

private data class ArtifactSource(
    val input: java.io.InputStream,
    val length: Long? = null,
    val disconnect: () -> Unit = {},
) : java.io.Closeable {
    override fun close() {
        try {
            input.close()
        } finally {
            disconnect()
        }
    }
}

private fun checkedCacheFile(context: Context, uri: Uri): File {
    val file = File(requireNotNull(uri.path)).canonicalFile
    val root = context.cacheDir.canonicalFile
    require(file.path.startsWith(root.path + File.separator) && file.isFile) { "本地附件不可用，请重新同步" }
    return file
}

/** No gateway credentials are ever forwarded to artifact or Markdown URLs. */
private fun openArtifactSource(context: Context, url: String): ArtifactSource {
    val uri = Uri.parse(url)
    return when (uri.scheme?.lowercase()) {
        "file" -> checkedCacheFile(context, uri).let { ArtifactSource(it.inputStream(), it.length()) }
        "content" -> {
            require(uri.authority == "${context.packageName}.fileprovider") { "不支持此外部文件来源" }
            ArtifactSource(context.contentResolver.openInputStream(uri) ?: error("本地附件不可用"))
        }
        "http", "https" -> {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            try {
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                ArtifactSource(connection.inputStream, connection.contentLengthLong.takeIf { it > 0 }, connection::disconnect)
            } catch (error: Throwable) {
                connection.disconnect()
                throw error
            }
        }
        else -> error("附件尚未下载，请重新同步")
    }
}

private suspend fun downloadUrl(
    context: Context,
    url: String,
    name: String,
    headers: Map<String, String>,
): Uri = withContext(Dispatchers.IO) {
    val uri = Uri.parse(url)
    if (uri.scheme == "file") {
        return@withContext FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", checkedCacheFile(context, uri))
    }
    if (uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider") return@withContext uri
    val dir = File(context.cacheDir, "artifacts").apply { mkdirs() }
    val file = File(dir, "${java.util.UUID.randomUUID()}-${sanitizeArtifactFilename(name)}")
    try {
        openArtifactSource(context, url).use { source ->
            file.outputStream().use { output -> source.input.copyTo(output) }
        }
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private suspend fun saveToDownloads(
    context: Context,
    displayName: String,
    mime: String?,
    url: String,
    authHeaders: Map<String, String>,
    onProgress: (Float) -> Unit,
): Uri = withContext(Dispatchers.IO) {
    val safeName = sanitizeArtifactFilename(displayName.ifBlank { "artifact" })
    openArtifactSource(context, url).use { source ->
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mime?.ifBlank { null } ?: guessMime(safeName))
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/deerflow/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载文件")
        var written = false
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                var lastEmit = 0L
                while (true) {
                    val count = source.input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    read += count
                    val total = source.length
                    if (total != null && total > 0) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit > 100 || read >= total) {
                            onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            lastEmit = now
                        }
                    }
                }
                written = true
            }
        } finally {
            if (written) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        }
        check(written) { "保存文件失败" }
        uri
    }
}

private fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").trim().lowercase()
    return if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    else "application/octet-stream"
}

@Composable
private fun NetworkImage(
    model: ImageRequest,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        loading = {
            Box(
                modifier = Modifier.fillMaxSize().background(scheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = scheme.primary,
                )
            }
        },
        error = {
            ImageLoadState(
                message = "图片加载失败",
                modifier = Modifier.fillMaxSize().background(scheme.surfaceContainer),
            )
        },
        success = { SubcomposeAsyncImageContent() },
    )
}

@Composable
private fun ImageLoadState(message: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}

private fun authenticatedImageRequest(context: Context, url: String, headers: Map<String, String>): ImageRequest {
    val uri = Uri.parse(url)
    val safeData: Any? = when (uri.scheme?.lowercase()) {
        "file" -> runCatching { checkedCacheFile(context, uri) }.getOrNull()
        "content" -> uri.takeIf { it.authority == "${context.packageName}.fileprovider" }
        "http", "https" -> url
        else -> null
    }
    return ImageRequest.Builder(context).data(safeData).build()
}

private fun sanitizeArtifactFilename(name: String): String {
    val clean = name.substringAfterLast('/').substringAfterLast('\\').trim()
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim(' ', '.').take(60)
    return clean.ifEmpty { "artifact" }
}

// ---------------------------------------------------------------------------
// Custom Lightweight Markdown Composable & Parser
// ---------------------------------------------------------------------------

internal sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class ListItem(val ordered: Boolean, val index: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class ImageBlock(val alt: String, val url: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data class BlockQuote(val text: String) : MarkdownBlock()
    data object Divider : MarkdownBlock()
}

@Composable
private fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    artifactHeaders: Map<String, String> = emptyMap(),
    resolveGeneratedImage: (suspend (String) -> AgentArtifact)? = null,
    imageReloadKey: Any? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.deerflowColors
    val blocks = remember(text) { parseBlocks(text) }
    val clipboard = LocalClipboardManager.current

    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { index, block ->
                when (block) {
                    is MarkdownBlock.CodeBlock -> {
                        var copied by remember(index, block.code) { mutableStateOf(false) }
                        LaunchedEffect(copied) {
                            if (copied) {
                                delay(1_500)
                                copied = false
                            }
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = semantic.codeSurface,
                            border = BorderStroke(1.dp, scheme.outlineVariant),
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(semantic.codeHeaderSurface)
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = block.language.ifBlank { "CODE" }.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                clipboard.setText(AnnotatedString(block.code))
                                                copied = true
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                            contentDescription = "复制代码",
                                            tint = if (copied) semantic.success else scheme.onSurfaceVariant,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text(
                                            text = if (copied) "已复制" else "复制",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (copied) semantic.success else scheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        text = block.code,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.Header -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }
                        Text(
                            text = parseMarkdownInline(block.text, scheme, semantic),
                            color = textColor,
                            style = style,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }

                    is MarkdownBlock.ListItem -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = if (block.ordered) "${block.index}. " else "• ",
                                color = textColor.copy(alpha = 0.65f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = parseMarkdownInline(block.text, scheme, semantic),
                                color = textColor,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    is MarkdownBlock.BlockQuote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(vertical = 2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(scheme.primary.copy(alpha = 0.55f)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = parseMarkdownInline(block.text, scheme, semantic),
                                color = textColor.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    MarkdownBlock.Divider -> {
                        HorizontalDivider(
                            color = scheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        Text(
                            text = parseMarkdownInline(block.text, scheme, semantic),
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    is MarkdownBlock.ImageBlock -> {
                        key(index, block.url) {
                            MarkdownImage(block, artifactHeaders, resolveGeneratedImage, imageReloadKey)
                        }
                    }

                    is MarkdownBlock.Table -> {
                        MarkdownTable(
                            headers = block.headers,
                            rows = block.rows,
                            textColor = textColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownImage(
    block: MarkdownBlock.ImageBlock,
    headers: Map<String, String>,
    resolveGeneratedImage: (suspend (String) -> AgentArtifact)? = null,
    imageReloadKey: Any? = null,
) {
    if (block.url.startsWith("waku-blob:")) {
        val reference = remember(block.url) { WakuImageReference.parse(block.url) }
        if (reference == null) {
            Text("图片引用无效，请重新同步会话", color = MaterialTheme.colorScheme.error)
        } else {
            ArtifactCard(
                artifact = reference.asArtifact(),
                autoPreview = true,
                reloadKey = imageReloadKey,
                previewContentScale = ContentScale.Fit,
                resolve = {
                    val resolver = resolveGeneratedImage ?: error("当前消息的图片暂不可用")
                    resolver(reference.value)
                },
            )
        }
        return
    }
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewOpen by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress: Float? by remember { mutableStateOf<Float?>(null) }
    val renderableUrl = remember(block.url) {
        block.url.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
    if (renderableUrl == null) {
        Text(
            text = block.alt.ifBlank { block.url },
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.primary,
        )
        return
    }

    fun openExternal() {
        scope.launch {
            opening = true
            val result = runCatching {
                downloadUrl(context, renderableUrl, block.alt.ifBlank { "image" }, headers)
            }
            opening = false
            result.onSuccess { uri ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "没有可打开此图片的应用", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                Toast.makeText(context, "打开失败：${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun save() {
        if (downloading) return
        scope.launch {
            downloading = true
            downloadProgress = null
            val result = runCatching {
                saveToDownloads(
                    context = context,
                    displayName = block.alt.ifBlank { "image" },
                    mime = "image/*",
                    url = renderableUrl,
                    authHeaders = headers,
                    onProgress = { v -> downloadProgress = v },
                )
            }
            downloading = false
            downloadProgress = null
            result.onSuccess {
                Toast.makeText(context, "已保存到 Download/deerflow/", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(context, "保存失败：${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    NetworkImage(
        model = authenticatedImageRequest(context, renderableUrl, headers),
        contentDescription = block.alt,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainer)
            .clickable { previewOpen = true },
    )

    if (previewOpen) {
        AlertDialog(
            onDismissRequest = { previewOpen = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = scheme.surfaceContainerLow,
            confirmButton = { TextButton(onClick = { previewOpen = false }) { Text("关闭") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { save() }, enabled = !downloading) {
                        Text(
                            when {
                                downloading -> downloadProgress?.let { "${(it * 100).roundToInt()}%" } ?: "保存中…"
                                else -> "保存"
                            },
                        )
                    }
                    TextButton(onClick = { openExternal() }, enabled = !opening && !downloading) {
                        Text(if (opening) "打开中…" else "外部打开")
                    }
                }
            },
            title = { Text(block.alt.ifBlank { "图片预览" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    NetworkImage(
                        model = authenticatedImageRequest(context, renderableUrl, headers),
                        contentDescription = block.alt,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.surfaceContainer),
                    )
                    if (downloading) {
                        Spacer(Modifier.height(8.dp))
                        if (downloadProgress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { downloadProgress ?: 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
        )
    }
}

/** Check if a line looks like a markdown table row: starts and ends with | */
private fun isTableRow(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.endsWith("|") && t.length > 1
}

/** Check if a line is a table separator row like |---|---| or | :---: | --- | */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith("|") || !t.endsWith("|")) return false
    val inner = t.removePrefix("|").removeSuffix("|")
    return inner.split("|").all { cell ->
        cell.trim().matches(Regex("^:?-{1,}:?$"))
    }
}

/** Parse cells from a table row like | cell1 | cell2 | */
private fun parseTableCells(line: String): List<String> {
    val t = line.trim().removePrefix("|").removeSuffix("|")
    return t.split("|").map { it.trim() }
}

private val markdownImageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")
private val markdownDividerRegex = Regex("^([-*_])\\1{2,}$")

internal fun parseBlocks(text: String): List<MarkdownBlock> {
    val lines = text.split("\n")
    val blocks = mutableListOf<MarkdownBlock>()
    var inCodeBlock = false
    var codeLanguage = ""
    val codeContent = StringBuilder()

    var currentParagraph = StringBuilder()

    // Table accumulation
    var inTable = false
    var tableHeaders = listOf<String>()
    val tableRows = mutableListOf<List<String>>()

    fun flushParagraph() {
        if (currentParagraph.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(currentParagraph.toString().trimEnd()))
            currentParagraph.setLength(0)
        }
    }

    fun flushTable() {
        if (inTable && tableHeaders.isNotEmpty()) {
            blocks.add(MarkdownBlock.Table(tableHeaders, tableRows.toList()))
        }
        inTable = false
        tableHeaders = emptyList()
        tableRows.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            flushParagraph()
            flushTable()
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(codeLanguage, codeContent.toString().trimEnd()))
                codeContent.setLength(0)
                inCodeBlock = false
            } else {
                codeLanguage = trimmed.substring(3).trim()
                inCodeBlock = true
            }
            i++
            continue
        }

        if (inCodeBlock) {
            codeContent.append(line).append("\n")
            i++
            continue
        }

        // Table detection: check if this line and the next form a table header + separator
        if (!inTable && isTableRow(trimmed) && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim())) {
            flushParagraph()
            inTable = true
            tableHeaders = parseTableCells(trimmed)
            i += 2 // skip header row + separator row
            continue
        }

        if (inTable) {
            if (isTableRow(trimmed) && !isTableSeparator(trimmed)) {
                tableRows.add(parseTableCells(trimmed))
                i++
                continue
            } else {
                flushTable()
                continue
            }
        }

        when {
            trimmed.isEmpty() -> {
                flushParagraph()
            }
            markdownDividerRegex.matches(trimmed) -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Divider)
            }
            markdownImageRegex.matchEntire(trimmed) != null -> {
                flushParagraph()
                val match = markdownImageRegex.matchEntire(trimmed)!!
                blocks.add(MarkdownBlock.ImageBlock(match.groupValues[1].trim(), match.groupValues[2].trim().removeSurrounding("<", ">")))
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.BlockQuote(trimmed.removePrefix(">").trim()))
            }
            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length
                val headerText = trimmed.substring(level).trim()
                if (level in 1..6) {
                    blocks.add(MarkdownBlock.Header(level, headerText))
                } else {
                    currentParagraph.append(line).append("\n")
                }
            }
            trimmed.startsWith("* ") || trimmed.startsWith("- ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.ListItem(false, 0, trimmed.substring(2).trim()))
            }
            trimmed.firstOrNull()?.isDigit() == true && trimmed.contains(". ") && trimmed.substringBefore(". ").all { it.isDigit() } -> {
                flushParagraph()
                val numStr = trimmed.substringBefore(". ")
                val num = numStr.toIntOrNull() ?: 1
                val listText = trimmed.substringAfter(". ").trim()
                blocks.add(MarkdownBlock.ListItem(true, num, listText))
            }
            else -> {
                currentParagraph.append(line).append("\n")
            }
        }
        i++
    }

    flushParagraph()
    flushTable()

    if (inCodeBlock) {
        blocks.add(MarkdownBlock.CodeBlock(codeLanguage, codeContent.toString().trimEnd()))
    }

    return blocks
}

@Composable
private fun MarkdownTable(
    headers: List<String>,
    rows: List<List<String>>,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.deerflowColors
    val borderColor = scheme.outlineVariant
    val headerBg = semantic.codeHeaderSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, borderColor, RoundedCornerShape(10.dp)),
        ) {
            Row(
                modifier = Modifier
                    .background(headerBg)
                    .height(IntrinsicSize.Min),
            ) {
                headers.forEachIndexed { index, header ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(borderColor),
                        )
                    }
                    Text(
                        text = parseMarkdownInline(header, scheme, semantic),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        modifier = Modifier
                            .widthIn(min = 64.dp, max = 220.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }

            HorizontalDivider(color = borderColor, thickness = 1.dp)

            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) {
                    HorizontalDivider(color = borderColor.copy(alpha = 0.55f), thickness = 1.dp)
                }
                val rowBg = if (rowIndex % 2 == 1) scheme.surfaceContainerLow else Color.Transparent
                Row(
                    modifier = Modifier
                        .background(rowBg)
                        .height(IntrinsicSize.Min),
                ) {
                    val colCount = headers.size.coerceAtLeast(row.size)
                    for (colIdx in 0 until colCount) {
                        if (colIdx > 0) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(borderColor.copy(alpha = 0.55f)),
                            )
                        }
                        Text(
                            text = parseMarkdownInline(row.getOrElse(colIdx) { "" }, scheme, semantic),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.92f),
                            modifier = Modifier
                                .widthIn(min = 64.dp, max = 220.dp)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun parseMarkdownInline(
    text: String,
    scheme: ColorScheme,
    semantic: DeerflowSemanticColors,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val len = text.length
    val linkStyle = SpanStyle(
        color = scheme.primary,
        textDecoration = TextDecoration.Underline,
        fontWeight = FontWeight.Medium,
    )
    while (i < len) {
        when {
            text[i] == '[' -> {
                val labelEnd = text.indexOf(']', i + 1)
                val urlStart = labelEnd + 1
                if (labelEnd != -1 && urlStart < len && text[urlStart] == '(') {
                    val urlEnd = text.indexOf(')', urlStart + 1)
                    if (urlEnd != -1) {
                        val label = text.substring(i + 1, labelEnd)
                        val url = text.substring(urlStart + 1, urlEnd).trim()
                        if (label.isNotEmpty() && isRenderableMarkdownUrl(url)) {
                            withLink(
                                LinkAnnotation.Url(
                                    url = url,
                                    styles = TextLinkStyles(style = linkStyle),
                                ),
                            ) {
                                append(label)
                            }
                            i = urlEnd + 1
                            continue
                        }
                    }
                }
            }
            i + 3 < len && text.substring(i, i + 2) == "**" -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    val content = text.substring(i + 2, end)
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(content)
                    pop()
                    i = end + 2
                    continue
                }
            }
            i + 3 < len && text.substring(i, i + 2) == "~~" -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    val content = text.substring(i + 2, end)
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(content)
                    pop()
                    i = end + 2
                    continue
                }
            }
            i + 1 < len && text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    val content = text.substring(i + 1, end)
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = semantic.inlineCodeSurface,
                            color = scheme.primary,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    append(" $content ")
                    pop()
                    i = end + 1
                    continue
                }
            }
            i + 2 < len && text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    val content = text.substring(i + 1, end)
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(content)
                    pop()
                    i = end + 1
                    continue
                }
            }
        }
        append(text[i])
        i++
    }
}

private fun isRenderableMarkdownUrl(url: String): Boolean =
    url.startsWith("http://") || url.startsWith("https://")

// ---------------------------------------------------------------------------
// Streaming animation helpers
// ---------------------------------------------------------------------------

@Composable
private fun StreamingIndicator(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "streaming")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
        ),
        label = "spin",
    )
    CircularProgressIndicator(
        progress = { (angle / 360f).coerceIn(0f, 1f) },
        modifier = modifier.size(16.dp),
        strokeWidth = 2.dp,
        color = tint,
    )
}

@Composable
private fun StreamingIcon(
    isStreaming: Boolean,
    staticIcon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    checkDurationMs: Int = 700,
) {
    var showCheck by remember { mutableStateOf(false) }
    var wasStreaming by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(isStreaming) {
        if (wasStreaming && !isStreaming) {
            showCheck = true
            delay(checkDurationMs.toLong())
            showCheck = false
        }
        wasStreaming = isStreaming
    }
    when {
        isStreaming -> StreamingIndicator(tint = tint, modifier = modifier)
        showCheck -> Icon(
            imageVector = Icons.Default.Check,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(16.dp),
        )
        else -> Icon(
            imageVector = staticIcon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier.size(16.dp),
        )
    }
}

@Composable
private fun TypingCursor(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blink",
    )
    Text(
        text = "▍",
        color = color.copy(alpha = alpha),
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier,
    )
}
