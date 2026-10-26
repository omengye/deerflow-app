package com.deerflow.app.data.waku

import kotlinx.serialization.json.*

/** Tool labels must never fall back to command arguments, paths, or result text. */
internal object WakuToolPresentation {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_-]*")
    private val generic = setOf("tool", "tool_call", "tool_result", "unknown")
    private val builtinNames = setOf(
        "execute", "execute_command", "exec_command", "bash", "shell", "run_command",
        "read", "read_file", "readfile", "write", "write_file", "writefile",
        "edit", "edit_file", "editfile", "apply_patch", "list_files", "list_dir",
        "search", "grep", "glob", "web_search", "web_fetch", "fetch", "update_plan",
    )

    fun name(activity: JsonObject): String {
        activity.label("tool_name")?.let { return it }
        val input = when (val arguments = activity["arguments"]) {
            is JsonObject -> arguments
            is JsonPrimitive -> arguments.contentOrNull?.let {
                runCatching { WakuJson.parseToJsonElement(it) as? JsonObject }.getOrNull()
            }
            else -> null
        }
        input?.label("tool_name")?.let { return it }
        val title = activity.label("title")
        val inputTitles = listOf(input?.label("title")) + listOf("arguments", "input", "tool_input").map {
            (input?.get(it) as? JsonObject)?.label("title")
        }
        // Older ACP activities sometimes retain only the original tool identifier in title.
        // Descriptive titles and input-provided titles are not tool names.
        if (title != null && identifier.matches(title) && title !in inputTitles &&
            title.lowercase() !in generic &&
            (activity.label("kind") == "tool" || title.lowercase() in builtinNames || title.startsWith("mcp__"))) {
            return title
        }
        return when (activity.label("kind")) {
            "command" -> "执行命令"
            "fileRead" -> "读取文件"
            "fileChange" -> "编辑文件"
            "fileSearch" -> "搜索文件"
            "fileList" -> "列出文件"
            "search" -> "搜索"
            "plan" -> "更新计划"
            else -> "工具调用"
        }
    }

    private fun JsonObject.label(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}
