package com.deerflow.app.data.waku

import com.deerflow.app.domain.model.AgentArtifact
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Read-only task controls. ACK means queued; only the correlated driver reply contains a result. */
class WakuTaskArtifacts(
    private val cacheDir: File,
    private val maxFileBytes: Long = 64L * 1024 * 1024,
    private val maxTotalBytes: Long = 128L * 1024 * 1024,
    private val chunkBytes: Int = 256 * 1024,
    private val requestTimeoutMs: Long = 30_000,
    private val send: suspend (command: JsonObject, sessionId: String, runtimeId: String) -> JsonObject,
) {
    private data class Key(val session: String, val runtime: String)
    private data class Binding(val connection: String, val acpSession: String, val epoch: String, val sequence: Long, val capabilities: JsonObject)
    private data class Pending(val key: Key, val command: JsonObject, val response: CompletableDeferred<JsonObject>)
    private data class Artifact(val root: String, val node: String, val id: String, val name: String, val mime: String, val size: Long)

    private val lock = Any()
    private val loads = Mutex()
    private val bindings = mutableMapOf<Key, Binding>()
    private val pending = mutableMapOf<String, Pending>()
    private var closed = false

    init {
        require(maxFileBytes > 0 && maxTotalBytes >= maxFileBytes)
        require(chunkBytes in 1..1_048_576 && requestTimeoutMs > 0)
    }

    /** Call for live AND replayed envelopes, without awaiting loadArtifacts on the event consumer. */
    fun onWireEvent(wire: JsonObject) {
        val event = wire["event"] as? JsonObject ?: return
        if (event.text("kind") != "deerflowTasks") return
        val payload = event["payload"] as? JsonObject ?: return
        val key = Key(wire.text("sessionId") ?: return, wire.text("runtimeId") ?: return)
        synchronized(lock) {
            if (closed) return
            when (payload.text("kind")) {
                "ready" -> {
                    val binding = Binding(
                        payload.text("connectionId") ?: return,
                        payload.text("sessionId") ?: return,
                        wire.text("epoch") ?: return,
                        (wire["sequence"] as? JsonPrimitive)?.longOrNull ?: return,
                        payload["capabilities"] as? JsonObject ?: return,
                    )
                    val previous = bindings[key]
                    if (previous != null && (previous.epoch != binding.epoch || previous.sequence >= binding.sequence)) return
                    if (previous != null && previous.connection != binding.connection) {
                        failPending(key, "任务连接已变化，请重新刷新产物")
                    }
                    bindings[key] = binding
                }
                "reply", "failed" -> {
                    val command = payload["command"] as? JsonObject ?: return
                    val id = command.text("requestId") ?: return
                    val request = pending[id] ?: return
                    if (request.key != key || request.command != command) return
                    pending.remove(id)
                    if (payload.text("kind") == "failed") {
                        request.response.completeExceptionally(IOException("产物查询未完成：" + (payload.text("message") ?: "请稍后刷新")))
                    } else {
                        val response = payload["response"] as? JsonObject
                        if (response == null) request.response.completeExceptionally(IOException("无效的产物查询响应"))
                        else request.response.complete(response)
                    }
                }
            }
        }
    }

    /** One helper belongs to one WebSocket link. Do not carry ACP authority across a new link. */
    fun disconnected() = synchronized(lock) {
        closed = true
        bindings.clear()
        pending.values.forEach { it.response.completeExceptionally(IOException("连接已断开，产物下载未完成")) }
        pending.clear()
    }

    suspend fun loadArtifacts(sessionId: String, runtimeId: String): List<AgentArtifact> = loads.withLock {
        withContext(Dispatchers.IO) {
            requireWakuUuid(sessionId, "sessionId")
            requireWakuUuid(runtimeId, "runtimeId")
            val key = Key(sessionId, runtimeId)
            ensureBinding(key)
            val binding = synchronized(lock) { bindings[key] } ?: throw IOException("当前会话尚未声明产物能力")
            if (!binding.capabilities.flag("tree") || !binding.capabilities.flag("artifactList") || !binding.capabilities.flag("artifactRead")) {
                throw IOException("当前服务器未提供可读取的任务产物，请升级服务器")
            }
            val files = linkedMapOf<String, Artifact>()
            var total = 0L
            var treeCursor: String? = null
            val treeCursors = mutableSetOf<String>()
            var nodeCount = 0
            do {
                val page = control(key, binding, action("list") {
                    put("rootId", JsonNull); put("cursor", treeCursor?.let(::JsonPrimitive) ?: JsonNull); put("limit", 100)
                }, "page")
                checkData(page.text("sessionId") == binding.acpSession, "任务页会话不匹配")
                for (value in page.requiredArray("nodes")) {
                    val node = value as? JsonObject ?: throw IOException("无效的任务节点")
                    checkData(++nodeCount <= 256, "任务节点超过本次产物读取上限")
                    val root = node.requiredText("rootId")
                    val nodeId = node.requiredText("id")
                    checkData(node.text("rootSessionId") == binding.acpSession, "任务节点属于其他会话")
                    var cursor: String? = null
                    val cursors = mutableSetOf<String>()
                    do {
                        val artifacts = control(key, binding, action("artifactList") {
                            put("rootId", root); put("nodeId", nodeId)
                            put("cursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("limit", 100)
                        }, "artifacts")
                        checkData(artifacts.text("rootId") == root && artifacts.text("nodeId") == nodeId, "产物页节点不匹配")
                        for (item in artifacts.requiredArray("artifacts")) {
                            val value = item as? JsonObject ?: throw IOException("无效的产物元数据")
                            val artifact = parseArtifact(root, nodeId, value)
                            val identity = "$root/$nodeId/${artifact.id}"
                            if (files.containsKey(identity)) continue
                            checkData(files.size < 64, "单次最多读取 64 个产物")
                            checkData(artifact.size <= maxFileBytes && artifact.size <= maxTotalBytes - total, "产物超过移动端下载大小上限")
                            total += artifact.size
                            files[identity] = artifact
                        }
                        cursor = nextCursor(artifacts, cursors)
                    } while (cursor != null)
                }
                treeCursor = nextCursor(page, treeCursors)
            } while (treeCursor != null)
            files.values.map { download(key, binding, it) }
        }
    }

    /** A saved projection cursor may be after Ready; recover authority from this live runtime's journal. */
    private suspend fun ensureBinding(key: Key) {
        synchronized(lock) {
            checkData(!closed, "连接已断开")
            if (bindings.containsKey(key)) return
        }
        var after = 0L
        var through: Long? = null
        var epoch: String? = null
        repeat(128) {
            val page = send(wakuCommand("replayEvents") {
                put("epoch", epoch?.let(::JsonPrimitive) ?: JsonNull)
                put("afterSequence", after); put("throughSequence", through?.let(::JsonPrimitive) ?: JsonNull); put("limit", 256)
            }, key.session, key.runtime)
            checkData(page.text("type") == "replayEvents", "无法恢复任务产物连接")
            val pageEpoch = page.requiredText("epoch")
            checkData(epoch == null || epoch == pageEpoch, "任务连接版本已变化")
            val upper = page.requiredLong("throughSequence")
            checkData(through == null || through == upper, "任务连接重放边界变化")
            val next = page.requiredLong("nextSequence")
            checkData(next in after..upper, "任务连接重放游标无效")
            var sequence = after
            for (element in page.requiredArray("events")) {
                val wire = element as? JsonObject ?: throw IOException("无效的任务连接事件")
                checkData(wire.text("sessionId") == key.session && wire.text("runtimeId") == key.runtime && wire.text("epoch") == pageEpoch, "任务连接事件身份不匹配")
                val number = wire.requiredLong("sequence")
                checkData(number > sequence && number <= next, "任务连接事件顺序无效")
                sequence = number
                onWireEvent(wire)
            }
            if (!page.requiredBoolean("hasMore")) return
            checkData(next > after, "任务连接重放未推进")
            after = next; through = upper; epoch = pageEpoch
        }
        throw IOException("任务历史过长，无法在本次恢复产物连接")
    }

    private suspend fun control(key: Key, binding: Binding, action: JsonObject, responseKind: String): JsonObject {
        val id = UUID.randomUUID().toString()
        val command = buildJsonObject {
            put("requestId", id); put("connectionId", binding.connection); put("sessionId", binding.acpSession); put("action", action)
        }
        val response = CompletableDeferred<JsonObject>()
        synchronized(lock) {
            checkData(!closed && bindings[key]?.connection == binding.connection, "任务连接已变化，请刷新产物")
            pending[id] = Pending(key, command, response)
        }
        try {
            val result = withTimeout(requestTimeoutMs) {
                val ack = send(wakuCommand("deerFlowTasks") { put("command", command) }, key.session, key.runtime)
                checkData(ack.text("type") == "ack", "产物请求未被服务器接受")
                response.await()
            }
            checkData(result.text("kind") == responseKind, "产物查询结果类型不匹配")
            return result["data"] as? JsonObject ?: throw IOException("产物查询缺少结果")
        } catch (_: TimeoutCancellationException) {
            throw IOException("产物查询超时，请重新刷新；没有自动重试")
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    private suspend fun download(key: Key, binding: Binding, artifact: Artifact): AgentArtifact {
        val directory = File(cacheDir, "waku-artifacts").canonicalFile
        checkData(directory.toPath().startsWith(cacheDir.canonicalFile.toPath()), "产物缓存目录无效")
        checkData(directory.isDirectory || directory.mkdirs(), "无法创建产物缓存")
        val temporary = File.createTempFile("download-", ".part", directory)
        var offset = 0L
        var expectedHash: String? = null
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(temporary).use { output ->
                do {
                    val chunk = control(key, binding, action("artifactRead") {
                        put("rootId", artifact.root); put("nodeId", artifact.node); put("artifactId", artifact.id)
                        put("offset", offset); put("limit", chunkBytes); put("expectedSha256", expectedHash?.let(::JsonPrimitive) ?: JsonNull)
                    }, "artifact")
                    checkData(chunk.text("rootId") == artifact.root && chunk.text("nodeId") == artifact.node, "产物分块节点不匹配")
                    val metadata = chunk["artifact"] as? JsonObject ?: throw IOException("产物分块缺少元数据")
                    checkData(parseArtifact(artifact.root, artifact.node, metadata) == artifact, "产物在下载期间发生变化")
                    val hash = chunk.requiredText("sha256")
                    checkData(hash.matches(Regex("[a-f0-9]{64}")) && (expectedHash == null || expectedHash == hash), "产物校验标识发生变化")
                    expectedHash = hash
                    checkData(chunk.text("encoding") == "base64" && chunk.requiredLong("offset") == offset, "产物分块编码或位置无效")
                    val encoded = chunk.text("data") ?: throw IOException("产物分块缺少数据")
                    checkData(encoded.length <= ((chunkBytes + 2) / 3) * 4, "产物分块超过大小限制")
                    val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw IOException("产物分块编码无效") }
                    checkData(bytes.size <= chunkBytes && bytes.size.toLong() <= artifact.size - offset, "产物数据超过声明大小")
                    val next = chunk.requiredLong("nextOffset")
                    val eof = chunk.requiredBoolean("eof")
                    checkData(next == offset + bytes.size && next <= artifact.size && (eof == (next == artifact.size)), "产物分块边界无效")
                    checkData(eof || next > offset, "产物下载未推进")
                    // Verify current authority and immutable identity before reusing bytes from cache.
                    if (offset == 0L) {
                        val cached = destination(directory, hash, artifact)
                        if (cached.isFile && cached.length() == artifact.size && cachedHash(cached) == hash) {
                            return result(artifact, cached)
                        }
                    }
                    output.write(bytes); digest.update(bytes); offset = next
                } while (!eof)
                output.fd.sync()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            checkData(offset == artifact.size && hash == expectedHash, "产物完整性校验失败")
            val destination = destination(directory, hash, artifact)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return result(artifact, destination)
        } finally {
            temporary.delete()
        }
    }

    private fun destination(directory: File, hash: String, artifact: Artifact): File {
        val destination = File(directory, "$hash-${safeName(artifact.name)}").canonicalFile
        checkData(destination.parentFile == directory, "产物缓存路径无效")
        return destination
    }

    private fun cachedHash(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            var count = input.read(bytes)
            while (count >= 0) {
                if (count > 0) digest.update(bytes, 0, count)
                count = input.read(bytes)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    } catch (_: IOException) { null }

    private fun result(artifact: Artifact, file: File) = AgentArtifact(
        path = "waku:${artifact.root}/${artifact.node}/${artifact.id}", name = safeName(artifact.name),
        url = file.toURI().toString(), mimeType = artifact.mime, size = artifact.size,
        kind = if (artifact.mime.startsWith("image/")) "image" else "file",
    )

    private fun parseArtifact(root: String, node: String, value: JsonObject): Artifact {
        val size = value.requiredLong("size")
        checkData(size >= 0, "产物大小无效")
        return Artifact(root, node, value.requiredText("id"), value.requiredText("name"), value.requiredText("mimeType"), size)
    }

    private fun nextCursor(page: JsonObject, seen: MutableSet<String>): String? {
        if (!page.requiredBoolean("hasMore")) return null
        val next = page.requiredText("nextCursor")
        checkData(next.length <= 16384 && seen.add(next) && seen.size <= 128, "产物分页未推进或超过上限")
        return next
    }

    private fun failPending(key: Key, message: String) {
        val ids = pending.filterValues { it.key == key }.keys.toList()
        ids.forEach { pending.remove(it)?.response?.completeExceptionally(IOException(message)) }
    }

    private fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isISOControl() || it in "<>:\"/\\|?*") '_' else it }.joinToString("")
        .trim(' ', '.').take(100).ifBlank { "artifact.bin" }

    private fun action(kind: String, body: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("kind", kind); body() }
    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.requiredText(key: String): String = text(key)?.takeIf { it.isNotBlank() && it.length <= 16384 } ?: throw IOException("产物响应缺少 $key")
    private fun JsonObject.requiredLong(key: String): Long = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: throw IOException("产物响应缺少 $key")
    private fun JsonObject.requiredBoolean(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw IOException("产物响应缺少 $key")
    private fun JsonObject.requiredArray(key: String): JsonArray = get(key) as? JsonArray ?: throw IOException("产物响应缺少 $key")
    private fun checkData(value: Boolean, message: String) { if (!value) throw IOException(message) }
}
