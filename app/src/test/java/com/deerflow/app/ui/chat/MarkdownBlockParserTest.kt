package com.deerflow.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownBlockParserTest {
    @Test
    fun parsesBlockQuotesAndHorizontalDividers() {
        val markdown = """
            第一段正文

            > 引用说明第一行
            >引用说明第二行

            ---

            ***

            ___

            收尾段落
        """.trimIndent()

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("第一段正文"),
                MarkdownBlock.BlockQuote("引用说明第一行"),
                MarkdownBlock.BlockQuote("引用说明第二行"),
                MarkdownBlock.Divider,
                MarkdownBlock.Divider,
                MarkdownBlock.Divider,
                MarkdownBlock.Paragraph("收尾段落"),
            ),
            parseBlocks(markdown),
        )
    }

    @Test
    fun ignoresQuoteAndDividerMarkersInsideFencedCodeBlocks() {
        val markdown = """
            ```markdown
            > 示例代码引用
            ---
            ```
        """.trimIndent()

        assertEquals(
            listOf(
                MarkdownBlock.CodeBlock(
                    language = "markdown",
                    code = "> 示例代码引用\n---",
                ),
            ),
            parseBlocks(markdown),
        )
    }

    @Test
    fun parsesTableHeadersRowsAndFollowingText() {
        val markdown = """
            | 模块 | 状态 |
            | --- | :---: |
            | UI | 完成 |
            | 测试 | 通过 |

            表格后说明
        """.trimIndent()

        assertEquals(
            listOf(
                MarkdownBlock.Table(
                    headers = listOf("模块", "状态"),
                    rows = listOf(
                        listOf("UI", "完成"),
                        listOf("测试", "通过"),
                    ),
                ),
                MarkdownBlock.Paragraph("表格后说明"),
            ),
            parseBlocks(markdown),
        )
    }
}
