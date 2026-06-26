package com.deerflow.app.data.agui

import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.AguiEvent
import com.deerflow.app.domain.model.ChatMessage
import com.deerflow.app.domain.model.ResumeEntry
import com.deerflow.app.domain.model.RunAgentInput
import com.deerflow.app.domain.model.Roles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.TimeUnit

data class UploadFilePart(
    val filename: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val openStream: () -> InputStream,
)

@Serializable
data class UploadedFile(
    val filename: String,
    val size: String? = null,
    val path: String? = null,
    @SerialName("virtual_path")
    val virtualPath: String? = null,
    @SerialName("artifact_url")
    val artifactUrl: String? = null,
    @SerialName("markdown_file")
    val markdownFile: String? = null,
    @SerialName("markdown_virtual_path")
    val markdownVirtualPath: String? = null,
    @SerialName("markdown_artifact_url")
    val markdownArtifactUrl: String? = null,
    @SerialName("original_filename")
    val originalFilename: String? = null,
)

@Serializable
data class UploadResponse(
    val success: Boolean = false,
    val files: List<UploadedFile> = emptyList(),
    val message: String? = null,
)

@Serializable
private data class ThreadDetailResponse(
    val title: String? = null,
    val artifacts: List<kotlinx.serialization.json.JsonElement> = emptyList(),
)

data class ThreadInfo(
    val title: String? = null,
    val artifacts: List<AgentArtifact> = emptyList(),
)

/**
 * AG-UI HTTP/SSE streaming client. Kotlin port of internal/agui/client.go.
 *
 * AG-UI uses POST with a `text/event-stream` response, so OkHttp's built-in
 * EventSource (GET-only) cannot be used; we read the response body line by line
 * exactly like the Go parseSSE implementation.
 */
class AguiClient(
    private val endpoint: String,
    private val headers: Map<String, String>,
    private val initialState: JsonObject,
    private val http: OkHttpClient = defaultHttp,
) {
    private val streamUrl: String
    private val threadsBaseUrl: String

    init {
        val httpUrl = endpoint.toHttpUrlOrNull()
        if (httpUrl != null) {
            val segments = httpUrl.pathSegments.filter { it.isNotEmpty() }
            val apiIndex = segments.indexOf("api")

            // Build a base URL with no path segments (scheme + host + port only)
            val baseUrl = httpUrl.newBuilder()
                .encodedPath("/")
                .build()

            if (apiIndex != -1) {
                // URL contains "api" segment, e.g. /api/chat/agui or /api/...
                val prefixSegments = segments.subList(0, apiIndex + 1) // up to and including "api"
                val hasAgui = segments.contains("chat") && segments.contains("agui")
                streamUrl = if (hasAgui) {
                    endpoint
                } else {
                    baseUrl.newBuilder().apply {
                        prefixSegments.forEach { addPathSegment(it) }
                        addPathSegment("chat")
                        addPathSegment("agui")
                    }.build().toString()
                }

                threadsBaseUrl = baseUrl.newBuilder().apply {
                    prefixSegments.forEach { addPathSegment(it) }
                    addPathSegment("threads")
                }.build().toString()
            } else {
                val isAgent = segments.contains("agent")
                if (isAgent) {
                    // Legacy /agent endpoint
                    streamUrl = endpoint
                    threadsBaseUrl = baseUrl.newBuilder().apply {
                        addPathSegment("api")
                        addPathSegment("threads")
                    }.build().toString()
                } else {
                    // Plain base URL with no recognized path, e.g. http://192.168.1.190:8000
                    streamUrl = baseUrl.newBuilder().apply {
                        segments.forEach { addPathSegment(it) }
                        addPathSegment("api")
                        addPathSegment("chat")
                        addPathSegment("agui")
                    }.build().toString()

                    threadsBaseUrl = baseUrl.newBuilder().apply {
                        segments.forEach { addPathSegment(it) }
                        addPathSegment("api")
                        addPathSegment("threads")
                    }.build().toString()
                }
            }
        } else {
            val clean = endpoint.removeSuffix("/")
            streamUrl = when {
                clean.endsWith("/api/chat/agui") || clean.endsWith("/agent") -> clean
                else -> "$clean/api/chat/agui"
            }
            threadsBaseUrl = when {
                clean.endsWith("/api/chat/agui") -> {
                    val base = clean.substring(0, clean.length - "/api/chat/agui".length)
                    "$base/api/threads"
                }
                clean.endsWith("/agent") -> {
                    val base = clean.substring(0, clean.length - "/agent".length)
                    "$base/api/threads"
                }
                else -> {
                    "$clean/api/threads"
                }
            }
        }
    }

    /** Start (or resume, when [resume] is non-empty) a run and stream its events. */
    fun runStream(
        threadId: String,
        history: List<ChatMessage>,
        resume: List<ResumeEntry> = emptyList(),
    ): Flow<AguiEvent> = flow {
        val payload = RunAgentInput(
            runId = newId("run"),
            threadId = threadId,
            state = initialState.takeIf { it.isNotEmpty() },
            messages = history.map {
                it.copy(
                    role = Roles.normalize(it.role),
                    id = it.id?.takeIf { it.isNotEmpty() } ?: newMsgId()
                )
            },
            resume = resume.ifEmpty { null },
        )

        val body = AguiJson.encodeToString(RunAgentInput.serializer(), payload)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(streamUrl)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        val call = http.newCall(request)
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val registration = job?.invokeOnCompletion {
            call.cancel()
        }

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val snippet = resp.body?.source()?.readUtf8Line().orEmpty()
                    error("run request failed: status=${resp.code} $snippet")
                }
                val reader = resp.body?.charStream() ?: error("empty response body")
                val dataLines = StringBuilder()
                var dataOriginalLength = 0
                var dataTruncated = false

                fun appendDataPayload(linePrefix: String, lineOriginalLength: Int, lineTruncated: Boolean) {
                    var start = "data:".length
                    if (start < linePrefix.length && linePrefix[start] == ' ') start += 1
                    val payloadLength = (lineOriginalLength - start).coerceAtLeast(0)
                    if (payloadLength <= 0) return
                    dataOriginalLength += payloadLength

                    val prefixPayloadLength = (linePrefix.length - start).coerceAtLeast(0)
                    if (!dataTruncated && prefixPayloadLength > 0) {
                        val remaining = MAX_SSE_EVENT_BUFFER_CHARS - dataLines.length
                        if (remaining > 0) {
                            val charsToCopy = minOf(remaining, prefixPayloadLength)
                            dataLines.append(linePrefix, start, start + charsToCopy)
                            if (charsToCopy < payloadLength) dataTruncated = true
                        } else {
                            dataTruncated = true
                        }
                    }
                    if (lineTruncated) dataTruncated = true
                }

                fun parseBufferedEvent(): AguiEvent {
                    val payload = dataLines.toString()
                    val event = if (dataTruncated) {
                        EventParser.parseTruncated(payload, dataOriginalLength)
                    } else {
                        EventParser.parse(payload)
                    }
                    return absolutizeArtifactUrls(event)
                }

                fun clearBufferedEvent() {
                    dataLines.setLength(0)
                    dataOriginalLength = 0
                    dataTruncated = false
                }

                suspend fun processLine(linePrefix: String, lineOriginalLength: Int, lineTruncated: Boolean) {
                    when {
                        lineOriginalLength == 0 -> {
                            // Blank line flushes the accumulated event.
                            if (dataLines.isNotEmpty() || dataTruncated) {
                                emit(parseBufferedEvent())
                                clearBufferedEvent()
                            }
                        }
                        linePrefix.startsWith("data:") -> appendDataPayload(linePrefix, lineOriginalLength, lineTruncated)
                        // Other SSE fields (event:, id:, :comment) are ignored.
                    }
                }

                val chars = CharArray(8 * 1024)
                val linePrefix = StringBuilder()
                var lineOriginalLength = 0
                var lineTruncated = false

                fun clearLine() {
                    linePrefix.setLength(0)
                    lineOriginalLength = 0
                    lineTruncated = false
                }

                while (true) {
                    currentCoroutineContext().ensureActive() // cancellation closes the socket
                    val read = reader.read(chars)
                    if (read == -1) break
                    for (idx in 0 until read) {
                        when (val ch = chars[idx]) {
                            '\n' -> {
                                processLine(linePrefix.toString(), lineOriginalLength, lineTruncated)
                                clearLine()
                            }
                            '\r' -> Unit
                            else -> {
                                lineOriginalLength += 1
                                if (linePrefix.length < MAX_SSE_LINE_BUFFER_CHARS) {
                                    linePrefix.append(ch)
                                } else {
                                    lineTruncated = true
                                }
                            }
                        }
                    }
                }

                if (lineOriginalLength > 0) {
                    processLine(linePrefix.toString(), lineOriginalLength, lineTruncated)
                }
                // EOF: flush any trailing buffered event.
                if (dataLines.isNotEmpty() || dataTruncated) {
                    emit(parseBufferedEvent())
                }
            }
        } finally {
            registration?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    private fun absolutizeArtifactUrls(event: AguiEvent): AguiEvent {
        if (event.type != "CUSTOM" || event.raw.str("name") != "deerflow.artifacts") return event
        val value = event.raw["value"] as? JsonObject ?: return event
        val artifacts = value["artifacts"] as? JsonArray ?: return event
        val rewritten = JsonArray(artifacts.map { item ->
            val obj = item as? JsonObject ?: return@map item
            val url = obj.str("url") ?: return@map item
            JsonObject(obj.toMutableMap().also { fields ->
                fields["url"] = JsonPrimitive(absoluteArtifactUrl(url))
            })
        })
        val newValue = JsonObject(value.toMutableMap().also { it["artifacts"] = rewritten })
        val newRaw = JsonObject(event.raw.toMutableMap().also { it["value"] = newValue })
        return event.copy(raw = newRaw)
    }

    private fun absoluteArtifactUrl(url: String): String {
        val trimmed = url.trim()
        val parsedEndpoint = endpoint.toHttpUrlOrNull()
        val parsedUrl = trimmed.toHttpUrlOrNull()
        if (parsedUrl != null) {
            if (parsedEndpoint != null && parsedUrl.host.isLoopbackHost() && parsedUrl.host != parsedEndpoint.host) {
                return parsedUrl.newBuilder()
                    .scheme(parsedEndpoint.scheme)
                    .host(parsedEndpoint.host)
                    .port(parsedEndpoint.port)
                    .build()
                    .toString()
            }
            return trimmed
        }
        val origin = parsedEndpoint
            ?.newBuilder()
            ?.encodedPath("/")
            ?.query(null)
            ?.fragment(null)
            ?.build()
            ?: return trimmed
        return origin.resolve(trimmed)?.toString() ?: trimmed
    }

    /** Upload local files into a DeerFlow thread before starting an AG-UI run. */
    suspend fun uploadFiles(threadId: String, files: List<UploadFilePart>): UploadResponse {
        if (files.isEmpty()) return UploadResponse(success = true, files = emptyList())

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                files.forEach { file ->
                    addFormDataPart(
                        "files",
                        file.filename,
                        file.asRequestBody(),
                    )
                }
            }
            .build()

        val request = Request.Builder()
            .url("$threadsBaseUrl/$threadId/uploads")
            .post(body)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("upload failed: status=${resp.code} ${bodyStr.take(300)}")
                }
                runCatching {
                    AguiJson.decodeFromString(UploadResponse.serializer(), bodyStr)
                }.getOrElse {
                    error("upload response could not be parsed: ${it.message}")
                }
            }
        }
    }

    /** Fetch the thread title from the backend API. */
    suspend fun fetchThreadTitle(threadId: String): String? = fetchThreadInfo(threadId)?.title

    /** Fetch thread metadata that is useful for local history recovery. */
    suspend fun fetchThreadInfo(threadId: String): ThreadInfo? {
        val url = "$threadsBaseUrl/$threadId"
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val bodyStr = resp.body?.string() ?: return@runCatching null
                    val detail = AguiJson.decodeFromString(ThreadDetailResponse.serializer(), bodyStr)
                    ThreadInfo(
                        title = detail.title,
                        artifacts = detail.artifacts.mapNotNull { artifactFromThreadDetail(threadId, it) },
                    )
                }
            }.getOrNull()
        }
    }


    private fun artifactFromThreadDetail(threadId: String, raw: kotlinx.serialization.json.JsonElement): AgentArtifact? {
        return when (raw) {
            is JsonPrimitive -> if (raw.isString) artifactFromVirtualPath(threadId, raw.content) else null
            is JsonObject -> {
                val path = raw.str("path") ?: raw.str("virtual_path") ?: return null
                val fallback = artifactFromVirtualPath(threadId, path) ?: return null
                fallback.copy(
                    name = raw.str("name") ?: raw.str("filename") ?: fallback.name,
                    url = raw.str("url")?.let(::absoluteArtifactUrl) ?: raw.str("artifact_url")?.let(::absoluteArtifactUrl) ?: fallback.url,
                    mimeType = raw.str("mimeType") ?: raw.str("mime_type") ?: fallback.mimeType,
                    kind = raw.str("kind") ?: fallback.kind,
                    size = raw.str("size")?.toLongOrNull() ?: fallback.size,
                )
            }
            else -> null
        }
    }

    private fun artifactFromVirtualPath(threadId: String, path: String): AgentArtifact? {
        val clean = path.trim()
        if (!clean.startsWith("/mnt/user-data/outputs/")) return null
        val name = clean.substringAfterLast('/').ifBlank { "artifact" }
        val mimeType = URLConnection.guessContentTypeFromName(name)
        return AgentArtifact(
            path = clean,
            name = name,
            url = artifactUrl(threadId, clean),
            mimeType = mimeType,
            kind = if (mimeType?.startsWith("image/") == true) "image" else "file",
        )
    }

    private fun artifactUrl(threadId: String, path: String): String {
        val base = "$threadsBaseUrl/$threadId/artifacts".toHttpUrlOrNull() ?: return "$threadsBaseUrl/$threadId/artifacts/${path.trimStart('/')}"
        return base.newBuilder().apply {
            path.trimStart('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        }.build().toString()
    }

    companion object {
        private const val MAX_SSE_EVENT_BUFFER_CHARS = 64 * 1024
        private const val MAX_SSE_LINE_BUFFER_CHARS = 64 * 1024

        private val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for long-lived SSE
            .build()

        private fun newId(prefix: String): String =
            "$prefix-${System.nanoTime()}-${UUID.randomUUID().toString().replace("-", "").take(16)}"

        private fun newMsgId(): String {
            val buf = ByteArray(6)
            java.security.SecureRandom().nextBytes(buf)
            return "msg-" + buf.joinToString("") { "%02x".format(it) }
        }
    }
}

private fun String.isLoopbackHost(): Boolean =
    equals("localhost", ignoreCase = true) || this == "127.0.0.1" || this == "::1" || this == "0.0.0.0"

private fun UploadFilePart.asRequestBody(): RequestBody {
    val contentType = (mimeType ?: "application/octet-stream").toMediaTypeOrNull()
    return object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength(): Long = sizeBytes ?: -1L

        override fun writeTo(sink: BufferedSink) {
            openStream().use { input ->
                sink.writeAll(input.source())
            }
        }
    }
}
