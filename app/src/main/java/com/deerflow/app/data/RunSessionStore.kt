package com.deerflow.app.data

import com.deerflow.app.data.agui.AguiJson
import com.deerflow.app.domain.model.ChatMessage
import com.deerflow.app.domain.model.ResumeEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

@Serializable
internal data class RunSession(
    val threadId: String,
    val runId: String,
    @Transient
    val history: List<ChatMessage> = emptyList(),
    @Transient
    val resume: List<ResumeEntry> = emptyList(),
    val lastEventId: String? = null,
    val reconnectUntilEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
private data class RunSessionFile(
    val sessions: List<RunSession> = emptyList(),
)

/** Small durable store used only while AG-UI runs are active or reconnectable. */
internal class RunSessionStore(private val file: File) {
    fun load(): List<RunSession> {
        if (!file.exists() || file.length() > MAX_FILE_BYTES) return emptyList()
        return runCatching {
            AguiJson.decodeFromString(RunSessionFile.serializer(), file.readText()).sessions
        }.getOrDefault(emptyList())
    }

    fun save(sessions: Collection<RunSession>) {
        runCatching {
            val content = AguiJson.encodeToString(
                RunSessionFile.serializer(),
                RunSessionFile(sessions.sortedBy { it.threadId }),
            )
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(content)
            if (!temporary.renameTo(file)) {
                file.writeText(content)
                temporary.delete()
            }
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024L
    }
}
