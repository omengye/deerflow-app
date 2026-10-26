package com.deerflow.app.data.waku

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class WakuToolPresentationTest {
    @Test fun structuredNameWinsOverInputTitleAndOutput() {
        val activity = buildJsonObject {
            put("tool_name", "read_file"); put("kind", "fileRead")
            put("title", "/private/report.txt"); put("detail", "private output")
            put("arguments", """{"path":"/private/report.txt"}"""); put("output", "private output")
        }
        assertEquals("read_file", WakuToolPresentation.name(activity))
    }

    @Test fun wrappedToolMetadataRestoresNamesFromOlderActivities() {
        for (input in listOf<JsonElement>(
            JsonPrimitive("""{"tool_name":"mcp__weather__query","arguments":{"title":"Weather for a private location"}}"""),
            buildJsonObject { put("tool_name", "mcp__weather__query") },
        )) {
            assertEquals("mcp__weather__query", WakuToolPresentation.name(buildJsonObject {
                put("kind", "tool"); put("title", "Weather for a private location"); put("arguments", input)
            }))
        }
    }

    @Test fun oldCanonicalToolIdentifierRemainsVisible() {
        assertEquals("execute", WakuToolPresentation.name(buildJsonObject {
            put("kind", "command"); put("title", "execute")
            put("arguments", """{"command":"echo private"}""")
        }))
        assertEquals("custom_lookup", WakuToolPresentation.name(buildJsonObject {
            put("kind", "tool"); put("title", "custom_lookup")
        }))
    }

    @Test fun missingNameDoesNotExposeArgumentsOutputOrPathTitle() {
        for (title in listOf("cat /private/token", "README.md", "/private/report.txt", "private-token")) {
            assertEquals("执行命令", WakuToolPresentation.name(buildJsonObject {
                put("kind", "command"); put("title", title)
                put("arguments", """{"command":"cat /private/token"}"""); put("output", "private-result")
            }))
        }
    }

    @Test fun inputProvidedTitleIsNotMistakenForToolName() {
        for (input in listOf(
            """{"title":"private_identifier"}""",
            """{"arguments":{"title":"private_identifier"}}""",
            """{"input":{"title":"private_identifier"}}""",
            """{"tool_input":{"title":"private_identifier"}}""",
        )) {
            assertEquals("工具调用", WakuToolPresentation.name(buildJsonObject {
                put("kind", "tool"); put("title", "private_identifier"); put("arguments", input)
            }))
        }
    }

    @Test fun malformedMetadataAndUnknownToolHaveNeutralFallback() {
        assertEquals("工具调用", WakuToolPresentation.name(buildJsonObject {
            put("kind", "tool"); put("title", "Tool Result")
            put("arguments", "{bad"); put("output", "execute")
        }))
        assertEquals("读取文件", WakuToolPresentation.name(buildJsonObject {
            put("kind", "fileRead"); put("title", "secret.txt")
        }))
    }
}
