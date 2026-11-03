package com.deerflow.app.data.oss

import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.concurrent.TimeUnit
import com.deerflow.app.domain.model.S3Object

@Serializable
data class S3Settings(
    val endpoint: String = "https://s3.cstcloud.cn",
    val bucket: String = "52e24feeab1948d3a16697f4ccb0d814",
    val region: String = "us-east-1",
    val accessKeyId: String = "",
    val accessKeySecret: String = "",
    val sessionToken: String = "",
    val userAgent: String = "S3Drive",
) {
    val validationError: String? get() {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
        return when {
            uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
                uri.query != null || uri.fragment != null || uri.path !in listOf("", "/") -> "OSS 地址必须为 HTTPS 服务端点"
            !bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) -> "OSS 桶名无效"
            !region.matches(Regex("[a-zA-Z0-9-]{1,64}")) -> "OSS 区域无效"
            accessKeyId.isBlank() || accessKeySecret.isBlank() -> "请填写 OSS Access Key 和 Secret Key"
            userAgent.isBlank() || userAgent.length > 256 || userAgent.any { it.code !in 32..126 } -> "OSS 客户端标识无效"
            else -> null
        }
    }
    val configured: Boolean get() = validationError == null
    override fun toString(): String = "S3Settings(endpoint=$endpoint, bucket=$bucket, region=$region, configured=$configured)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun importProfile(text: String): S3Settings {
            require(text.length <= 32 * 1024) { "OSS 配置文件过大" }
            val parsed = json.decodeFromString(serializer(), text.removePrefix("\uFEFF"))
            return parsed.copy(
                endpoint = parsed.endpoint.trim().trimEnd('/'),
                bucket = parsed.bucket.trim(),
                region = parsed.region.trim(),
                accessKeyId = parsed.accessKeyId.trim(),
                accessKeySecret = parsed.accessKeySecret.trim(),
                sessionToken = parsed.sessionToken.trim(),
                userAgent = parsed.userAgent.trim(),
            ).also {
                require(it.validationError == null) { it.validationError.orEmpty() }
            }
        }
    }
}

object S3Signing {
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val c = byte.toInt() and 255
            if (c in 65..90 || c in 97..122 || c in 48..57 || c in listOf(45, 46, 95, 126)) append(c.toChar())
            else append("%%%02X".format(c))
        }
    }
    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hmac(key: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray(Charsets.UTF_8))
    }
    private fun signingKey(settings: S3Settings, date: String): ByteArray =
        hmac(hmac(hmac(hmac(("AWS4" + settings.accessKeySecret).toByteArray(Charsets.UTF_8), date), settings.region), "s3"), "aws4_request")
    private fun scope(settings: S3Settings, date: String) = "$date/${settings.region}/s3/aws4_request"
    private fun objectPath(settings: S3Settings, key: String): String {
        require(key.isNotBlank() && !key.startsWith('/') && key.split('/').none { it in listOf(".", "..", "") }) { "OSS 文件标识无效" }
        return "/" + encode(settings.bucket) + "/" + key.split('/').joinToString("/") { encode(it) }
    }
    private fun authority(settings: S3Settings): String {
        val uri = URI(settings.endpoint.trim())
        val host = uri.host.lowercase()
        return if (uri.port > 0 && uri.port != 443) "$host:${uri.port}" else host
    }

    fun presign(settings: S3Settings, key: String, method: String = "GET", now: Instant = Instant.now(), expiresSeconds: Long = 86_400): String {
        require(settings.validationError == null) { settings.validationError.orEmpty() }
        require(method in listOf("GET", "PUT", "HEAD", "DELETE") && expiresSeconds in 1..604_800)
        val date = day.format(now)
        val values = sortedMapOf(
            "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
            "X-Amz-Credential" to "${settings.accessKeyId}/${scope(settings, date)}",
            "X-Amz-Date" to timestamp.format(now),
            "X-Amz-Expires" to expiresSeconds.toString(),
            "X-Amz-SignedHeaders" to "host",
        )
        if (settings.sessionToken.isNotBlank()) values["X-Amz-Security-Token"] = settings.sessionToken
        val query = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val path = objectPath(settings, key)
        val canonical = "$method\n$path\n$query\nhost:${authority(settings)}\n\nhost\nUNSIGNED-PAYLOAD"
        val toSign = "AWS4-HMAC-SHA256\n${timestamp.format(now)}\n${scope(settings, date)}\n${sha256(canonical.toByteArray(Charsets.UTF_8))}"
        val signature = hex(hmac(signingKey(settings, date), toSign))
        return "https://${authority(settings)}$path?$query&X-Amz-Signature=$signature"
    }
}

class S3Storage(private val client: OkHttpClient = defaultClient) {
    suspend fun upload(settings: S3Settings, file: File, key: String, name: String, mime: String): S3Object = withContext(Dispatchers.IO) {
        require(file.length() in 0..MAX_FILE_BYTES) { "OSS 附件最大 50 MiB" }
        val hash = S3Signing.fileHash(file)
        val request = Request.Builder().url(S3Signing.presign(settings, key, "PUT", expiresSeconds = 900))
            .header("User-Agent", settings.userAgent).put(file.asRequestBody(mime.toMediaTypeOrNull())).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("OSS 上传未完成（HTTP ${response.code}）")
            }
        } catch (error: IOException) {
            // URLs and HTTP exception messages can contain temporary signatures.
            throw IOException(if (error.message?.startsWith("OSS 上传") == true) error.message else "OSS 上传中断，请检查网络后重新提交")
        }
        S3Object(settings.endpoint.trim().trimEnd('/'), settings.bucket, key, name, mime, file.length(), hash)
    }

    fun downloadUrl(settings: S3Settings, objectRef: S3Object): String {
        require(settings.endpoint.trim().trimEnd('/') == objectRef.endpoint && settings.bucket == objectRef.bucket) { "请配置此附件所属的 OSS 账号" }
        return S3Signing.presign(settings, objectRef.key)
    }

    companion object {
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
