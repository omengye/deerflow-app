package com.deerflow.app.data

import android.content.Context
import com.deerflow.app.data.agui.AguiClient
import com.deerflow.app.data.agui.AguiHttpException
import com.deerflow.app.data.agui.UploadFilePart
import com.deerflow.app.data.agui.UploadedFile
import com.deerflow.app.data.agui.AguiJson
import com.deerflow.app.data.settings.SettingsStore
import com.deerflow.app.domain.BlockKind
import com.deerflow.app.domain.ConversationReducer
import com.deerflow.app.domain.ConversationState
import com.deerflow.app.domain.ReplayState
import com.deerflow.app.domain.UserDisplayText
import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.AguiEvent
import com.deerflow.app.domain.model.ChatMessage
import com.deerflow.app.domain.model.ResumeEntry
import com.deerflow.app.domain.model.ThreadMeta
import com.deerflow.app.domain.model.ThreadStore
import com.deerflow.app.domain.model.userMessage
import com.deerflow.app.service.SseForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * App-scoped conversation store. Each thread owns an independent state and run
 * job, while [state] exposes only the currently selected thread to the UI.
 * [SseForegroundService] stays active while any thread is streaming.
 */
class ConversationRepository(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
) {
    private val initialThreadId = newThreadId()
    private var currentThreadId: String = initialThreadId
    private val _state = MutableStateFlow(ConversationState(threadId = initialThreadId))
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val threadsDir = appContext.filesDir.resolve("threads").also { it.mkdirs() }
    private val indexFile = threadsDir.resolve("threads_index.json")
    private val runSessionStore = RunSessionStore(threadsDir.resolve("active_run_sessions.json"))
    private val statesByThread = mutableMapOf(initialThreadId to _state.value)
    private val runJobsByThread = mutableMapOf<String, Job>()
    private val runSessionsByThread = mutableMapOf<String, RunSession>()
    private val activeOperations = java.util.concurrent.atomic.AtomicInteger(0)
    private val deletedThreadIds = mutableSetOf<String>()

    private val _runningThreadIds = MutableStateFlow<Set<String>>(emptySet())
    val runningThreadIds: StateFlow<Set<String>> = _runningThreadIds.asStateFlow()

    private val _threads = MutableStateFlow<List<ThreadMeta>>(emptyList())
    val threads: StateFlow<List<ThreadMeta>> = _threads.asStateFlow()
    val artifactHeaders: StateFlow<Map<String, String>> = settings.flow
        .map { it.headers() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            var recentThreadId: String? = null
            mutex.withLock {
                loadIndex()
                val recent = _threads.value.firstOrNull()
                if (recent != null) {
                    recentThreadId = recent.id
                    val loaded = loadThreadInternal(recent.id)
                    statesByThread[recent.id] = loaded
                    setCurrentThreadLocked(recent.id, loaded)
                }
                recoverPersistedRunsLocked()
            }
            recentThreadId?.let { threadId ->
                val cfg = settings.current()
                syncThreadInfo(threadId, AguiClient(cfg.endpoint, cfg.headers(), cfg.initialState()))
            }
        }
    }

    private fun recoverPersistedRunsLocked() {
        val now = System.currentTimeMillis()
        val sessions = runSessionStore.load()
            .filter { now - it.updatedAtEpochMillis <= RUN_SESSION_METADATA_RETENTION_MS }
            .map { session ->
                session.copy(
                    reconnectUntilEpochMillis = now + STREAM_RECONNECT_WINDOW_MS,
                    updatedAtEpochMillis = now,
                )
            }

        runSessionsByThread.clear()
        sessions.forEach { session ->
            if (session.threadId in deletedThreadIds) return@forEach
            runSessionsByThread[session.threadId] = session
            val loaded = statesByThread[session.threadId]
                ?: loadThreadInternal(session.threadId).also { statesByThread[session.threadId] = it }
            updateThreadStateLocked(session.threadId) {
                loaded.copy(
                    running = true,
                    status = "Restoring agent stream...",
                    replay = ReplayState.from(loaded.history),
                )
            }
            ensureProvisionalThreadMetaLocked(session.threadId)
            launchRunSessionLocked(session, recovering = true)
        }
        saveRunSessionsLocked()
    }

    private fun saveRunSessionsLocked() {
        runSessionStore.save(runSessionsByThread.values)
    }

    private fun loadIndex() {
        if (!indexFile.exists()) {
            _threads.value = emptyList()
            return
        }
        runCatching {
            val content = indexFile.readText()
            _threads.value = AguiJson.decodeFromString(ListSerializer(ThreadMeta.serializer()), content)
                .sortedByDescending { it.lastActive }
        }.onFailure {
            _threads.value = emptyList()
        }
    }

    private fun saveIndex(list: List<ThreadMeta>) {
        runCatching {
            val content = AguiJson.encodeToString(ListSerializer(ThreadMeta.serializer()), list)
            indexFile.writeText(content)
            _threads.value = list.sortedByDescending { it.lastActive }
        }
    }

    private fun loadThreadInternal(threadId: String): ConversationState {
        val file = threadsDir.resolve("thread_$threadId.json")
        if (!file.exists()) {
            return ConversationState(threadId = threadId, status = "Idle")
        }
        if (file.length() > MAX_THREAD_HISTORY_FILE_BYTES) {
            return ConversationState(threadId = threadId, status = "Idle")
                .appendSystem(
                    BlockKind.ERROR,
                    "[HISTORY_SKIPPED]",
                    "This local thread history is too large to load safely. Start a new thread or clear app data.",
                )
        }
        return runCatching {
            val content = file.readText()
            val store = decodeThreadStore(content)
            val rawJson = JsonObject(mapOf("messages" to AguiJson.encodeToJsonElement(ListSerializer(ChatMessage.serializer()), store.messages)))
            val event = AguiEvent(type = "MESSAGES_SNAPSHOT", raw = rawJson)
            val baseState = ConversationState(threadId = threadId, history = store.messages, status = "Idle")
            ConversationReducer.reduce(baseState, event).appendArtifacts(store.artifacts)
        }.getOrElse {
            ConversationState(threadId = threadId, status = "Idle")
        }.let { loaded ->
            if (threadId in runJobsByThread) loaded.copy(running = true, status = "Running") else loaded
        }
    }

    private fun decodeThreadStore(content: String): ThreadStore {
        return runCatching {
            AguiJson.decodeFromString(ThreadStore.serializer(), content)
        }.getOrElse {
            ThreadStore(messages = AguiJson.decodeFromString(ListSerializer(ChatMessage.serializer()), content))
        }
    }

    private fun saveCurrentThread(state: ConversationState) {
        val threadId = state.threadId
        val history = state.history
        if (history.isEmpty() && state.artifacts.isEmpty()) return

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            mutex.withLock {
                if (threadId in deletedThreadIds) return@withLock

                val file = threadsDir.resolve("thread_$threadId.json")
                runCatching {
                    val content = AguiJson.encodeToString(ThreadStore.serializer(), ThreadStore(messages = history, artifacts = state.artifacts))
                    file.writeText(content)

                    ensureProvisionalThreadMetaLocked(threadId)
                }
            }
        }
    }

    fun selectThread(threadId: String) {
        scope.launch {
            mutex.withLock {
                val loaded = statesByThread[threadId] ?: loadThreadInternal(threadId).also { statesByThread[threadId] = it }
                setCurrentThreadLocked(threadId, loaded)

                // Update index timestamp
                val currentMeta = _threads.value
                val existing = currentMeta.find { it.id == threadId }
                if (existing != null) {
                    val updated = currentMeta.filterNot { it.id == threadId } + existing.copy(lastActive = System.currentTimeMillis())
                    saveIndex(updated)
                }
            }
            val cfg = settings.current()
            syncThreadInfo(threadId, AguiClient(cfg.endpoint, cfg.headers(), cfg.initialState()))
        }
    }

    fun deleteThread(threadId: String) {
        scope.launch {
            val jobToJoin = cancelRunJob(threadId, cancelBackend = true)
            jobToJoin?.cancel()
            jobToJoin?.join()
            mutex.withLock {
                updateRunningThreadIdsLocked()
                val wasActive = currentThreadId == threadId

                deletedThreadIds += threadId

                // Delete message file
                val file = threadsDir.resolve("thread_$threadId.json")
                if (file.exists()) file.delete()
                statesByThread.remove(threadId)

                // Remove from index
                val updated = _threads.value.filterNot { it.id == threadId }
                saveIndex(updated)

                // If it was the active thread, switch to another thread or create a new one
                if (wasActive) {
                    val next = updated.firstOrNull()
                    if (next != null) {
                        val loaded = statesByThread[next.id] ?: loadThreadInternal(next.id).also { statesByThread[next.id] = it }
                        setCurrentThreadLocked(next.id, loaded)
                    } else {
                        val freshId = newThreadId()
                        val freshState = ConversationState(threadId = freshId, status = "Idle")
                        statesByThread[freshId] = freshState
                        setCurrentThreadLocked(freshId, freshState)
                    }
                }
                updateForegroundServiceLocked()
            }
        }
    }

    /** Send a user message and start a fresh run, uploading attachments first when present. */
    fun send(text: String, attachments: List<PendingAttachment> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        scope.launch {
            activeOperations.incrementAndGet()
            mutex.withLock { updateForegroundServiceLocked() }
            try {
                val threadId = mutex.withLock { currentThreadId }
                val jobToJoin = cancelRunJob(threadId, cancelBackend = true)
                jobToJoin?.cancel()
                jobToJoin?.join()
                mutex.withLock {
                    updateRunningThreadIdsLocked()
                    updateForegroundServiceLocked()
                }

                var uploadedFiles: List<UploadedFile> = emptyList()
                if (attachments.isNotEmpty()) {
                    mutex.withLock {
                        updateThreadStateLocked(threadId) { it.copy(status = "Uploading ${attachments.size} attachment(s)...") }
                    }

                    val cfg = settings.current()
                    val client = AguiClient(cfg.endpoint, cfg.headers(), cfg.initialState())
                    val parts = attachments.map { it.toUploadFilePart() }
                    val uploadResult = runCatching { client.uploadFiles(threadId, parts) }
                        .getOrElse { error ->
                            mutex.withLock {
                                updateThreadStateLocked(threadId) {
                                    it.copy(running = false, status = "Upload error: ${error.message}")
                                        .appendSystem(BlockKind.ERROR, "[UPLOAD_ERROR]", error.message.orEmpty())
                                }
                            }
                            return@launch
                        }
                    uploadedFiles = uploadResult.files
                }

                mutex.withLock {
                    val state = statesByThread[threadId] ?: ConversationState(threadId = threadId)

                    val displayText = UserDisplayText.clean(buildUserDisplayText(trimmed, uploadedFiles))
                    val promptText = buildAgentPromptText(trimmed, uploadedFiles)
                    val nextState = state.let { s ->
                        s.appendSystem(BlockKind.USER, "You", displayText)
                            .copy(history = s.history + userMessage(promptText))
                    }
                    updateThreadStateLocked(threadId) { nextState }
                    ensureProvisionalThreadMetaLocked(threadId)
                    saveCurrentThread(nextState)
                    startRun(threadId, emptyList())
                }
            } finally {
                activeOperations.decrementAndGet()
                mutex.withLock { updateForegroundServiceLocked() }
            }
        }
    }

    /** Reply to outstanding interrupts (plain text or a JSON resume array). */
    fun resume(text: String) {
        val entries = buildResumeEntries(text) ?: return
        scope.launch {
            activeOperations.incrementAndGet()
            mutex.withLock { updateForegroundServiceLocked() }
            try {
                val threadId = mutex.withLock { currentThreadId }
                val jobToJoin = cancelRunJob(threadId, cancelBackend = true)
                jobToJoin?.cancel()
                jobToJoin?.join()
                mutex.withLock {
                    updateRunningThreadIdsLocked()
                    updateForegroundServiceLocked()
                    updateThreadStateLocked(threadId) { it.copy(interrupts = emptyList()).removeBlock("interrupts") }
                    startRun(threadId, entries)
                }
            } finally {
                activeOperations.decrementAndGet()
                mutex.withLock { updateForegroundServiceLocked() }
            }
        }
    }

    fun cancel() {
        scope.launch {
            val threadId = mutex.withLock { currentThreadId }
            val jobToJoin = cancelRunJob(threadId, cancelBackend = true)
            jobToJoin?.cancel()
            jobToJoin?.join()
            mutex.withLock {
                updateRunningThreadIdsLocked()
                updateThreadStateLocked(threadId) { it.copy(running = false, status = "Idle (cancelled)") }
                updateForegroundServiceLocked()
            }
        }
    }

    fun newThread() {
        scope.launch {
            mutex.withLock {
                val threadId = newThreadId()
                val state = ConversationState(threadId = threadId, status = "Idle")
                statesByThread[threadId] = state
                setCurrentThreadLocked(threadId, state)
            }
        }
    }

    private fun startRun(threadId: String, resume: List<ResumeEntry>) {
        val snapshot = statesByThread[threadId] ?: ConversationState(threadId = threadId)
        val now = System.currentTimeMillis()
        val session = RunSession(
            threadId = threadId,
            runId = AguiClient.newRunId(),
            history = snapshot.history,
            resume = resume,
            reconnectUntilEpochMillis = now + STREAM_RECONNECT_WINDOW_MS,
            updatedAtEpochMillis = now,
        )
        runSessionsByThread[threadId] = session
        saveRunSessionsLocked()
        launchRunSessionLocked(session, recovering = false)
    }

    private fun launchRunSessionLocked(session: RunSession, recovering: Boolean) {
        val threadId = session.threadId
        val runId = session.runId
        val historyToSend = session.history
        val resume = session.resume
        val snapshot = statesByThread[threadId] ?: ConversationState(threadId = threadId)
        updateThreadStateLocked(threadId) {
            snapshot.copy(
                running = true,
                status = if (recovering) "Restoring agent stream..." else "Starting run...",
                replay = ReplayState.from(snapshot.history),
            )
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val myJob = coroutineContext[Job]
            var lastSavedHistorySize = historyToSend.size
            val cfg = settings.current()
            val client = AguiClient(cfg.endpoint, cfg.headers(), cfg.initialState())
            var retryAttempt = 0
            var reconnecting = recovering
            var reconnectDeadline = if (recovering) {
                System.currentTimeMillis() + STREAM_RECONNECT_WINDOW_MS
            } else {
                session.reconnectUntilEpochMillis
            }
            try {
                var sawTerminalEvent = false
                while (true) {
                    val lastEventId = mutex.withLock {
                        runSessionsByThread[threadId]?.lastEventId
                    }
                    val resumeEventId = lastEventId ?: if (recovering || reconnecting || retryAttempt > 0) INITIAL_REPLAY_CURSOR else null
                    try {
                        client.runStream(threadId, runId, historyToSend, resume, resumeEventId)
                            .collect { event ->
                                mutex.withLock {
                                    if (runJobsByThread[threadId] == myJob) {
                                        retryAttempt = 0
                                        reconnecting = false
                                        if (event.type == "RUN_FINISHED" || event.type == "RUN_CANCELLED" || event.type == "RUN_ERROR") {
                                            sawTerminalEvent = true
                                        }
                                        val currentState = statesByThread[threadId] ?: ConversationState(threadId = threadId)
                                        val nextState = ConversationReducer.reduce(currentState, event)
                                        if (nextState !== currentState) {
                                            updateThreadStateLocked(threadId) { nextState }
                                            if (shouldPersistRunEvent(event.type, lastSavedHistorySize, nextState.history.size)) {
                                                lastSavedHistorySize = nextState.history.size
                                                saveCurrentThread(nextState)
                                            }
                                        }
                                        event.sseId?.takeIf { it.isNotBlank() }?.let { sseId ->
                                            val session = runSessionsByThread[threadId]
                                            if (session?.runId == runId) {
                                                val now = System.currentTimeMillis()
                                                runSessionsByThread[threadId] = session.copy(
                                                    lastEventId = sseId,
                                                    reconnectUntilEpochMillis = now + STREAM_RECONNECT_WINDOW_MS,
                                                    updatedAtEpochMillis = now,
                                                )
                                                saveRunSessionsLocked()
                                            }
                                        }
                                    }
                                }
                            }
                        if (!sawTerminalEvent && mutex.withLock { runJobsByThread[threadId] == myJob }) {
                            throw IOException("stream ended before terminal event")
                        }
                        break
                    } catch (e: IOException) {
                        if (e is AguiHttpException && !e.isRetryable) throw e
                        val now = System.currentTimeMillis()
                        if (!reconnecting) {
                            reconnecting = true
                            reconnectDeadline = now + STREAM_RECONNECT_WINDOW_MS
                            mutex.withLock {
                                val current = runSessionsByThread[threadId]
                                if (current?.runId == runId) {
                                    runSessionsByThread[threadId] = current.copy(
                                        reconnectUntilEpochMillis = reconnectDeadline,
                                        updatedAtEpochMillis = now,
                                    )
                                    saveRunSessionsLocked()
                                }
                            }
                        }
                        if (now >= reconnectDeadline) throw e
                        retryAttempt += 1
                        val remainingMillis = reconnectDeadline - now
                        val delayMillis = minOf(reconnectDelayMillis(retryAttempt), remainingMillis)
                        mutex.withLock {
                            if (runJobsByThread[threadId] == myJob) {
                                val remainingSeconds = ((remainingMillis + 999L) / 1000L).coerceAtLeast(1L)
                                updateThreadStateLocked(threadId) {
                                    it.copy(status = "Reconnecting stream... (${remainingSeconds}s remaining)")
                                }
                            }
                        }
                        kotlinx.coroutines.delay(delayMillis)
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                mutex.withLock {
                    if (runJobsByThread[threadId] == myJob) {
                        updateThreadStateLocked(threadId) { state ->
                            if (e is AguiHttpException && e.statusCode == 410) {
                                state.copy(running = false, status = "Replay expired; refresh the thread to load the final result")
                                    .appendSystem(BlockKind.ERROR, "[REPLAY_EXPIRED]", "The saved stream can no longer be replayed.")
                            } else {
                                state.copy(running = false, status = "Stream error: ${e.message}")
                                    .appendSystem(BlockKind.ERROR, "[RUN_ERROR]", e.message.orEmpty())
                            }
                        }
                    }
                }
            } finally {
                var shouldFetch = false
                mutex.withLock {
                    if (runJobsByThread[threadId] == myJob) {
                        runJobsByThread.remove(threadId)
                        runSessionsByThread.remove(threadId)
                        saveRunSessionsLocked()
                        updateRunningThreadIdsLocked()
                        updateThreadStateLocked(threadId) { if (it.running) it.copy(running = false, status = "Idle") else it }
                        val meta = _threads.value.find { it.id == threadId }
                        if (meta == null || !meta.isTitleFetched || meta.title.isBlank()) {
                            shouldFetch = true
                            ensureProvisionalThreadMetaLocked(threadId)
                        }
                    }
                    updateForegroundServiceLocked()
                }
                scope.launch {
                    syncThreadInfoAfterRun(threadId, client, waitForTitle = shouldFetch)
                }
            }
        }
        runJobsByThread[threadId] = job
        updateRunningThreadIdsLocked()
        updateForegroundServiceLocked()
        job.start()
    }

    private suspend fun syncThreadInfoAfterRun(
        threadId: String,
        client: AguiClient,
        waitForTitle: Boolean,
    ) {
        if (!waitForTitle) {
            syncThreadInfo(threadId, client)
            return
        }

        for (attempt in 0 until TITLE_SYNC_ATTEMPTS) {
            val hasTitle = syncThreadInfo(threadId, client, ensureProvisionalTitle = attempt == 0)
            if (hasTitle) return
            if (attempt < TITLE_SYNC_ATTEMPTS - 1) {
                kotlinx.coroutines.delay(TITLE_SYNC_RETRY_DELAY_MS * (attempt + 1))
            }
        }
    }

    private suspend fun syncThreadInfo(
        threadId: String,
        client: AguiClient,
        ensureProvisionalTitle: Boolean = false,
    ): Boolean {
        val info = client.fetchThreadInfo(threadId)
        if (info == null) {
            if (ensureProvisionalTitle) {
                mutex.withLock {
                    if (threadId !in deletedThreadIds) {
                        ensureProvisionalThreadMetaLocked(threadId)
                    }
                }
            }
            return false
        }

        var hasServerTitle = false
        mutex.withLock {
            if (threadId in deletedThreadIds) return@withLock
            if (info.messages.isNotEmpty()) {
                val snapshotEvent = AguiEvent(
                    type = "MESSAGES_SNAPSHOT",
                    raw = JsonObject(mapOf("messages" to JsonArray(info.messages))),
                )
                updateThreadStateLocked(threadId) { current ->
                    ConversationReducer.reduce(current, snapshotEvent)
                }
                statesByThread[threadId]?.let(::saveCurrentThread)
            }
            if (info.artifacts.isNotEmpty()) {
                updateThreadStateLocked(threadId) { it.appendArtifacts(info.artifacts) }
                statesByThread[threadId]?.let(::saveCurrentThread)
            }
            updateThreadStateLocked(threadId) { current ->
                if (!current.running && current.status.startsWith("Replay expired")) {
                    current.copy(status = "Idle")
                } else {
                    current
                }
            }
            val title = info.title?.trim().orEmpty()
            hasServerTitle = title.isNotEmpty()
            if (hasServerTitle) {
                val currentMeta = _threads.value
                val existing = currentMeta.find { it.id == threadId }
                val updated = if (existing != null) {
                    currentMeta.filterNot { it.id == threadId } + existing.copy(
                        title = title,
                        isTitleFetched = true,
                    )
                } else {
                    currentMeta + ThreadMeta(
                        id = threadId,
                        title = title,
                        lastActive = System.currentTimeMillis(),
                        isTitleFetched = true,
                    )
                }
                saveIndex(updated)
            } else if (ensureProvisionalTitle) {
                ensureProvisionalThreadMetaLocked(threadId)
            }
        }
        return hasServerTitle
    }

    private fun ensureProvisionalThreadMetaLocked(threadId: String, nowMillis: Long = System.currentTimeMillis()) {
        if (threadId in deletedThreadIds) return

        val currentMeta = _threads.value
        val existing = currentMeta.find { it.id == threadId }
        val updated = if (existing != null) {
            val hasFetchedTitle = existing.isTitleFetched && existing.title.isNotBlank()
            val title = existing.title.takeIf { it.isNotBlank() } ?: provisionalThreadTitle(nowMillis)
            currentMeta.filterNot { it.id == threadId } + existing.copy(
                title = title,
                lastActive = nowMillis,
                isTitleFetched = hasFetchedTitle,
            )
        } else {
            currentMeta + ThreadMeta(
                id = threadId,
                title = provisionalThreadTitle(nowMillis),
                lastActive = nowMillis,
                isTitleFetched = false,
            )
        }
        saveIndex(updated)
    }

    private fun provisionalThreadTitle(nowMillis: Long): String =
        THREAD_TITLE_FORMATTER.format(Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()))

    private fun setCurrentThreadLocked(threadId: String, state: ConversationState) {
        currentThreadId = threadId
        statesByThread[threadId] = state
        _state.value = state
    }

    private fun updateThreadStateLocked(threadId: String, transform: (ConversationState) -> ConversationState) {
        val current = statesByThread[threadId] ?: ConversationState(threadId = threadId)
        val updated = transform(current)
        statesByThread[threadId] = updated
        if (currentThreadId == threadId) {
            _state.value = updated
        }
    }

    private fun updateRunningThreadIdsLocked() {
        _runningThreadIds.value = runJobsByThread.keys.toSet()
    }

    private suspend fun cancelRunJob(threadId: String, cancelBackend: Boolean): Job? {
        val session = mutex.withLock { runSessionsByThread[threadId] }
        if (cancelBackend && session != null) {
            val cfg = settings.current()
            runCatching { AguiClient(cfg.endpoint, cfg.headers(), cfg.initialState()).cancelRun(session.runId) }
        }
        return mutex.withLock {
            val currentSession = runSessionsByThread[threadId]
            if (session == null || currentSession?.runId == session.runId) {
                runSessionsByThread.remove(threadId)
                val job = runJobsByThread.remove(threadId)
                saveRunSessionsLocked()
                job
            } else {
                null
            }
        }
    }

    private fun updateForegroundServiceLocked() {
        if (runJobsByThread.isEmpty() && activeOperations.get() == 0) {
            SseForegroundService.stop(appContext)
        } else {
            SseForegroundService.start(appContext)
        }
    }


    private fun PendingAttachment.toUploadFilePart(): UploadFilePart {
        val safeName = sanitizeUploadFilename(displayName)
        return UploadFilePart(
            filename = safeName,
            mimeType = mimeType,
            sizeBytes = sizeBytes?.takeIf { it >= 0 },
            openStream = {
                appContext.contentResolver.openInputStream(uri) ?: error("Cannot open attachment: $safeName")
            },
        )
    }

    private fun sanitizeUploadFilename(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\').trim()
        return clean.ifEmpty { "upload-${UUID.randomUUID().toString().take(8)}" }
    }

    private fun buildUserDisplayText(text: String, uploadedFiles: List<UploadedFile>): String {
        if (uploadedFiles.isEmpty()) return text
        val lines = mutableListOf<String>()
        if (text.isNotBlank()) lines += text
        lines += "Attached file(s):"
        uploadedFiles.forEach { file ->
            lines += "- ${file.originalFilename ?: file.filename}"
        }
        return lines.joinToString("\n")
    }

    private fun buildAgentPromptText(text: String, uploadedFiles: List<UploadedFile>): String {
        if (uploadedFiles.isEmpty()) return text
        val lines = mutableListOf<String>()
        lines += if (text.isNotBlank()) text else "Please analyze the uploaded files."
        lines += ""
        lines += "<uploaded_files_from_android>"
        lines += "The user uploaded the following files from the Android client. They are saved under /mnt/user-data/uploads for this thread:"
        uploadedFiles.forEach { file ->
            val displayName = file.originalFilename ?: file.filename
            val virtualPath = file.virtualPath ?: "/mnt/user-data/uploads/${file.filename}"
            lines += "- $displayName: $virtualPath"
            file.markdownVirtualPath?.let { mdPath ->
                lines += "  Markdown conversion: $mdPath"
            }
        }
        lines += "Use read_file, grep, glob, or view_image to inspect these files before answering."
        lines += "</uploaded_files_from_android>"
        return lines.joinToString("\n")
    }

    private fun shouldPersistRunEvent(eventType: String, lastSavedHistorySize: Int, currentHistorySize: Int): Boolean {
        return when (eventType.trim()) {
            "TEXT_MESSAGE_END",
            "TOOL_CALL_RESULT",
            "RUN_FINISHED",
            "RUN_CANCELLED",
            "RUN_ERROR" -> currentHistorySize != lastSavedHistorySize
            "CUSTOM" -> true
            "TOOL_CALL_END" -> true
            else -> false
        }
    }

    private fun buildResumeEntries(text: String): List<ResumeEntry>? {
        val trimmed = text.trim()
        val interrupts = _state.value.interrupts
        return if (trimmed.startsWith("[")) {
            // Caller supplied an explicit resume array.
            runCatching {
                Json.decodeFromString(ListSerializer(ResumeEntry.serializer()), trimmed)
            }.getOrNull()
        } else {
            // Plain text resolves every open interrupt with the same payload.
            interrupts.map { ResumeEntry(it.id, "resolved", JsonPrimitive(trimmed)) }
        }
    }

    companion object {
        @Volatile private var instance: ConversationRepository? = null

        fun get(context: Context): ConversationRepository =
            instance ?: synchronized(this) {
                instance ?: ConversationRepository(
                    appContext = context.applicationContext,
                    settings = SettingsStore(context.applicationContext),
                ).also { instance = it }
            }

        private const val MAX_THREAD_HISTORY_FILE_BYTES = 1024 * 1024L
        private const val STREAM_RECONNECT_WINDOW_MS = 10 * 60 * 1000L
        private const val RUN_SESSION_METADATA_RETENTION_MS = 60 * 60 * 1000L
        private const val INITIAL_REPLAY_CURSOR = "0-0"
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val TITLE_SYNC_ATTEMPTS = 4
        private const val TITLE_SYNC_RETRY_DELAY_MS = 1000L
        private val THREAD_TITLE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private fun newThreadId(): String = "thread-${UUID.randomUUID().toString().replace("-", "")}"

        private fun reconnectDelayMillis(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, 5)
            return (INITIAL_RECONNECT_DELAY_MS shl shift).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }
    }
}
