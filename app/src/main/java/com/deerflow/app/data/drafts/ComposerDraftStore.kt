package com.deerflow.app.data.drafts

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

@Serializable
data class DraftAttachment(val uri: String, val displayName: String, val mimeType: String? = null, val sizeBytes: Long? = null, val uploadToOss: Boolean = false)

@Serializable
data class ComposerDraft(val text: String = "", val attachments: List<DraftAttachment> = emptyList())

/** A local draft never authorizes a send. Each profile/thread has a separate atomic file. */
class ComposerDraftStore(private val directory: File) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(key: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$name.json")
    }
    suspend fun load(key: String): ComposerDraft = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = file(key)
            if (!target.exists()) return@withLock ComposerDraft()
            check(target.length() <= MAX_BYTES) { "草稿文件过大，请核对后清空草稿" }
            json.decodeFromString(ComposerDraft.serializer(), target.readText(Charsets.UTF_8))
        }
    }
    suspend fun save(key: String, draft: ComposerDraft): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            coroutineContext.ensureActive()
            check(directory.isDirectory || directory.mkdirs()) { "无法创建草稿目录" }
            val target = file(key)
            if (draft.text.isEmpty() && draft.attachments.isEmpty()) {
                check(!target.exists() || target.delete()) { "无法清除已提交草稿" }
                return@withLock
            }
            val bytes = json.encodeToString(ComposerDraft.serializer(), draft).toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_BYTES) { "草稿过大，无法在设备保存" }
            val temporary = File(directory, "${target.name}.tmp")
            try {
                FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
            Unit
        }
    }
    private companion object { const val MAX_BYTES = 4 * 1024 * 1024L }
}
