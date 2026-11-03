package com.deerflow.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UserDisplayTextTest {

    @Test
    fun cleanOrdinaryText() {
        val result = UserDisplayText.clean("Hello, can you help me with this code?")
        assertEquals("Hello, can you help me with this code?", result)
    }

    @Test
    fun cleanOssAttachmentEnvelope() {
        val raw = """
            请帮我分析这份文档

            [DEERFLOW_OSS_ATTACHMENTS_V1]
            {
              "version": 1,
              "attachments": [
                {
                  "id": "deerflow-app/attachments/123/report.pdf",
                  "name": "report.pdf",
                  "mimeType": "application/pdf",
                  "size": 1024,
                  "sha256": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                  "url": "https://s3.example.com/bucket/report.pdf?X-Amz-Signature=secret"
                }
              ]
            }
            [/DEERFLOW_OSS_ATTACHMENTS_V1]
            请使用 oss-attachments 技能下载并校验这些附件后处理。图片下载到工作目录后使用 view_image；不要仅根据文件名猜测内容。
        """.trimIndent()

        val result = UserDisplayText.clean(raw)
        assertEquals("请帮我分析这份文档\n附件：report.pdf", result)
    }

    @Test
    fun cleanOssAttachmentWithoutLeadingText() {
        val raw = """
            [DEERFLOW_OSS_ATTACHMENTS_V1]
            {
              "version": 1,
              "attachments": [
                {
                  "id": "deerflow-app/attachments/123/image.png",
                  "name": "image.png"
                }
              ]
            }
            [/DEERFLOW_OSS_ATTACHMENTS_V1]
            请使用 oss-attachments 技能下载并校验这些附件后处理。
        """.trimIndent()

        val result = UserDisplayText.clean(raw)
        assertEquals("附件：image.png", result)
    }
}
