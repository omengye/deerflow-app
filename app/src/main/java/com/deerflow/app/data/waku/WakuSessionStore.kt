package com.deerflow.app.data.waku

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Profile-isolated storage of projection, replay position and uncertain commands in one atomic file. */
class WakuSessionStore(root: File, endpoint: String, token: String) {
    private val directory = File(root, "waku-${profileNamespace(endpoint, token)}")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(sessionId: String): WakuSnapshot? {
        val file = sessionFile(sessionId)
        val snapshot = readRecoverable(file) ?: return null
        if (snapshot.sessionId() != sessionId) throw IOException("Waku snapshot session identity does not match its file")
        return snapshot
    }

    @Synchronized
    fun list(): List<WakuSnapshot> {
        if (!directory.exists()) return emptyList()
        val names = directory.listFiles().orEmpty().mapNotNull {
            when {
                it.name.endsWith(".json") -> it.name
                it.name.endsWith(".json.bak") -> it.name.removeSuffix(".bak")
                else -> null
            }
        }.distinct()
        return names.mapNotNull { readRecoverable(File(directory, it)) }.sortedByDescending {
            (it.session["updated_at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
        }
    }

    @Synchronized
    fun save(snapshot: WakuSnapshot) {
        val sessionId = snapshot.sessionId()
        require(sessionId.isNotBlank()) { "A Waku snapshot must have a session id" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create Waku session storage")
        val bytes = json.encodeToString(WakuSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw IOException("Waku snapshot exceeds the local storage limit")
        val file = sessionFile(sessionId)
        val temporary = File.createTempFile("${file.name}.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                replaceWithBackup(temporary, file)
            }
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun delete(sessionId: String) {
        val file = sessionFile(sessionId)
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(backup(file).toPath())
    }

    private fun readRecoverable(file: File): WakuSnapshot? {
        val candidates = listOf(file, backup(file)).filter(File::exists)
        if (candidates.isEmpty()) return null
        var failure: Exception? = null
        for (candidate in candidates) {
            try {
                if (candidate.length() > MAX_FILE_BYTES) throw IOException("Waku snapshot exceeds the local storage limit")
                return json.decodeFromString(WakuSnapshot.serializer(), candidate.readText(Charsets.UTF_8))
            } catch (error: Exception) { failure = error }
        }
        // Surface corruption. Returning empty state would allow a replay cursor or uncertain command to disappear silently.
        throw IOException("Could not recover Waku session snapshot", failure)
    }

    private fun replaceWithBackup(temporary: File, file: File) {
        val backup = backup(file)
        if (file.exists()) Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: IOException) {
            if (!file.exists() && backup.exists()) {
                try { Files.move(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                catch (restore: IOException) { failure.addSuppressed(restore) }
            }
            throw failure
        }
        Files.deleteIfExists(backup.toPath())
    }

    private fun sessionFile(id: String) = File(directory, "${sha256(id)}.json")
    private fun backup(file: File) = File(file.parentFile, "${file.name}.bak")
    private fun WakuSnapshot.sessionId(): String = (session["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024L
        fun profileNamespace(endpoint: String, token: String): String = sha256(endpoint.trim().trimEnd('/') + "\u0000" + token)
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}