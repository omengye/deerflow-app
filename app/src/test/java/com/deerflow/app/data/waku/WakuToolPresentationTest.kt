package com.deerflow.app.data.waku

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun taskTitleAndAgentNameOnlyReadStructuredTopLevelFields() {
        val structured = buildJsonObject {
            put("task_title", "核对已有故障事实")
            put("step_title", "兜底步骤标题")
            put("agent_name", "sre-coordinator")
            put("subagent", "fallback-agent")
            put("title", "/private/secret/path")
            put("detail", "sensitive output")
            put("arguments", """{"task_title":"leaked","agent_name":"leaked"}""")
        }
        assertEquals("核对已有故障事实", WakuToolPresentation.taskTitle(structured))
        assertEquals("sre-coordinator", WakuToolPresentation.agentName(structured))

        val fallbackKeys = buildJsonObject {
            put("step_title", "阶段验证")
            put("subagent", "worker-1")
        }
        assertEquals("阶段验证", WakuToolPresentation.taskTitle(fallbackKeys))
        assertEquals("worker-1", WakuToolPresentation.agentName(fallbackKeys))

        val unstructured = buildJsonObject {
            put("title", "/private/secret/path")
            put("detail", "sensitive output")
            put("output", "sensitive result")
            put("arguments", """{"task_title":"leaked","agent_name":"leaked"}""")
        }
        assertNull(WakuToolPresentation.taskTitle(unstructured))
        assertNull(WakuToolPresentation.agentName(unstructured))
    }
}
