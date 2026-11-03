package com.deerflow.app.data.oss

import com.deerflow.app.domain.model.MessageFile
import kotlinx.serialization.json.*
import java.time.Instant

/** The signed read URLs contain an account ID, but never the secret key. */
object OssAttachmentEnvelope {
    private const val START = "[DEERFLOW_OSS_ATTACHMENTS_V1]"
    private const val END = "[/DEERFLOW_OSS_ATTACHMENTS_V1]"

    fun append(text: String, files: List<MessageFile>, settings: S3Settings, now: Instant = Instant.now()): String {
        val remote = files.mapNotNull { it.oss }
        if (remote.isEmpty()) return text.trim()
        val manifest = buildJsonObject {
            put("version", 1)
            put("attachments", buildJsonArray {
                remote.forEach { file -> add(buildJsonObject {
                    put("id", file.key); put("name", file.name); put("mimeType", file.mimeType)
                    put("size", file.size); put("sha256", file.sha256)
                    put("url", S3Signing.presign(settings, file.key, now = now))
                    put("headers", buildJsonObject { put("User-Agent", settings.userAgent) })
                }) }
            })
        }
        return text.trim() + "\n\n$START\n$manifest\n$END\n" +
            "请使用 oss-attachments 技能下载并校验这些附件后处理。图片下载到工作目录后使用 view_image；不要仅根据文件名猜测内容。"
    }
}
