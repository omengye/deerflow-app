package com.deerflow.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deerflow.app.domain.BlockKind
import com.deerflow.app.domain.DisplayBlock
import com.deerflow.app.domain.UserDisplayText

/** Renders one transcript block, styled by its kind, using a premium AI Chat aesthetic with Markdown. */
@Composable
fun BlockCard(block: DisplayBlock, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        when (block.kind) {
            BlockKind.USER -> {
                // User Message: Aligned Right, custom pill bubble
                Card(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        MarkdownText(
                            text = UserDisplayText.clean(block.content),
                            textColor = scheme.onPrimaryContainer
                        )
                    }
                }
            }

            BlockKind.ASSISTANT -> {
                // Assistant Message: Aligned Left, clean surface bubble
                val agentName = remember(block.header) {
                    val idx = block.header.indexOf("agent:")
                    if (idx >= 0) block.header.substring(idx + 6).trim() else null
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(0.85f)
                ) {
                    if (agentName != null) {
                        Text(
                            text = agentName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = scheme.primary,
                            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                        )
                    }
                    Card(
                        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp),
                        colors = CardDefaults.cardColors(containerColor = scheme.secondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline.copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                            MarkdownText(
                                text = block.content,
                                textColor = scheme.onSecondary
                            )
                        }
                    }
                }
            }

            BlockKind.THINKING, BlockKind.REASONING -> {
                // Reasoning/Thinking: Accordion Collapsible Box
                var expanded by remember { mutableStateOf(false) }
                val isThinking = block.kind == BlockKind.THINKING
                val title = if (isThinking) "Thinking Process" else "Reasoning Output"
                val icon = if (isThinking) Icons.Default.Info else Icons.Default.Build

                Card(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .animateContentSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = scheme.secondaryContainer.copy(alpha = 0.3f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline.copy(alpha = 0.2f))
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = title,
                                    tint = scheme.tertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Icon(
                                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle",
                                tint = scheme.onSecondaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (expanded && block.content.isNotBlank()) {
                            HorizontalDivider(color = scheme.outline.copy(alpha = 0.15f))
                            SelectionContainer {
                                Text(
                                    text = block.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSecondaryContainer.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }

            BlockKind.TOOL -> {
                // Tool Call or Tool Result: Collapsible Box (matching Thinking style)
                var expanded by remember { mutableStateOf(false) }
                val isToolCall = remember(block.header) { block.header.contains("TOOL_CALL") }

                val title = remember(block.header, block.content) {
                    if (isToolCall) {
                        val toolName = block.content.substringBefore("|").trim()
                        "Tool Call: $toolName"
                    } else {
                        val toolId = block.header.substringAfter("#", "").trim()
                        if (toolId.isNotEmpty()) "Tool Result: #$toolId" else "Tool Result"
                    }
                }

                Card(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .animateContentSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = scheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline.copy(alpha = 0.2f))
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Build,
                                    contentDescription = "Tool Call",
                                    tint = scheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Icon(
                                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle",
                                tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (expanded && block.content.isNotBlank()) {
                            HorizontalDivider(color = scheme.outline.copy(alpha = 0.15f))

                            if (isToolCall) {
                                // Parse and layout name, args, result neatly
                                val parts = remember(block.content) { block.content.split("|").map { it.trim() } }
                                val args = remember(parts) { parts.getOrNull(1)?.removePrefix("args:")?.trim().orEmpty() }
                                val result = remember(parts) { parts.getOrNull(2)?.removePrefix("result:")?.trim().orEmpty() }

                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    if (args.isNotEmpty()) {
                                        Text(
                                            text = "Arguments:",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = scheme.primary.copy(alpha = 0.8f)
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        SelectionContainer {
                                            Text(
                                                text = args,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = scheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (result.isNotEmpty()) {
                                        if (args.isNotEmpty()) Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "Result:",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = scheme.primary.copy(alpha = 0.8f)
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        SelectionContainer {
                                            Text(
                                                text = result,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = scheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Tool result raw content (usually JSON)
                                SelectionContainer {
                                    Text(
                                        text = block.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            BlockKind.INTERRUPT, BlockKind.ERROR -> {
                // Warning/Alert/Interrupt Card
                val isError = block.kind == BlockKind.ERROR
                val borderCol = if (isError) scheme.error else scheme.tertiary

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.errorContainer),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, borderCol)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isError) Icons.Default.Warning else Icons.Default.Info,
                                contentDescription = "Alert",
                                tint = borderCol,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = block.header,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isError) scheme.onErrorContainer else scheme.onTertiaryContainer
                            )
                        }
                        if (block.content.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(
                                    text = block.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isError) scheme.onErrorContainer.copy(alpha = 0.9f) else scheme.onTertiaryContainer.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }
            }

            BlockKind.SYSTEM -> {
                // System Badge: Minimal Centered Info Text
                SelectionContainer {
                    Text(
                        text = "${block.header}: ${block.content}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurface.copy(alpha = 0.35f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Custom Lightweight Markdown Composable & Parser
// ---------------------------------------------------------------------------

private sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class ListItem(val ordered: Boolean, val index: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
}

@Composable
private fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val scheme = MaterialTheme.colorScheme
    val blocks = remember(text) { parseBlocks(text) }

    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.4f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline.copy(alpha = 0.2f))
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            if (block.language.isNotEmpty()) {
                                Text(
                                    text = block.language.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = block.code,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
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
                        text = parseMarkdownInline(block.text, scheme),
                        color = textColor,
                        style = style,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = if (block.ordered) "${block.index}. " else "• ",
                            color = textColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = parseMarkdownInline(block.text, scheme),
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseMarkdownInline(block.text, scheme),
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                is MarkdownBlock.Table -> {
                    MarkdownTable(
                        headers = block.headers,
                        rows = block.rows,
                        textColor = textColor
                    )
                }
            }
        }
        }
    }
}

/** Check if a line looks like a markdown table row: starts and ends with | or contains | separators */
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

private fun parseBlocks(text: String): List<MarkdownBlock> {
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
    var tableSeparatorSeen = false

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
        tableSeparatorSeen = false
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
            tableSeparatorSeen = true
            i += 2 // skip header row + separator row
            continue
        }

        if (inTable) {
            if (isTableRow(trimmed) && !isTableSeparator(trimmed)) {
                tableRows.add(parseTableCells(trimmed))
                i++
                continue
            } else {
                // End of table
                flushTable()
                // Don't increment i, re-process this line
                continue
            }
        }

        when {
            trimmed.isEmpty() -> {
                flushParagraph()
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
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = scheme.outline.copy(alpha = 0.3f)
    val headerBg = scheme.surfaceVariant.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
        ) {
            // Header row
            Row(
                modifier = Modifier
                    .background(headerBg, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .height(IntrinsicSize.Min)
            ) {
                headers.forEachIndexed { index, header ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(IntrinsicSize.Max)
                                .background(borderColor)
                        )
                    }
                    Text(
                        text = header,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            HorizontalDivider(color = borderColor, thickness = 1.dp)

            // Data rows
            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) {
                    HorizontalDivider(color = borderColor.copy(alpha = 0.15f), thickness = 1.dp)
                }
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min)
                ) {
                    val colCount = headers.size.coerceAtLeast(row.size)
                    for (colIdx in 0 until colCount) {
                        if (colIdx > 0) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(IntrinsicSize.Max)
                                    .background(borderColor.copy(alpha = 0.15f))
                            )
                        }
                        Text(
                            text = row.getOrElse(colIdx) { "" },
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.9f),
                            modifier = Modifier
                                .width(IntrinsicSize.Max)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun parseMarkdownInline(text: String, scheme: ColorScheme): AnnotatedString = buildAnnotatedString {
    var i = 0
    val len = text.length
    while (i < len) {
        when {
            // Bold
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
            // Inline Code
            i + 1 < len && text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    val content = text.substring(i + 1, end)
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = scheme.surfaceVariant.copy(alpha = 0.6f),
                            color = scheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    append(" $content ")
                    pop()
                    i = end + 1
                    continue
                }
            }
            // Italic
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
