package com.deerflow.app.data.waku

import com.deerflow.app.data.oss.S3Settings
import com.deerflow.app.data.oss.S3Signing
import com.deerflow.app.data.oss.S3Storage
import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.MessageFile
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

class MessageFileCache(private val directory: File, private val http: OkHttpClient = defaultHttp) {
    data class Staged(val file: File, val size: Long, val sha256: String)

    companion object {
        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }

    fun stage(input: InputStream, name: String, maximum: Long): Staged {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建附件缓存" }
        val temporary = File.createTempFile("input-", ".part", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maximum) { "附件超过当前上传方式的大小限制" }
                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val basename = name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(60).trim(' ', '.').ifBlank { "attachment" }
            val target = File(directory, "$hash-$basename")
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return Staged(target, total, hash)
        } finally { temporary.delete() }
    }

    private fun cached(file: MessageFile): File? {
        val uri = runCatching { URI(file.localUrl) }.getOrNull() ?: return null
        if (uri.scheme != "file") return null
        val local = runCatching { File(uri).canonicalFile }.getOrNull() ?: return null
        if (!local.toPath().startsWith(directory.canonicalFile.toPath()) || !local.isFile || local.length() != file.size) return null
        val hash = file.oss?.sha256 ?: file.sha256
        return local.takeIf { hash.isBlank() || S3Signing.fileHash(it) == hash }
    }

    suspend fun resolve(file: MessageFile, settings: S3Settings,
        read: suspend (JsonObject) -> JsonObject): AgentArtifact = withContext(Dispatchers.IO) {
        require(file.size in 0..S3Storage.MAX_FILE_BYTES && (file.oss != null || file.sha256.isEmpty() || file.sha256.matches(Regex("[a-f0-9]{64}")))) { "附件元数据无效" }
        cached(file)?.let { return@withContext file.asArtifact().copy(url = it.toURI().toString()) }
        val staged = if (file.oss != null) {
            val url = S3Storage().downloadUrl(settings, file.oss)
            try {
                http.newCall(Request.Builder().url(url).header("User-Agent", settings.userAgent).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("OSS 附件读取未完成（HTTP ${response.code}）")
                    val body = response.body ?: throw IOException("OSS 附件没有返回内容")
                    body.byteStream().use { stage(it, file.name, file.size.coerceAtMost(S3Storage.MAX_FILE_BYTES)) }
                }
            } catch (error: IOException) {
                throw IOException(if (error.message?.startsWith("OSS 附件") == true) error.message else "OSS 附件读取中断，请稍后重试")
            }
        } else {
            val reference = file.serverReference ?: error("附件缺少可读取的引用")
            val result = read(wakuCommand(if (reference.startsWith("waku-blob:")) "readBlob" else "readAttachment") {
                put("reference", reference)
                if (!reference.startsWith("waku-blob:")) put("path", file.serverPath ?: error("附件缺少服务器路径"))
            })
            require(result.string("type") == "blobData") { "服务器返回的附件内容无效" }
            val encoded = result.string("bytes") ?: error("服务器附件内容为空")
            require(encoded.length.toLong() <= ((file.size + 2) / 3) * 4 + 4) { "附件内容超过声明大小" }
            Base64.getDecoder().decode(encoded).inputStream().use { stage(it, file.name, file.size) }
        }
        val expected = file.oss?.sha256 ?: file.sha256
        if (staged.size != file.size || (expected.isNotBlank() && staged.sha256 != expected)) {
            staged.file.delete()
            error("附件完整性校验失败，请重新上传")
        }
        file.asArtifact().copy(url = staged.file.toURI().toString())
    }
}
