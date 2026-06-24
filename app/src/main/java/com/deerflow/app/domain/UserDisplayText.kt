package com.deerflow.app.domain

/**
 * Converts internal user message payloads into text that is safe to show in the
 * transcript. Uploaded-file turns include agent-only instructions and virtual
 * paths; those should remain in history for the backend, but not leak into UI.
 */
object UserDisplayText {
    fun clean(raw: String): String {
        val normalized = normalizeText(raw)
        val uploadBlocks = UPLOAD_BLOCK_REGEX.findAll(normalized).map { it.value }.toList()
        val withoutUploadBlocks = UPLOAD_BLOCK_REGEX.replace(normalized, "")
        val withoutInstructions = stripUploadInstructions(withoutUploadBlocks)
        val summaryText = normalizeAttachedFileText(withoutInstructions)

        val files = (uploadBlocks.flatMap(::extractUploadFileNames) + extractAttachedSummaryFileNames(summaryText))
            .map(::cleanFileName)
            .filter { it.isNotEmpty() && !it.contains("/mnt/user-data/uploads/") }
            .distinct()

        val userText = CHINESE_ATTACHED_SUMMARY_REGEX
            .replace(ATTACHED_SUMMARY_REGEX.replace(summaryText, ""), "")
            .trim()
            .trimEnd(',', '\uFF0C')
            .takeUnless { isDefaultUploadPrompt(it) }
            .orEmpty()

        val lines = mutableListOf<String>()
        if (userText.isNotBlank()) lines += userText
        if (files.isNotEmpty()) lines += "\u9644\u4EF6\uFF1A${files.joinToString(", ")}"

        return lines.ifEmpty {
            if (looksLikeUploadTurn(normalized)) listOf("\u9644\u4EF6\u5DF2\u4E0A\u4F20") else listOf(stripUploadInstructions(normalized).trim())
        }.joinToString("\n").trim()
    }

    private fun normalizeText(text: String): String = text
        .replace('\uFFFD', ' ')
        .replace("\u00E2\u20AC\u201D", "-")
        .replace("\u00E2\u20AC\u201C", "-")
        .replace("\u00E2\u20AC\u0153", "\"")
        .replace("\u00E2\u20AC\uFFFD", "\"")
        .replace("\u00E2\u20AC\u02DC", "'")
        .replace("\u00E2\u20AC\u2122", "'")
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .trim()

    private fun normalizeAttachedFileText(text: String): String = text
        .replace(Regex("Attached file\\(s\\):\\s*[\\u2022-]\\s*"), "Attached file(s):\n- ")
        .replace(Regex("(?m)^\\s*\\u2022\\s+"), "- ")
        .trim()

    private fun stripUploadInstructions(text: String): String {
        val firstMarker = INSTRUCTION_MARKERS
            .mapNotNull { marker -> marker.find(text)?.range?.first }
            .minOrNull()
        return if (firstMarker == null) text else text.take(firstMarker)
    }

    private fun extractAttachedSummaryFileNames(text: String): List<String> {
        val names = mutableListOf<String>()

        ATTACHED_SUMMARY_REGEX.find(text)?.value
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.startsWith("- ") }
            ?.forEach { names += it.removePrefix("- ").trim() }

        val chineseSummary = CHINESE_ATTACHED_SUMMARY_REGEX.find(text)?.value
        if (chineseSummary != null) {
            chineseSummary
                .replace(Regex("^\\s*\\u9644\\u4EF6[:\\uFF1A]\\s*"), "")
                .split(',', '\uFF0C', '\n')
                .mapTo(names) { it.trim() }
        }

        return names
    }

    private fun extractUploadFileNames(uploadBlock: String): List<String> {
        return UPLOAD_FILE_LINE_REGEX.findAll(uploadBlock)
            .map { it.groupValues[1].trim() }
            .map(::cleanFileName)
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun cleanFileName(name: String): String {
        return stripUploadInstructions(name)
            .substringBefore(" Path:")
            .substringBefore(": /mnt/user-data/uploads/")
            .substringBefore(" (/mnt/user-data/uploads/")
            .replace(Regex("\\s+\\([^)]*\\)$"), "")
            .trim()
            .trim('-', '\u2022', ',', '\uFF0C', ':', '\uFF1A')
            .trim()
    }

    private fun isDefaultUploadPrompt(text: String): Boolean {
        val normalized = text.trim().trimEnd('.', '\u3002')
        return DEFAULT_UPLOAD_PROMPTS.any { normalized.equals(it, ignoreCase = true) }
    }

    private fun looksLikeUploadTurn(text: String): Boolean =
        UPLOAD_BLOCK_REGEX.containsMatchIn(text) ||
            ATTACHED_SUMMARY_REGEX.containsMatchIn(text) ||
            CHINESE_ATTACHED_SUMMARY_REGEX.containsMatchIn(text) ||
            DEFAULT_UPLOAD_PROMPTS.any { text.contains(it, ignoreCase = true) }

    private val INSTRUCTION_MARKERS = listOf(
        Regex("(?i)\\bRead\\s+(?:from\\s+)?the\\s+file\\s+first\\b"),
        Regex("(?i)\\bRead\\s+the\\s+file\\s+first\\b"),
        Regex("(?i)\\buse\\s+the\\s+outline\\s+line\\s+numbers\\b"),
        Regex("(?i)\\bTo\\s+work\\s+with\\s+these\\s+files\\s*:"),
        Regex("(?i)\\bUse\\s+`?read_file`?\\b"),
        Regex("(?i)\\bUse\\s+`?grep`?\\b"),
        Regex("(?i)\\bUse\\s+`?glob`?\\b"),
        Regex("(?i)\\bOnly\\s+fall\\s+back\\s+to\\s+web\\s+search\\b"),
        Regex("(?i)\\bThey\\s+are\\s+saved\\s+under\\s+/mnt/user-data/uploads\\b"),
    )

    private val DEFAULT_UPLOAD_PROMPTS = listOf(
        "Please analyze the uploaded files",
        "Please analyze the uploaded files.",
        "Please analyze the uploaded file",
        "Please analyze the uploaded file.",
    )

    private val UPLOAD_BLOCK_REGEX = Regex(
        "<uploaded_files(?:_from_android)?>.*?</uploaded_files(?:_from_android)?>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val UPLOAD_FILE_LINE_REGEX = Regex("(?m)(?:^|[:\\uFF1A])\\s*[-\\u2022]\\s+([^\\n]+)")
    private val ATTACHED_SUMMARY_REGEX = Regex(
        "Attached file\\(s\\):.*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val CHINESE_ATTACHED_SUMMARY_REGEX = Regex(
        "\\u9644\\u4EF6[:\\uFF1A].*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}
