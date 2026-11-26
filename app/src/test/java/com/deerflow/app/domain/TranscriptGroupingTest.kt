package com.deerflow.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptGroupingTest {
    private fun userBlock(key: String, content: String, turnIndex: Int? = null) = DisplayBlock(
        key = key,
        kind = BlockKind.USER,
        header = "You",
        content = content,
        turnIndex = turnIndex,
    )

    private fun toolBlock(
        key: String,
        name: String,
        turnIndex: Int? = null,
        isRunning: Boolean = false,
        isError: Boolean = false,
        taskTitle: String? = null,
        agentName: String? = null,
        kind: String? = null,
    ) = DisplayBlock(
        key = key,
        kind = BlockKind.TOOL,
        header = name,
        content = "",
        tool = ToolDetails(
            name = name,
            isRunning = isRunning,
            isError = isError,
            taskTitle = taskTitle,
            agentName = agentName,
            kind = kind,
        ),
        turnIndex = turnIndex,
    )

    private fun textBlock(key: String, kind: BlockKind, content: String, turnIndex: Int? = null) = DisplayBlock(
        key = key,
        kind = kind,
        header = kind.name,
        content = content,
        turnIndex = turnIndex,
    )

    @Test
    fun contiguousToolsCollapseWithSemanticTitleInsteadOfUserPromptEcho() {
        val initialBlocks = listOf(
            userBlock("u1", "帮我看看这段日志为什么报错", turnIndex = 1),
            toolBlock("t1", "read_file", turnIndex = 1, isRunning = false, agentName = "sre-coordinator", kind = "fileRead"),
            toolBlock("t2", "grep", turnIndex = 1, isRunning = true, agentName = "sre-coordinator", kind = "search"),
        )
        val firstGrouped = TranscriptGrouping.group(initialBlocks)
        assertEquals(2, firstGrouped.size)
        val firstCard = (firstGrouped[1] as GroupedTranscriptEntry.ToolCard).group
        assertEquals("tool-group:t1", firstCard.key)
        assertEquals("正在检索与读取信息", firstCard.taskTitle)
        assertFalse(firstCard.taskTitle.contains("帮我看看"))
        assertEquals(2, firstCard.tools.size)
        assertEquals(1, firstCard.startBlockIndex)
        assertEquals(2, firstCard.endBlockIndex)
        assertTrue(firstCard.expandable)
        assertEquals(ToolGroupStatus.RUNNING, firstCard.status)
        assertEquals("grep", firstCard.featuredTool.name)

        val finishedBlocks = listOf(
            userBlock("u1", "帮我看看这段日志为什么报错", turnIndex = 1),
            toolBlock("t1", "read_file", turnIndex = 1, isRunning = false, kind = "fileRead"),
            toolBlock("t2", "grep", turnIndex = 1, isRunning = false, kind = "search"),
            toolBlock("t3", "list_dir", turnIndex = 1, isRunning = false, kind = "fileList"),
        )
        val secondGrouped = TranscriptGrouping.group(finishedBlocks)
        val secondCard = (secondGrouped[1] as GroupedTranscriptEntry.ToolCard).group
        assertEquals(firstCard.key, secondCard.key)
        assertEquals("已完成信息检索", secondCard.taskTitle)
        assertEquals(3, secondCard.tools.size)
        assertEquals(1, secondCard.startBlockIndex)
        assertEquals(3, secondCard.endBlockIndex)
        assertEquals("list_dir", secondCard.featuredTool.name)
    }

    @Test
    fun semanticTitlesReflectCategoriesAndErrors() {
        val runningCmd = (TranscriptGrouping.group(
            listOf(toolBlock("t1", "execute", isRunning = true, kind = "command")),
        )[0] as GroupedTranscriptEntry.ToolCard).group
        assertEquals("正在执行命令", runningCmd.taskTitle)

        val readAndCmd = (TranscriptGrouping.group(
            listOf(
                toolBlock("t1", "read_file", kind = "fileRead"),
                toolBlock("t2", "execute", kind = "command"),
            ),
        )[0] as GroupedTranscriptEntry.ToolCard).group
        assertEquals("已完成检索与命令执行", readAndCmd.taskTitle)

        val mixedCustom = (TranscriptGrouping.group(
            listOf(
                toolBlock("t1", "mcp__weather__query", kind = "tool"),
                toolBlock("t2", "read_file", kind = "fileRead"),
            ),
        )[0] as GroupedTranscriptEntry.ToolCard).group
        assertEquals("已完成工具调用", mixedCustom.taskTitle)

        val failedGroup = (TranscriptGrouping.group(
            listOf(
                toolBlock("t1", "read_file", kind = "fileRead"),
                toolBlock("t2", "execute", isError = true, kind = "command"),
            ),
        )[0] as GroupedTranscriptEntry.ToolCard).group
        assertEquals("工具调用异常（1 项失败）", failedGroup.taskTitle)
    }

    @Test
    fun precedingExplicitReasoningHeadingIsUsedOnceAndProseIsIgnored() {
        val blocksWithBoldHeading = listOf(
            userBlock("u1", "查一下生产故障", turnIndex = 1),
            textBlock("r1", BlockKind.REASONING, "**核对已有故障事实与调查进展：**\n先检查最近的日志。", turnIndex = 1),
            toolBlock("t1", "ReadIncident", turnIndex = 1, kind = "tool"),
            textBlock("r2", BlockKind.REASONING, "接下来看看普通思考段落，没有显式标题，需要执行命令验证。", turnIndex = 1),
            toolBlock("t2", "execute", turnIndex = 1, kind = "command"),
        )
        val grouped = TranscriptGrouping.group(blocksWithBoldHeading)
        assertEquals(5, grouped.size)
        val g1 = (grouped[2] as GroupedTranscriptEntry.ToolCard).group
        val g2 = (grouped[4] as GroupedTranscriptEntry.ToolCard).group
        assertEquals("核对已有故障事实与调查进展", g1.taskTitle)
        assertEquals("已完成命令执行", g2.taskTitle)

        val blocksWithAtxHeading = listOf(
            textBlock("r3", BlockKind.THINKING, "### 排查日志详情\n分析错误堆栈", turnIndex = 2),
            toolBlock("t3", "grep", turnIndex = 2, kind = "search"),
        )
        val atxGrouped = TranscriptGrouping.group(blocksWithAtxHeading)
        val g3 = (atxGrouped[1] as GroupedTranscriptEntry.ToolCard).group
        assertEquals("排查日志详情", g3.taskTitle)
    }

    @Test
    fun explicitTaskTitleOverridesReasoningHeadingAndSplitsWhenChanged() {
        val blocks = listOf(
            userBlock("u1", "请排查生产故障", turnIndex = 1),
            textBlock("r1", BlockKind.REASONING, "**思考阶段标题**", turnIndex = 1),
            toolBlock("t1", "read_file", turnIndex = 1, taskTitle = "阶段一：收集日志", kind = "fileRead"),
            toolBlock("t2", "grep", turnIndex = 1, taskTitle = null, kind = "search"),
            toolBlock("t3", "execute", turnIndex = 1, taskTitle = "阶段二：验证修复", kind = "command"),
        )
        val grouped = TranscriptGrouping.group(blocks)
        assertEquals(4, grouped.size)
        val first = (grouped[2] as GroupedTranscriptEntry.ToolCard).group
        val second = (grouped[3] as GroupedTranscriptEntry.ToolCard).group
        assertEquals("阶段一：收集日志", first.taskTitle)
        assertEquals(listOf("read_file", "grep"), first.tools.map { it.name })
        assertEquals("阶段二：验证修复", second.taskTitle)
        assertEquals(listOf("execute"), second.tools.map { it.name })
    }

    @Test
    fun toolsSeparatedByTurnStaySeparate() {
        val blocks = listOf(
            userBlock("u1", "第一轮", turnIndex = 1),
            toolBlock("t1", "read_file", turnIndex = 1, kind = "fileRead"),
            toolBlock("t2", "execute", turnIndex = 2, kind = "command"),
        )
        val grouped = TranscriptGrouping.group(blocks)
        assertEquals(3, grouped.size)
        val g1 = (grouped[1] as GroupedTranscriptEntry.ToolCard).group
        val g2 = (grouped[2] as GroupedTranscriptEntry.ToolCard).group
        assertEquals("已完成信息检索", g1.taskTitle)
        assertEquals("已完成命令执行", g2.taskTitle)
        assertFalse(g1.expandable)
        assertEquals("tool-group:t1", g1.key)
        assertEquals("tool-group:t2", g2.key)
    }

    @Test
    fun featuredToolAndStatusPrioritizeRunningThenFailedThenCompleted() {
        val runningBlocks = listOf(
            toolBlock("t1", "first", isRunning = true),
            toolBlock("t2", "second", isError = true),
            toolBlock("t3", "third", isRunning = false),
        )
        val runningGroup = (TranscriptGrouping.group(runningBlocks)[0] as GroupedTranscriptEntry.ToolCard).group
        assertEquals(ToolGroupStatus.RUNNING, runningGroup.status)
        assertEquals("first", runningGroup.featuredTool.name)

        val failedBlocks = listOf(
            toolBlock("t1", "first", isRunning = false),
            toolBlock("t2", "second", isError = true),
            toolBlock("t3", "third", isRunning = false),
        )
        val failedGroup = (TranscriptGrouping.group(failedBlocks)[0] as GroupedTranscriptEntry.ToolCard).group
        assertEquals(ToolGroupStatus.FAILED, failedGroup.status)
        assertEquals("third", failedGroup.featuredTool.name)

        val completedBlocks = listOf(
            toolBlock("t1", "first"),
            toolBlock("t2", "second"),
        )
        val completedGroup = (TranscriptGrouping.group(completedBlocks)[0] as GroupedTranscriptEntry.ToolCard).group
        assertEquals(ToolGroupStatus.COMPLETED, completedGroup.status)
        assertEquals("second", completedGroup.featuredTool.name)
    }
}
