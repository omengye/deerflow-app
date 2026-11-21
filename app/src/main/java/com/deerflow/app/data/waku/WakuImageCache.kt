package com.deerflow.app.data.waku

import com.deerflow.app.data.oss.S3Signing
import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.MessageFile
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** A daemon blob reference is durable, but it is not an HTTP URL or a device path. */
class WakuImageReference private constructor(val value: String, val extension: String, val mimeType: String) {
    fun asArtifact() = AgentArtifact(value, "生成图片.$extension", "", mimeType, kind = "image")

    companion object {
        private val reference = Regex("waku-blob:[A-Za-z0-9_-]{1,128}\\.(png|jpg|jpeg|gif|webp)")
        private val markdownImage = Regex("!\\[[^\\]]*\\]\\(([^)]+)\\)")

        fun parse(value: String): WakuImageReference? {
            val match = reference.matchEntire(value) ?: return null
            val extension = match.groupValues[1]
            return WakuImageReference(value, extension, "image/" + if (extension == "jpg") "jpeg" else extension)
        }

        fun inMarkdown(text: String): List<WakuImageReference> = markdownImage.findAll(text).mapNotNull {
            parse(it.groupValues[1].trim().removeSurrounding("<", ">"))
        }.toList()
    }
}

/** One instance per server/credential profile. Metadata survives process death; bytes are disposable. */
class WakuImageCache(private val directory: File) {
    private val files = MessageFileCache(directory)
    // Bound decoded image memory and coalesce repeated references while a read is in progress.
    private val downloads = Mutex()

    suspend fun resolve(reference: String, read: suspend (JsonObject) -> JsonObject): AgentArtifact =
        withContext(Dispatchers.IO) {
            val image = WakuImageReference.parse(reference) ?: error("图片引用无效，请重新同步会话")
            downloads.withLock {
                val metadata = File(directory, S3Signing.sha256(reference.toByteArray(Charsets.UTF_8)) + ".json")
                readCached(metadata, image)?.let { return@withLock it }
                val response = read(wakuCommand("readBlob") { put("reference", reference) })
                require(response.string("type") == "blobData") { "服务器返回的图片内容无效" }
                val encoded = response.string("bytes") ?: error("服务器未返回图片内容")
                require(encoded.isNotEmpty() && encoded.length.toLong() <= ((MAX_IMAGE_BYTES + 2) / 3) * 4) {
                    "生成图片最大支持 20 MiB"
                }
                val bytes = try { Base64.getDecoder().decode(encoded) }
                catch (_: IllegalArgumentException) { error("图片数据损坏，请重新同步后重试") }
                require(bytes.size.toLong() in 1..MAX_IMAGE_BYTES && matchesImageType(bytes, image.mimeType)) {
                    "图片内容或格式无效，请重新同步后重试"
                }
                val staged = bytes.inputStream().use { files.stage(it, image.asArtifact().name, MAX_IMAGE_BYTES) }
                val file = MessageFile(reference, image.asArtifact().name, image.mimeType, staged.size,
                    localUrl = staged.file.toURI().toString(), serverReference = reference, sha256 = staged.sha256)
                val temporary = File.createTempFile("image-index-", ".part", directory)
                try {
                    temporary.outputStream().use { output ->
                        output.write(WakuJson.encodeToString(MessageFile.serializer(), file).toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    try {
                        Files.move(temporary.toPath(), metadata.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary.toPath(), metadata.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally { temporary.delete() }
                file.asArtifact()
            }
        }

    private fun readCached(metadata: File, image: WakuImageReference): AgentArtifact? = runCatching {
        if (!metadata.isFile || metadata.length() > 16 * 1024) return null
        val file = WakuJson.decodeFromString(MessageFile.serializer(), metadata.readText(Charsets.UTF_8))
        if (file.id != image.value || file.serverReference != image.value || file.mimeType != image.mimeType ||
            file.oss != null || file.size !in 1..MAX_IMAGE_BYTES || !file.sha256.matches(Regex("[a-f0-9]{64}"))) return null
        files.cached(file)?.let { file.asArtifact().copy(url = it.toURI().toString()) }
    }.getOrNull()

    companion object {
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

        private fun matchesImageType(bytes: ByteArray, mime: String): Boolean {
            fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
            return when (mime) {
                "image/png" -> starts(137, 80, 78, 71, 13, 10, 26, 10)
                "image/jpeg" -> starts(255, 216, 255)
                "image/gif" -> bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
                "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
                else -> false
            }
        }
    }
}
