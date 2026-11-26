package com.deerflow.app.domain

enum class ToolGroupStatus { RUNNING, FAILED, COMPLETED }

data class ToolStep(
    val key: String,
    val name: String,
    val agentName: String? = null,
    val isRunning: Boolean = false,
    val isError: Boolean = false,
    val kind: String? = null,
)

data class ToolGroup(
    val key: String,
    val taskTitle: String,
    val tools: List<ToolStep>,
    val turnIndex: Int?,
    val startBlockIndex: Int,
    val endBlockIndex: Int,
) {
    val status: ToolGroupStatus
        get() = when {
            tools.any { it.isRunning } -> ToolGroupStatus.RUNNING
            tools.any { it.isError } -> ToolGroupStatus.FAILED
            else -> ToolGroupStatus.COMPLETED
        }

    val featuredTool: ToolStep
        get() = tools.lastOrNull { it.isRunning } ?: tools.last()

    val expandable: Boolean
        get() = tools.size > 1
}

sealed class GroupedTranscriptEntry {
    data class Single(val block: DisplayBlock, val blockIndex: Int) : GroupedTranscriptEntry()
    data class ToolCard(val group: ToolGroup) : GroupedTranscriptEntry()
}

/**
 * Collapses contiguous tool invocations within the same turn/task into a compact task card.
 * Keeps interleaved reasoning or assistant messages in chronological order.
 */
object TranscriptGrouping {
    private enum class ToolCategory { READ_SEARCH, EDIT, COMMAND, PLAN, OTHER }

    private const val MIN_REASONING_HEADING_CODE_POINTS = 2
    private const val MAX_REASONING_HEADING_CODE_POINTS = 28
    private val WHITESPACE_REGEX = Regex("[\\s\\p{Z}]+")
    private val BOLD_HEADING_REGEX = Regex("^\\*\\*((?:(?!\\*\\*).)+)\\*\\*$")
    private val ATX_HEADING_REGEX = Regex("^#{1,6}\\s+(.+?)\\s*#*$")

    private val PLAN_NAMES = setOf("update_plan", "更新计划")
    private val EDIT_KINDS = setOf("fileChange")
    private val EDIT_NAMES = setOf(
        "write", "write_file", "writefile", "edit", "edit_file", "editfile", "apply_patch", "编辑文件",
    )
    private val COMMAND_KINDS = setOf("command")
    private val COMMAND_NAMES = setOf(
        "execute", "execute_command", "exec_command", "bash", "shell", "run_command", "执行命令",
    )
    private val READ_SEARCH_KINDS = setOf("fileRead", "fileSearch", "fileList", "search")
    private val READ_SEARCH_NAMES = setOf(
        "read", "read_file", "readfile", "list_files", "list_dir",
        "search", "grep", "glob", "web_search", "web_fetch", "fetch",
        "读取文件", "搜索文件", "列出文件", "搜索",
    )

    fun group(blocks: List<DisplayBlock>): List<GroupedTranscriptEntry> {
        if (blocks.isEmpty()) return emptyList()
        val entries = mutableListOf<GroupedTranscriptEntry>()

        var precedingReasoningHeading: String? = null
        var precedingReasoningTurn: Int? = null

        var pendingBlocks = mutableListOf<DisplayBlock>()
        var pendingStartIndex = -1
        var pendingEndIndex = -1
        var pendingTurnIndex: Int? = null
        var pendingExplicitTitle: String? = null

        fun flushPending() {
            if (pendingBlocks.isEmpty()) return
            val steps = pendingBlocks.map { block ->
                val details = block.tool
                val isError = details?.isError == true
                val isRunning = details?.isRunning == true && !isError
                ToolStep(
                    key = block.key,
                    name = details?.name?.trim()?.takeIf { it.isNotEmpty() } ?: "工具调用",
                    agentName = details?.agentName?.trim()?.takeIf { it.isNotEmpty() },
                    isRunning = isRunning,
                    isError = isError,
                    kind = details?.kind?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            val reasoningTitle = if (precedingReasoningTurn == pendingTurnIndex) precedingReasoningHeading else null
            if (reasoningTitle != null) {
                precedingReasoningHeading = null
                precedingReasoningTurn = null
            }
            val resolvedTitle = pendingExplicitTitle
                ?: reasoningTitle
                ?: semanticTitle(steps)

            entries += GroupedTranscriptEntry.ToolCard(
                ToolGroup(
                    key = "tool-group:${pendingBlocks.first().key}",
                    taskTitle = resolvedTitle,
                    tools = steps,
                    turnIndex = pendingTurnIndex,
                    startBlockIndex = pendingStartIndex,
                    endBlockIndex = pendingEndIndex,
                ),
            )
            pendingBlocks = mutableListOf()
            pendingStartIndex = -1
            pendingEndIndex = -1
            pendingTurnIndex = null
            pendingExplicitTitle = null
        }

        blocks.forEachIndexed { index, block ->
            if (block.kind == BlockKind.TOOL) {
                val incomingTitle = block.tool?.taskTitle?.trim()?.takeIf { it.isNotEmpty() }
                val shouldSplit = pendingBlocks.isNotEmpty() && (
                    pendingTurnIndex != block.turnIndex ||
                        (pendingExplicitTitle != null && incomingTitle != null && incomingTitle != pendingExplicitTitle)
                    )
                if (shouldSplit) {
                    flushPending()
                }
                if (pendingBlocks.isEmpty()) {
                    pendingStartIndex = index
                    pendingTurnIndex = block.turnIndex
                }
                pendingEndIndex = index
                if (incomingTitle != null) {
                    pendingExplicitTitle = incomingTitle
                }
                pendingBlocks += block
            } else {
                flushPending()
                entries += GroupedTranscriptEntry.Single(block, index)
                if (block.kind == BlockKind.REASONING || block.kind == BlockKind.THINKING) {
                    val heading = extractExplicitReasoningHeading(block.content)
                    precedingReasoningHeading = heading
                    precedingReasoningTurn = if (heading != null) block.turnIndex else null
                } else {
                    precedingReasoningHeading = null
                    precedingReasoningTurn = null
                }
            }
        }
        flushPending()
        return entries
    }

    internal fun extractExplicitReasoningHeading(raw: String): String? {
        val firstLine = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        val extracted = BOLD_HEADING_REGEX.matchEntire(firstLine)?.groupValues?.get(1)
            ?: ATX_HEADING_REGEX.matchEntire(firstLine)?.groupValues?.get(1)?.let { inner ->
                val trimmedInner = inner.trim()
                BOLD_HEADING_REGEX.matchEntire(trimmedInner)?.groupValues?.get(1) ?: trimmedInner
            }
            ?: return null
        val normalized = extracted
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .trimEnd(':', '：')
            .trim()
        if (normalized.isEmpty()) return null
        val codePoints = normalized.codePointCount(0, normalized.length)
        return normalized.takeIf { codePoints in MIN_REASONING_HEADING_CODE_POINTS..MAX_REASONING_HEADING_CODE_POINTS }
    }

    private fun semanticTitle(steps: List<ToolStep>): String {
        if (steps.any { it.isRunning }) {
            val featured = steps.lastOrNull { it.isRunning } ?: steps.last()
            return when (categorize(featured)) {
                ToolCategory.READ_SEARCH -> "正在检索与读取信息"
                ToolCategory.EDIT -> "正在修改文件"
                ToolCategory.COMMAND -> "正在执行命令"
                ToolCategory.PLAN -> "正在规划执行步骤"
                ToolCategory.OTHER -> "正在调用工具处理"
            }
        }
        val errorCount = steps.count { it.isError }
        if (errorCount > 0) {
            return "工具调用异常（$errorCount 项失败）"
        }
        return when (steps.map(::categorize).toSet()) {
            setOf(ToolCategory.READ_SEARCH) -> "已完成信息检索"
            setOf(ToolCategory.EDIT) -> "已完成文件修改"
            setOf(ToolCategory.COMMAND) -> "已完成命令执行"
            setOf(ToolCategory.PLAN) -> "已更新执行计划"
            setOf(ToolCategory.READ_SEARCH, ToolCategory.EDIT) -> "已完成检索与文件修改"
            setOf(ToolCategory.READ_SEARCH, ToolCategory.COMMAND) -> "已完成检索与命令执行"
            setOf(ToolCategory.EDIT, ToolCategory.COMMAND) -> "已完成修改与命令验证"
            else -> "已完成工具调用"
        }
    }

    private fun categorize(step: ToolStep): ToolCategory {
        val kind = step.kind
        val name = step.name.trim().lowercase()
        return when {
            kind == "plan" || name in PLAN_NAMES -> ToolCategory.PLAN
            kind in EDIT_KINDS || name in EDIT_NAMES || name.startsWith("write_") || name.startsWith("edit_") ->
                ToolCategory.EDIT
            kind in COMMAND_KINDS || name in COMMAND_NAMES -> ToolCategory.COMMAND
            kind in READ_SEARCH_KINDS || name in READ_SEARCH_NAMES ||
                name.startsWith("read_") || name.startsWith("list_") ||
                name.startsWith("get_") || name.startsWith("search_") -> ToolCategory.READ_SEARCH
            else -> ToolCategory.OTHER
        }
    }
}
