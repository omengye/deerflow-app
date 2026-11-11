package com.deerflow.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.deerflow.app.data.background.BackgroundConnectionPolicy
import com.deerflow.app.data.background.BackgroundConnectionService
import com.deerflow.app.data.background.BackgroundWork
import com.deerflow.app.data.background.TaskNotifications
import java.util.concurrent.atomic.AtomicBoolean
import com.deerflow.app.data.agui.AguiJson
import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.data.settings.SettingsStore
import com.deerflow.app.data.waku.*
import com.deerflow.app.data.oss.S3Storage
import com.deerflow.app.data.oss.OssAttachmentEnvelope
import com.deerflow.app.domain.BlockKind
import com.deerflow.app.domain.ConversationReducer
import com.deerflow.app.domain.ConversationState
import com.deerflow.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID

/** App-server client. Connections are disposable; execution and native history belong to the daemon. */
class ConversationRepository(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val network = NetworkMonitor(appContext)
    private val retention = BackgroundConnectionPolicy(scope)
    // elapsedRealtime includes deep sleep, so a socket from before Doze is not treated as fresh.
    private val connectionSupervisor = WakuConnectionSupervisor(clockMillis = android.os.SystemClock::elapsedRealtime)
    private val serviceRequested = AtomicBoolean(false)
    private val outgoingOperations = MutableStateFlow(0)
    private val backgroundSummary = MutableStateFlow(BackgroundWork())
    private val taskNotifications by lazy { TaskNotifications(appContext) }
    val backgroundConnection = retention.state
    val backgroundWork = combine(backgroundSummary, outgoingOperations) { work, count -> work.copy(transmitting = count) }
    private val refreshSignal = MutableStateFlow(0L)
    private val forceHydration = AtomicBoolean(false)
    private val mutex = Mutex()
    private val operations = Mutex()
    private val initialId = UUID.randomUUID().toString()
    private val _state = MutableStateFlow(ConversationState(initialId))
    private val _threads = MutableStateFlow<List<ThreadMeta>>(emptyList())
    private val _runningThreadIds = MutableStateFlow<Set<String>>(emptySet())
    private val _connection = MutableStateFlow("请配置 Waku 服务器")
    private val _isConnected = MutableStateFlow(false)
    private val _canSend = MutableStateFlow(false)
    private val _profileKey = MutableStateFlow("")
    private val _pendingPermissions = MutableStateFlow<List<JsonObject>>(emptyList())
    private val _pendingQuestions = MutableStateFlow<List<JsonObject>>(emptyList())
    private val _submissionStatus = MutableStateFlow(WakuSubmissionStatus())
    private val _taskFiles = MutableStateFlow(TaskFilesState())
    val taskFiles = _taskFiles.asStateFlow()
    val state = _state.asStateFlow()
    val threads = _threads.asStateFlow()
    val runningThreadIds = _runningThreadIds.asStateFlow()
    val connection = _connection.asStateFlow()
    val isConnected = _isConnected.asStateFlow()
    val canSend = _canSend.asStateFlow()
    val profileKey = _profileKey.asStateFlow()
    val pendingPermissions = _pendingPermissions.asStateFlow()
    val pendingQuestions = _pendingQuestions.asStateFlow()
    val submissionStatus = _submissionStatus.asStateFlow()
    val artifactHeaders: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

    private class Profile(val key: String, var config: AppSettings, val store: WakuSessionStore) {
        var selected = ""
        val snapshots = mutableMapOf<String, WakuSnapshot>()
        val catalog = mutableMapOf<String, JsonObject>()
        val projects = mutableMapOf<String, JsonObject>()
        val remoteIds = mutableSetOf<String>()
        val drafts = mutableSetOf<String>()
        val ready = mutableSetOf<String>()
        val syncing = mutableSetOf<String>()
        val syncStatus = mutableMapOf<String, String>()
        val syncErrors = mutableMapOf<String, String>()
        val blocked = mutableMapOf<String, String>()
        val artifacts = mutableMapOf<String, List<AgentArtifact>>()
        val artifactErrors = mutableMapOf<String, String>()
        val localDirty = mutableSetOf<String>()
        val serverDirty = mutableSetOf<String>()
    }
    private class Runtime(val id: String) {
        var catchingUp = true
        var epoch: String? = null
        val buffered = mutableListOf<JsonObject>()
        var bytes = 0
    }
    private class Link(val profile: Profile, val client: WakuClient) {
        var forceHydration = false
        var reconnectImmediately = false
        val recovery = WakuSessionRecovery()
        val ended = CompletableDeferred<Throwable>()
        val runtimes = mutableMapOf<String, Runtime>()
        val checkpoint = Channel<Unit>(Channel.CONFLATED)
        val titleRefresh = Channel<Unit>(Channel.CONFLATED)
        val catalogChanges = Channel<Unit>(Channel.CONFLATED)
        val titleChecked = mutableMapOf<String, JsonObject>()
        val catalogRefresh = Mutex()
        val artifactJobs = mutableMapOf<String, Job>()
        lateinit var artifacts: WakuTaskArtifacts
        lateinit var toolApproval: WakuToolApproval
    }
    @Volatile private var active: Link? = null
    @Volatile private var profile: Profile? = null
    private val profiles = mutableMapOf<String, Profile>()
    private data class Selection(val profile: Profile, val id: String)
    @Volatile private var selection: Selection? = null
    private var legacyIndex: List<ThreadMeta> = emptyList()
    private val legacyStates = mutableMapOf<String, ConversationState>()

    init {
        scope.launch {
            legacyIndex = readLegacyIndex()
            connectionRequests(settings.flow, retention.keepConnected, network.state, refreshSignal).collectLatest { request ->
                val (cfg, keepConnected, networkState) = request
                try {
                    val current = activateProfile(cfg)
                    if (!cfg.isConfigured) {
                        setConnection(current, if (cfg.needsReconfiguration) "旧接口配置需要重新设置为 Waku /v1" else cfg.validationError ?: "请配置连接", false)
                    } else if (!keepConnected) setConnection(current, "后台接收已暂停，返回应用后自动恢复；服务器任务继续运行", false)
                    else if (!networkState.available) setConnection(current, "网络不可用，等待恢复", false)
                    else connectLoop(current)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    _connection.value = error.message ?: "无法恢复本地会话"
                    _isConnected.value = false
                    _canSend.value = false
                }
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            combine(backgroundWork, retention.state) { work, state -> work to state }.collect { (work, state) ->
                if (work.hasWork && state.visible && state.pauseReason == null && !state.serviceActive) {
                    ensureBackgroundService()
                }
            }
        }
        scope.launch {
            network.state.map { it.retryRevision }.distinctUntilChanged().drop(1).collect {
                connectionSupervisor.networkRecovered()
            }
        }
    }

    fun setForeground(value: Boolean) {
        val returning = value && !retention.state.value.visible
        retention.setVisible(value)
        if (value && (backgroundSummary.value.hasWork || outgoingOperations.value > 0)) ensureBackgroundService()
        if (returning) connectionSupervisor.foreground()
    }

    private fun ensureBackgroundService() {
        val state = retention.state.value
        if (!state.visible || state.pauseReason != null || state.serviceActive || !serviceRequested.compareAndSet(false, true)) return
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, BackgroundConnectionService::class.java))
        } catch (_: RuntimeException) {
            serviceRequested.set(false)
            retention.pause("系统暂不允许后台接收，返回应用后会恢复")
        }
    }

    fun backgroundServiceStarted() {
        retention.serviceStarted()
        serviceRequested.set(false)
    }
    fun backgroundServiceStopped() {
        serviceRequested.set(false)
        retention.serviceStopped()
    }
    fun pauseBackgroundConnection(reason: String) { retention.pause(reason) }

    fun refresh() { forceHydration.set(true); refreshSignal.update { it + 1 } }

    private suspend fun activateProfile(cfg: AppSettings): Profile = mutex.withLock {
        val key = WakuSessionStore.profileNamespace(cfg.endpoint, cfg.token)
        if (profile?.key == key) return@withLock profile!!.also { it.config = cfg; publish(it) }
        val next = profiles[key] ?: Profile(key, cfg, WakuSessionStore(File(appContext.filesDir, "waku-sessions"), cfg.endpoint, cfg.token)).also { p ->
            p.store.list().forEach { stored ->
                val recovered = stored.copy(pendingCommands = stored.pendingCommands.map { command ->
                    when (command.s("state")) {
                        "prepared" -> command.patch("state" to JsonPrimitive("notSent"))
                        "sending" -> command.patch("state" to JsonPrimitive("unknown"))
                        else -> command
                    }
                })
                val id = stored.session.s("id") ?: return@forEach
                p.snapshots[id] = recovered
                p.artifacts[id] = recovered.artifactFiles
            }
            p.selected = p.snapshots.values.maxByOrNull { it.session.n("updated_at") }?.session?.s("id") ?: initialId
            profiles[key] = p
        }
        next.config = cfg
        profile = next
        _profileKey.value = key
        _isConnected.value = false
        if (next.selected !in next.snapshots) createDraft(next, next.selected)
        publish(next)
        next
    }

    private suspend fun connectLoop(current: Profile) {
        var attempt = 0
        connectionSupervisor.resetFailures()
        while (currentCoroutineContext().isActive && profile === current) {
            setConnection(current, if (attempt == 0) "正在连接服务器…" else "连接中断，正在重新同步…", false)
            val link = Link(current, WakuClient(current.config.endpoint, current.config.token, UUID.randomUUID().toString(), incomingCapacity = 512,
                clockMillis = android.os.SystemClock::elapsedRealtime,
                diagnostics = { event -> android.util.Log.i("WakuConnection", "stage=${event.stage} elapsedMs=${event.elapsedMillis} failure=${event.failure} transport=${event.transport}") },
            ))
            link.forceHydration = forceHydration.getAndSet(false)
            link.artifacts = WakuTaskArtifacts(File(appContext.cacheDir, "waku-${current.key}"),
                readCheckpoint = { id, runtime -> mutex.withLock {
                    ensureCurrent(link)
                    current.snapshots[id]?.let { snapshot -> snapshot.artifactReplay?.takeIf {
                        it.position?.runtimeId == runtime && it.position.epoch == snapshot.replayPosition()?.epoch
                    } }
                } },
                saveCheckpoint = { id, checkpoint -> mutex.withLock {
                    ensureCurrent(link)
                    val snapshot = current.snapshots.getValue(id)
                    if (snapshot.replayPosition()?.epoch == checkpoint.position?.epoch) {
                        val ready = newestReady(snapshot.taskReady, checkpoint.taskReady)
                        putSnapshot(current, id, snapshot.copy(artifactReplay = checkpoint, taskReady = ready), false)
                        flushLocal(current)
                    }
                } },
                beforeRead = { id ->
                    outgoingOperations.first { it == 0 }
                    mutex.withLock {
                        ensureCurrent(link)
                        if (current.selected != id || id in current.syncing || outgoingOperations.value > 0 ||
                            current.snapshots[id]?.pendingCommands?.isNotEmpty() == true || current.snapshots[id]?.pendingHistory != null ||
                            current.snapshots[id]?.session?.s("status") in BUSY) {
                            throw IOException("会话忙碌或正在同步，产物读取暂时暂停，请稍后刷新")
                        }
                    }
                },
            ) { command, session, runtime ->
                ensureCurrent(link)
                link.client.request(command, session, runtime)
            }
            link.toolApproval = WakuToolApproval { command, session, runtime ->
                ensureCurrent(link)
                link.client.request(command, session, runtime)
            }
            active = link
            val failure = try {
                coroutineScope {
                    val foreground = launch {
                        connectionSupervisor.monitorForeground(
                            health = { link.client.health },
                            probe = { timeout ->
                                val response = link.client.request(wakuCommand("getSettings"), timeoutMs = timeout)
                                require(response.s("type") == "settings")
                            },
                            reconnect = {
                                if (active === link && retention.state.value.visible) {
                                    link.reconnectImmediately = true
                                    link.ended.complete(IOException("连接已失去响应，正在重新连接"))
                                    link.client.close()
                                }
                            },
                        )
                    }
                    try { runConnection(link) } finally { foreground.cancelAndJoin() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { error }
            finally {
                link.toolApproval.disconnected()
                link.artifacts.disconnected()
                link.client.close()
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (profile === current) flushLocal(current)
                        if (active === link) {
                            active = null; current.ready.clear(); current.syncing.clear(); current.syncStatus.clear()
                            _isConnected.value = false; publish(current)
                        }
                    }
                }
            }
            if (failure is WakuConnectionException && !failure.retryable) {
                setConnection(current, failure.message ?: "连接需要重新配置", false)
                return
            }
            setConnection(current, "${failure.message ?: "连接中断"}；将自动重连，不重发操作", false)
            attempt++
            connectionSupervisor.awaitRetry(link.client.health.connectedForMillis, immediate = link.reconnectImmediately)
        }
    }

    private suspend fun runConnection(link: Link): Throwable = coroutineScope {
        link.client.connect()
        ensureCurrent(link)
        setConnection(link.profile, "已连接，正在同步会话…", true)
        val receive = launch {
            try {
                for (wire in link.client.incoming) {
                    ensureCurrent(link)
                    when (wire.s("type")) {
                        "event" -> receiveEvent(link, wire)
                        "taskStateChanged" -> link.catalogChanges.trySend(Unit)
                        "shuttingDown" -> link.ended.complete(IOException("服务器正在关闭"))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { link.ended.complete(error); link.client.close() }
        }
        val watch = launch {
            val closed = link.client.state.filterIsInstance<WakuConnectionState.Disconnected>().first()
            setConnection(link.profile, closed.failure?.message ?: "连接中断，正在恢复", false)
            link.ended.complete(closed.failure ?: IOException("连接中断"))
        }
        val localWriter = launch {
            while (isActive) { delay(250); mutex.withLock { ensureCurrent(link); flushLocal(link.profile) } }
        }
        val titleReader = launch {
            for (signal in link.titleRefresh) {
                try { refreshTitles(link) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Optional metadata can retry on the next catalog refresh. */ }
            }
        }
        val catalogReader = launch {
            for (signal in link.catalogChanges) {
                try {
                    for (id in loadCatalog(link)) {
                        try {
                            // Join an in-flight recovery first, then check whether its snapshot
                            // predates the history change that invalidated this subscription.
                            do {
                                hydrateAttach(link, id, forceHydrate = true)
                            } while (mutex.withLock {
                                ensureCurrent(link)
                                WakuRecovery.needsHistoryRefresh(link.profile.snapshots[id]?.session, link.profile.catalog[id])
                            })
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { mutex.withLock {
                            ensureCurrent(link)
                            link.profile.syncErrors[id] = "历史同步未完成：${error.message ?: "请重新同步"}"
                            publish(link.profile)
                        } }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The next catalog notification or refresh retries this read. */ }
            }
        }
        val serverWriter = launch {
            while (isActive) {
                withTimeoutOrNull(2_000) { link.checkpoint.receive() }
                try { checkpoint(link) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { setConnection(link.profile, "会话正在本地保存；服务器同步失败：${error.message}", true) }
            }
        }
        val recoveryJobs = mutableListOf<Job>()
        try {
            loadCatalog(link)
            val toRestore = mutex.withLock {
                ensureCurrent(link)
                val p = link.profile
                (listOf(p.selected) + p.catalog.filterValues { it.s("status") in BUSY }.keys).distinct().filterNot { it.startsWith("legacy:") }
            }
            // Each session unlocks independently. Failure in another task cannot block this chat.
            for (id in toRestore) recoveryJobs += launch {
                var retry = 0
                while (isActive && active === link) {
                    try { hydrateAttach(link, id, forceHydrate = link.forceHydration && retry == 0); break }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        val recoveredElsewhere = mutex.withLock {
                            ensureCurrent(link)
                            if (id in link.profile.ready && id !in link.profile.syncing) return@withLock true
                            link.profile.syncErrors[id] = "同步未完成：${error.message ?: "连接暂时不可用"}；将从已保存进度重试"
                            publish(link.profile)
                            false
                        }
                        if (recoveredElsewhere) break
                        delay(WakuRecovery.retryDelayMillis(retry++))
                    }
                }
            }
            setConnection(link.profile, "已连接", true)
            link.ended.await()
        } finally {
            recoveryJobs.forEach { it.cancel() }
            receive.cancel(); watch.cancel(); localWriter.cancel(); serverWriter.cancel(); titleReader.cancel(); catalogReader.cancel()
        }
    }

    private suspend fun loadCatalog(link: Link) = link.catalogRefresh.withLock {
        val result = link.client.request(wakuCommand("loadTaskState"))
        require(result.s("type") == "taskState") { "服务器返回的会话列表无效" }
        val changedHistory = mutex.withLock {
            ensureCurrent(link)
            val p = link.profile
            p.projects.putAll(result.objs("projects").associateBy { it.s("id").orEmpty() })
            p.catalog.clear()
            p.catalog.putAll(result.objs("sessions").filter { it.s("provider") == "deerFlow" }.associateBy { it.s("id").orEmpty() })
            p.remoteIds.clear(); p.remoteIds += p.catalog.keys
            // Only copy title metadata: catalog rows deliberately contain empty transcripts/cursors.
            val changed = mutableListOf<String>()
            for ((id, summary) in p.catalog) {
                val snapshot = p.snapshots[id] ?: continue
                if (WakuRecovery.needsHistoryRefresh(snapshot.session, summary)) {
                    p.ready -= id; p.serverDirty -= id
                    p.syncErrors[id] = "会话历史已更新，正在重新同步…"
                    link.artifactJobs[id]?.cancel()
                    p.artifacts.remove(id); p.artifactErrors.remove(id)
                    if (id == p.selected || id in link.runtimes) changed += id
                    continue
                }
                var merged = WakuSessionTitles.mergeMetadata(snapshot.session, summary)
                if (id in p.serverDirty) {
                    snapshot.session["title"]?.takeIf { it is JsonPrimitive && it.content.isNotBlank() }?.let {
                        merged = merged.patch("title" to it)
                    }
                }
                if (merged != snapshot.session) putSnapshot(p, id, snapshot.copy(session = merged), false)
            }
            for (id in p.remoteIds) p.drafts.remove(id)
            for (id in p.snapshots.keys - p.remoteIds) {
                val snapshot = p.snapshots[id] ?: continue
                if (snapshot.remoteKnown) {
                    p.drafts -= id
                    p.blocked[id] = "服务器中已没有此会话，本地历史只读；请新建会话。"
                } else if (snapshot.session.objs("messages").isEmpty() && snapshot.cursor == null) p.drafts += id
                else p.blocked[id] = "本地历史尚未在服务器中找到对应会话，请核对服务器配置。"
            }
            publish(p)
            changed
        }
        link.titleRefresh.trySend(Unit)
        changedHistory
    }

    /** Read missing title history without attaching a runtime or submitting provider work. */
    private suspend fun refreshTitles(link: Link) {
        while (currentCoroutineContext().isActive) {
            val candidate = mutex.withLock {
                ensureCurrent(link)
                val p = link.profile
                p.catalog.entries.firstOrNull { (id, summary) ->
                    link.titleChecked[id] != summary &&
                        WakuSessionTitles.needsDetails(p.snapshots[id]?.session, summary)
                }?.let { it.key to it.value }
            } ?: return
            val (id, summary) = candidate
            val response = link.client.request(wakuCommand("hydrateSession") { put("sessionId", id) })
            val remote = response["session"] as? JsonObject
            mutex.withLock {
                ensureCurrent(link)
                val p = link.profile
                if (p.catalog[id] == summary) {
                    link.titleChecked[id] = summary
                    if (remote?.s("id") == id) {
                        val current = p.snapshots[id]
                        val next = if (current == null) WakuProjection.fromSession(remote).copy(remoteKnown = true)
                        else {
                            var merged = WakuSessionTitles.mergeMetadata(current.session, remote, fullSnapshot = true)
                            if (id in p.serverDirty) {
                                current.session["title"]?.takeIf { it is JsonPrimitive && it.content.isNotBlank() }?.let {
                                    merged = merged.patch("title" to it)
                                }
                            }
                            current.copy(session = merged)
                        }
                        if (next != current) putSnapshot(p, id, next, false)
                        publish(p)
                    }
                }
            }
        }
    }

    private suspend fun hydrateAttach(link: Link, id: String, forceHydrate: Boolean = false) = link.recovery.run(id) {
        hydrateAttachOnce(link, id, forceHydrate)
    }

    private suspend fun hydrateAttachOnce(link: Link, id: String, forceHydrate: Boolean) {
        val (shouldHydrate, acceptedPromptIds) = mutex.withLock {
            ensureCurrent(link)
            link.artifactJobs[id]?.cancel()
            link.runtimes[id]?.catchingUp = true
            link.profile.syncErrors.remove(id)
            link.profile.syncStatus[id] = "正在校验会话…"
            link.profile.syncing += id; link.profile.ready -= id; publish(link.profile)
            (id in link.profile.remoteIds) to link.profile.snapshots[id]?.pendingCommands.orEmpty()
                .filter { it.s("kind") == "prompt" && it.s("state") == "accepted" }
                .mapNotNull { it.s("requestId") }.toSet()
        }
        try {
            if (!shouldHydrate) {
                mutex.withLock {
                    ensureCurrent(link)
                    link.profile.ready += id
                    if (link.profile.snapshots[id]?.remoteKnown != true) link.profile.drafts += id
                }
                return
            }
            var attached = WakuRuntimeAttachment.parse(link.client.request(wakuCommand("attachSession"), id))
            val needsHydration = mutex.withLock {
                ensureCurrent(link)
                val cached = link.profile.snapshots[id]
                forceHydrate || attached.runtimeId == null || cached?.replayPosition()?.runtimeId != attached.runtimeId ||
                    WakuRecovery.needsHistoryRefresh(cached?.session, link.profile.catalog[id])
            }
            if (needsHydration) {
                hydrateSnapshot(link, id)
                // Hydration can cross a desktop rewind. Re-read runtime identity instead of
                // attaching the old journal to the newly truncated conversation.
                attached = WakuRuntimeAttachment.parse(link.client.request(wakuCommand("attachSession"), id))
            }
            val runtimeId = attached.runtimeId
            mutex.withLock {
                ensureCurrent(link)
                val p = link.profile
                putSnapshot(p, id, attached.applyTo(p.snapshots.getValue(id)), false)
            }
            link.artifacts.onAttached(id, attached)
            if (runtimeId == null) recoverNativeHistory(link, id)
            else {
                mutex.withLock {
                    ensureCurrent(link)
                    val p = link.profile
                    p.blocked.remove(id)
                    putSnapshot(p, id, WakuProjection.bindRuntime(p.snapshots.getValue(id), runtimeId), serverDirty = false)
                    val existing = link.runtimes[id]?.takeIf { it.id == runtimeId }
                    link.runtimes[id] = existing?.apply { catchingUp = true } ?: Runtime(runtimeId)
                }
                try { catchUp(link, id, runtimeId) }
                catch (changed: ReplayEpochChanged) {
                    hydrateSnapshot(link, id, changed.actualEpoch)
                    mutex.withLock {
                        ensureCurrent(link)
                        val p = link.profile
                        putSnapshot(p, id, WakuProjection.bindRuntime(p.snapshots.getValue(id), runtimeId), false)
                        val existing = link.runtimes[id]?.takeIf { it.id == runtimeId }
                        link.runtimes[id] = existing?.apply {
                            catchingUp = true
                            buffered.clear()
                            bytes = 0
                        } ?: Runtime(runtimeId)
                    }
                    catchUp(link, id, runtimeId)
                }
            }
            mutex.withLock {
                ensureCurrent(link)
                require(!WakuRecovery.needsHistoryRefresh(link.profile.snapshots[id]?.session, link.profile.catalog[id])) {
                    "会话历史已更新，请重新同步"
                }
                val snapshot = link.profile.snapshots.getValue(id)
                val recovered = WakuRecovery.resolveRecoveredCommands(snapshot, acceptedPromptIds)
                if (recovered != snapshot) {
                    putSnapshot(link.profile, id, recovered, false)
                    flushLocal(link.profile)
                }
                link.profile.ready += id; link.profile.syncErrors.remove(id)
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { if (active === link) {
                    link.profile.syncing -= id; link.profile.syncStatus.remove(id); publish(link.profile)
                    if (id in link.profile.ready && link.profile.snapshots[id]?.session?.s("status") !in BUSY) {
                        link.runtimes[id]?.let { scheduleArtifacts(link, id, it.id) }
                    } else if (id !in link.profile.ready) {
                        link.runtimes.remove(id)
                    }
                } }
            }
        }
    }

    private suspend fun hydrateSnapshot(link: Link, id: String, epoch: String? = null) {
        val remote = link.client.request(wakuCommand("hydrateSession") { put("sessionId", id) })["session"] as? JsonObject
            ?: error("服务器会话已不存在")
        mutex.withLock {
            ensureCurrent(link)
            val p = link.profile
            val previous = p.snapshots[id]
            val dirtyTitle = previous?.takeIf { id in p.serverDirty && !WakuRecovery.needsHistoryRefresh(it.session, remote) }
                ?.session?.get("title")?.takeIf { it is JsonPrimitive && it.content.isNotBlank() }
            var snapshot = if (epoch == null) WakuRecovery.chooseSnapshot(previous, remote)
                else WakuProjection.fromSession(remote).copy(pendingCommands = previous?.pendingCommands.orEmpty(),
                    sentMessages = previous?.sentMessages.orEmpty(),
                    artifactFiles = previous?.takeIf { WakuRecovery.historyRevision(it.session) == WakuRecovery.historyRevision(remote) }?.artifactFiles.orEmpty())
            require(remote.s("id") == id && !WakuRecovery.needsHistoryRefresh(remote, p.catalog[id])) {
                "服务器会话历史已变化，请重新同步"
            }
            if (epoch != null && snapshot.replayPosition()?.epoch != epoch) {
                snapshot = snapshot.copy(cursor = null, session = snapshot.session.patch("runtime_event_cursor" to JsonNull))
            }
            var resolved = WakuRecovery.resolveObservedCommands(snapshot).copy(remoteKnown = true)
            if (dirtyTitle != null) resolved = resolved.copy(session = resolved.session.patch("title" to dirtyTitle))
            putSnapshot(p, id, resolved, dirtyTitle != null)
        }
    }

    private suspend fun recoverNativeHistory(link: Link, id: String) {
        val snapshot = mutex.withLock { ensureCurrent(link); link.profile.snapshots.getValue(id) }
        val wasBusy = snapshot.session.s("status") in BUSY
        val cursor = snapshot.session["provider_cursor"] as? JsonObject
        var restored = snapshot.copy(permissions = emptyList(), userInputs = emptyList(), runtimeId = null, cursor = null,
            session = snapshot.session.patch("runtime_event_cursor" to JsonNull),
            pendingCommands = snapshot.pendingCommands.map {
                if (it.s("kind") != "prompt" && it.s("state") == "accepted") it.patch("state" to JsonPrimitive("unknown")) else it
            })
        mutex.withLock {
            ensureCurrent(link)
            link.runtimes.remove(id)
            if (wasBusy) link.profile.blocked[id] = "运行时已不可用，正在读取原生历史；不会自动继续执行。"
            putSnapshot(link.profile, id, restored, false)
            publish(link.profile)
        }
        if (cursor != null && snapshot.session.arr("messages").isEmpty() && snapshot.session.arr("transcript_blocks").isEmpty()) {
            val cwd = mutex.withLock { cwd(link.profile, snapshot.session) }
            val result = link.client.request(wakuCommand("loadProviderSession") { put("cursor", cursor); put("cwd", cwd) })
            val history = result["history"] as? JsonObject ?: error("无法读取原生会话历史")
            // Native imports are bounded, text-only and mint fresh message IDs. They cannot replace
            // an existing hydrated transcript or be merged by comparing message text.
            if (snapshot.session.arr("messages").isEmpty() && snapshot.session.arr("transcript_blocks").isEmpty()) {
                restored = WakuProjection.fromSession(snapshot.session.patch(
                    "messages" to history.arr("messages"), "turns" to history.arr("turns"),
                    "runtime_event_cursor" to JsonNull,
                )).copy(pendingCommands = restored.pendingCommands, remoteKnown = snapshot.remoteKnown,
                    sentMessages = restored.sentMessages, artifactFiles = restored.artifactFiles)
            }
        }
        mutex.withLock {
            ensureCurrent(link)
            link.runtimes.remove(id)
            if (wasBusy) link.profile.blocked[id] = "运行时已不可用。已读取原生历史；请在桌面核对或恢复执行，手机不会自动继续。"
            else link.profile.blocked.remove(id)
            if (id in link.profile.artifactErrors) {
                val retained = if (link.profile.artifacts[id].isNullOrEmpty()) "" else "已下载的文件仍可使用。"
                link.profile.artifactErrors[id] = "聊天历史已恢复，但服务器当前没有此会话的文件读取连接，未完成文件暂时无法下载。$retained"
            }
            putSnapshot(link.profile, id, WakuRecovery.resolveObservedCommands(restored), serverDirty = false)
        }
    }

    private suspend fun catchUp(link: Link, id: String, runtimeId: String) {
        val initial = mutex.withLock { ensureCurrent(link); link.profile.snapshots.getValue(id) }
        val end = WakuReplayRunner().run(id, runtimeId, initial.replayPosition(),
            request = { command -> ensureCurrent(link); link.client.request(command, id, runtimeId) },
            commit = { page -> mutex.withLock {
                ensureCurrent(link)
                for (wire in page.events) {
                    link.artifacts.onWireEvent(wire, replayed = true)
                    link.toolApproval.onWireEvent(wire)
                    applyEvent(link, wire)
                }
                val p = link.profile
                putSnapshot(p, id, p.snapshots.getValue(id).atReplayPosition(page.position), false)
                flushLocal(p)
                p.syncStatus[id] = "正在同步会话… ${page.percent}%"
                publish(p)
            } },
        )
        recoverInteractions(link, id, runtimeId, end)
        mutex.withLock {
            ensureCurrent(link)
            val p = link.profile
            val runtime = link.runtimes[id] ?: error("运行时已改变")
            runtime.epoch = end.epoch
            var snapshot = p.snapshots.getValue(id)
            if (snapshot.session.s("status") != "waiting") {
                snapshot = snapshot.copy(permissions = emptyList(), userInputs = emptyList(), interactionsVerified = true)
                putSnapshot(p, id, snapshot, false)
            }
            snapshot.taskReady?.takeIf { it.s("epoch") == end.epoch && it.s("runtimeId") == runtimeId }
                ?.let { link.artifacts.onWireEvent(it, replayed = true) }
            val buffered = runtime.buffered.toList()
            runtime.buffered.clear(); runtime.bytes = 0; runtime.catchingUp = false
            for (wire in buffered) {
                require(wire.s("epoch") == end.epoch) { "重放期间运行时发生变化" }
                applyEvent(link, wire)
            }
            flushLocal(p)
            publish(p)
        }
    }

    private suspend fun recoverInteractions(link: Link, id: String, runtimeId: String, through: WakuReplayPosition) {
        val snapshot = mutex.withLock { ensureCurrent(link); link.profile.snapshots.getValue(id) }
        if (snapshot.session.s("status") != "waiting" || snapshot.interactionsVerified) return
        var checkpoint = snapshot.interactionReplay?.takeIf {
            it.position?.epoch == through.epoch && it.position.runtimeId == runtimeId && it.position.sequence <= through.sequence
        } ?: WakuControlCheckpoint(position = WakuReplayPosition(runtimeId, through.epoch, 0))
        WakuReplayRunner().run(id, runtimeId, checkpoint.position, through.sequence,
            request = { command -> ensureCurrent(link); link.client.request(command, id, runtimeId) },
            commit = { page ->
                checkpoint = checkpoint.advance(page)
                mutex.withLock {
                    ensureCurrent(link)
                    val p = link.profile
                    val current = p.snapshots.getValue(id)
                    val accepted = current.pendingCommands.filter { it.s("state") == "accepted" }.mapNotNull { it.s("targetRequestId") }.toSet()
                    putSnapshot(p, id, current.copy(interactionReplay = checkpoint,
                        taskReady = newestReady(current.taskReady, checkpoint.taskReady),
                        permissions = if (page.hasMore) current.permissions else checkpoint.permissions.filterNot { it.s("requestId") in accepted },
                        userInputs = if (page.hasMore) current.userInputs else checkpoint.questions.filterNot { it.s("requestId") in accepted },
                        interactionsVerified = !page.hasMore), false)
                    flushLocal(p)
                    p.syncStatus[id] = "正在恢复待处理操作… ${page.percent}%"
                    publish(p)
                }
            },
        )
    }

    private fun newestReady(first: JsonObject?, second: JsonObject?): JsonObject? = when {
        second == null -> first
        first == null || first.s("epoch") != second.s("epoch") || second.n("sequence") > first.n("sequence") -> second
        else -> first
    }

    private suspend fun receiveEvent(link: Link, wire: JsonObject) {
        link.artifacts.onWireEvent(wire)
        link.toolApproval.onWireEvent(wire)
        mutex.withLock {
            ensureCurrent(link)
            val id = wire.s("sessionId") ?: return
            val runtime = link.runtimes[id] ?: return
            if (runtime.id != wire.s("runtimeId") || wire["replayable"] == JsonPrimitive(false)) return
            if (runtime.catchingUp) {
                runtime.bytes += wire.toString().length * 2
                if (runtime.buffered.size >= 2_048 || runtime.bytes > 8 * 1024 * 1024) throw IOException("同步期间事件缓冲区已满，重新连接补齐")
                runtime.buffered += wire
            } else {
                if (runtime.epoch != null && runtime.epoch != wire.s("epoch")) throw IOException("服务器运行时发生变化，需要重新同步")
                applyEvent(link, wire)
                publish(link.profile)
            }
        }
    }

    private fun applyEvent(link: Link, wire: JsonObject) {
        val id = wire.s("sessionId") ?: return
        val previous = link.profile.snapshots[id] ?: return
        if (WakuRecovery.needsHistoryRefresh(previous.session, link.profile.catalog[id])) return
        var snapshot = WakuProjection.reduce(previous, wire)
        if (snapshot === previous) return
        snapshot = WakuRecovery.resolveObservedCommands(snapshot)
        val eventKind = (wire["event"] as? JsonObject)?.s("kind")
        if (eventKind in setOf("promptSubmitted", "turnStarted")) link.artifactJobs[id]?.cancel()
        if (eventKind in setOf("turnFinished", "processExited")) {
            snapshot = snapshot.copy(pendingCommands = snapshot.pendingCommands.filter { it.s("kind") == "prompt" })
            link.checkpoint.trySend(Unit)
            if (eventKind == "turnFinished" && link.runtimes[id]?.catchingUp == false) {
                wire.s("runtimeId")?.let { scheduleArtifacts(link, id, it) }
            }
            if (eventKind == "processExited") link.artifactJobs[id]?.cancel()
        }
        putSnapshot(link.profile, id, snapshot)
        if (!retention.state.value.visible && link.runtimes[id]?.catchingUp == false &&
            eventKind in setOf("turnFinished", "permission", "userInputRequested")) {
            // Replayed history never re-notifies; notification failures cannot break synchronization.
            runCatching { taskNotifications.event(id, needsAttention = eventKind != "turnFinished") }
        }
    }

    /** Called under mutex; asynchronous replies must continue through the independent event consumer. */
    private fun scheduleArtifacts(link: Link, id: String, runtimeId: String) {
        if (link.profile.selected != id || link.artifactJobs[id]?.isActive == true || link.artifacts.hasExited(id, runtimeId)) return
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val files = link.artifacts.listArtifacts(id, runtimeId)
                mutex.withLock {
                    if (active === link) {
                        val old = link.profile.artifacts[id].orEmpty().associateBy { it.path }
                        val indexed = files.map { file ->
                            old[file.path]?.takeIf { it.name == file.name && it.size == file.size && it.mimeType == file.mimeType }
                                ?.let { file.copy(url = it.url) } ?: file
                        }
                        saveArtifactIndex(link.profile, id, indexed)
                        link.profile.artifactErrors.remove(id); publish(link.profile)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutex.withLock {
                    if (active === link) {
                        val retained = if (link.profile.artifacts[id].isNullOrEmpty()) "" else "已下载的文件仍可使用。"
                        link.profile.artifactErrors[id] = "文件读取未完成。${retained}点击刷新可重试：${error.message}"
                        publish(link.profile)
                    }
                }
            } finally { withContext(NonCancellable) { mutex.withLock {
                if (link.artifactJobs[id] === job) { link.artifactJobs.remove(id); publish(link.profile) }
            } } }
        }
        link.artifactJobs[id] = job
        job.start()
        publish(link.profile)
    }

    private fun saveArtifactIndex(p: Profile, id: String, files: List<AgentArtifact>) {
        p.artifacts[id] = files
        p.snapshots[id]?.let { putSnapshot(p, id, it.copy(artifactFiles = files), false) }
        flushLocal(p)
    }

    fun refreshArtifacts() = scope.launch {
        mutex.withLock {
            val p = profile ?: return@withLock
            val link = active?.takeIf { it.profile === p }
            val runtime = link?.runtimes?.get(p.selected)
            if (link != null && runtime != null && !link.artifacts.hasExited(p.selected, runtime.id)) {
                scheduleArtifacts(link, p.selected, runtime.id)
            } else {
                p.artifactErrors[p.selected] = "当前没有可用的文件读取连接；已下载的文件仍可打开。可返回会话重新同步。"
                publish(p)
            }
        }
    }

    private fun verifiedLocalArtifact(p: Profile, file: AgentArtifact): AgentArtifact? = runCatching {
        if (file.url.isBlank()) return null
        val uri = java.net.URI(file.url)
        if (uri.scheme != "file") return null
        val local = File(uri).canonicalFile
        val root = File(appContext.cacheDir, "waku-${p.key}/waku-artifacts").canonicalFile
        if (local.toPath().startsWith(root.toPath()) && local.isFile && local.length() == file.size &&
            local.name.substringBefore('-') == com.deerflow.app.data.oss.S3Signing.fileHash(local)
        ) file else null
    }.getOrNull()

    suspend fun resolveArtifact(file: AgentArtifact, threadId: String): AgentArtifact {
        val (p, link) = mutex.withLock {
            val p = profile ?: error("会话不可用")
            require(p.selected == threadId && p.artifacts[threadId].orEmpty().any { it.path == file.path }) { "会话已切换，请重新打开文件" }
            p to active?.takeIf { it.profile === p && _isConnected.value }
        }
        val runtime = link?.runtimes?.get(threadId)?.id
        val loaded = if (link != null && runtime != null && !link.artifacts.hasExited(threadId, runtime)) {
            try {
                link.artifacts.loadArtifact(threadId, runtime, file.path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { verifiedLocalArtifact(p, file) } ?: throw error
            }
        } else withContext(Dispatchers.IO) {
            verifiedLocalArtifact(p, file) ?: run {
                val uri = runCatching { java.net.URI(file.url) }.getOrNull()
                require(uri?.scheme == "file") { "此文件尚未下载，请连接服务器后重试" }
                error("文件缓存不可用，请连接服务器重新下载")
            }
        }
        mutex.withLock {
            val current = p.artifacts[threadId].orEmpty()
            val updated = current.map { if (it.path == loaded.path) loaded else it }
            if (updated != current) {
                saveArtifactIndex(p, threadId, updated)
                publish(p)
            }
        }
        return loaded
    }

    private fun messageCache(p: Profile) = MessageFileCache(File(appContext.cacheDir, "waku-${p.key}/message-files"))

    suspend fun resolveMessageFile(file: MessageFile, threadId: String): AgentArtifact {
        val p = mutex.withLock {
            val p = profile ?: error("会话不可用")
            val snapshot = p.snapshots[threadId]
            val fromSent = snapshot?.sentMessages?.values.orEmpty().any { sent -> sent.attachments.any { it.id == file.id } }
            val fromSession = snapshot?.session?.objs("messages").orEmpty().any { msg ->
                msg.objs("attachments").any { it.s("name") == file.name || it.s("blob_reference") == file.serverReference || it.s("path") == file.serverPath }
            }
            require(p.selected == threadId && (fromSent || fromSession || file.oss != null || file.serverReference != null)) {
                "会话已切换，请重新打开附件"
            }
            p
        }
        val result = messageCache(p).resolve(file, settings.currentOss()) { command ->
            val link = active?.takeIf { it.profile === p && profile === p } ?: error("请连接此会话的服务器后重试")
            link.client.request(command, timeoutMs = 120_000)
        }
        mutex.withLock {
            p.snapshots[threadId]?.let { snapshot ->
                val sent = snapshot.sentMessages.mapValues { (_, value) -> value.copy(attachments = value.attachments.map {
                    if (it.id == file.id) it.copy(localUrl = result.url) else it
                }) }
                if (sent != snapshot.sentMessages) {
                    putSnapshot(p, threadId, snapshot.copy(sentMessages = sent), false); flushLocal(p); publish(p)
                }
            }
        }
        return result
    }

    private suspend fun checkpoint(link: Link) {
        val captured = mutex.withLock {
            ensureCurrent(link)
            link.profile.serverDirty.filter { it in link.profile.ready && it !in link.profile.syncing && it !in link.profile.blocked && it in link.profile.remoteIds &&
                link.profile.snapshots[it]?.pendingHistory == null }
                .mapNotNull { id -> link.profile.snapshots[id]?.let { id to it } }.toMap()
        }
        if (captured.isEmpty()) return
        saveRemote(link, captured.values.map { it.session })
        mutex.withLock {
            ensureCurrent(link)
            captured.forEach { (id, value) -> if (link.profile.snapshots[id] === value) link.profile.serverDirty -= id }
        }
    }

    private suspend fun saveRemote(link: Link, sessions: List<JsonObject>) {
        val projects = mutex.withLock {
            ensureCurrent(link)
            val ids = sessions.filter { it.s("id") !in link.profile.remoteIds }.mapNotNull { it.s("project_id") }.toSet()
            link.profile.projects.filterKeys { it in ids }.values.toList()
        }
        val result = link.client.request(wakuCommand("saveTaskState") {
            put("projects", JsonArray(projects)); put("liveSessionIds", JsonArray(emptyList())); put("sessions", JsonArray(sessions))
        })
        require(result.s("type") == "taskStateSaved") { "服务器未确认会话保存" }
        mutex.withLock {
            ensureCurrent(link)
            for (session in sessions) session.s("id")?.let { id ->
                link.profile.remoteIds += id; link.profile.drafts -= id
                link.profile.snapshots[id]?.let { snapshot ->
                    if (!snapshot.remoteKnown) putSnapshot(link.profile, id, snapshot.copy(remoteKnown = true), false)
                }
            }
        }
    }

    fun newThread() = scope.launch {
        mutex.withLock {
            val p = profile ?: return@withLock
            val id = UUID.randomUUID().toString()
            active?.takeIf { it.profile === p }?.artifactJobs?.values?.forEach { it.cancel() }
            createDraft(p, id); p.selected = id
            if (_isConnected.value) p.ready += id
            publish(p)
        }
    }

    fun selectThread(id: String) = scope.launch {
        val link = mutex.withLock {
            val p = profile ?: return@launch
            active?.takeIf { it.profile === p }?.artifactJobs?.filterKeys { it != id }?.values?.forEach { it.cancel() }
            p.selected = id; publish(p)
            active?.takeIf { it.profile === p }
        }
        if (!id.startsWith("legacy:") && link != null) operations.withLock {
            try { hydrateAttach(link, id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutex.withLock {
                if (active === link && (id !in link.profile.ready || id in link.profile.syncing)) {
                    link.profile.syncErrors[id] = "同步未完成：${error.message ?: "请重新同步"}"
                    publish(link.profile)
                }
            } }
        }
    }

    fun send(text: String, attachments: List<PendingAttachment> = emptyList(), onPersisted: () -> Unit = {}): Job {
        val captured = selection
        val target = captured?.profile
        val id = captured?.id ?: _state.value.threadId
        val requestId = UUID.randomUUID().toString()
        val turnId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        val pending = buildJsonObject {
            put("kind", "prompt"); put("requestId", requestId); put("messageId", messageId); put("turnId", turnId)
            put("text", text); put("state", "prepared"); put("createdAt", System.currentTimeMillis())
            put("attachments", JsonArray(attachments.map { file -> buildJsonObject {
                put("uri", file.uri.toString()); put("name", file.displayName); put("mimeType", primitive(file.mimeType)); put("size", file.sizeBytes?.let(::JsonPrimitive) ?: JsonNull)
                put("uploadToOss", file.uploadToOss)
            } }))
        }
        val retainSubmission = target != null && !id.startsWith("legacy:")
        if (retainSubmission) {
            // Request protection while the initiating Activity is still visible, before upload/start.
            outgoingOperations.update { it + 1 }
            ensureBackgroundService()
        }
        return scope.launch {
            if (target == null || id.startsWith("legacy:")) return@launch
            try {
                // Record the user's original input before waiting for a connection, upload or another operation.
                val hadPrevious = recordPending(target, id, pending)
                onPersisted()
                require(!hadPrevious) { "已有尚未核对的操作，本条消息未发送，原文已保留" }
                operations.withLock {
                    val link = active?.takeIf { it.profile === target && profile === target }
                        ?: error("连接已改变，消息尚未发送，原文已保留")
                    mutex.withLock {
                        require(_isConnected.value && id in target.ready && id !in target.syncing && id !in target.blocked) { "连接尚未同步，消息尚未发送" }
                        require(target.snapshots[id]?.session?.s("status") !in BUSY) { "会话正在运行，消息尚未发送" }
                        require(target.snapshots[id]?.pendingHistory == null) { "会话历史操作尚未完成，请在桌面恢复后重新同步；消息尚未发送" }
                        require(WakuCommandPolicy.canSubmitPrompt(target.snapshots.getValue(id).pendingCommands, requestId)) { "上一项提交尚未确认，本条消息未发送" }
                    }
                    submit(link, id, text, attachments, requestId, turnId, messageId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val reason = error.message ?: "发送失败，原文已保留"
                mutex.withLock {
                    val old = target.snapshots[id]
                    if (old != null) {
                        val commands = old.pendingCommands.map {
                            if (it.s("requestId") == requestId && it.s("state") == "prepared") {
                                it.patch("state" to JsonPrimitive("notSent"), "error" to JsonPrimitive(reason))
                            } else it
                        }
                        putSnapshot(target, id, old.copy(pendingCommands = commands), false); publish(target)
                        runCatching { flushLocal(target) }
                    }
                }
                showError(target, reason)
            }
        }.also { job ->
            if (retainSubmission) job.invokeOnCompletion { outgoingOperations.update { count -> (count - 1).coerceAtLeast(0) } }
        }
    }

    private suspend fun submit(link: Link, id: String, text: String, attachments: List<PendingAttachment>, requestId: String, turnId: String, messageId: String) {
        require(text.isNotBlank() || attachments.isNotEmpty()) { "请输入消息或选择附件" }
        var promptDispatched = false
        var startDispatched = false
        var createdRuntime = false
        try {
            val prepared = uploadAttachments(link, attachments)
            val uploaded = prepared.native
            mutex.withLock {
                ensureCurrent(link)
                putSnapshot(link.profile, id, WakuMessageFiles.remember(link.profile.snapshots.getValue(id), messageId, text.trim(), prepared.files), false)
                flushLocal(link.profile)
            }
            var snapshot = mutex.withLock { ensureCurrent(link); link.profile.snapshots.getValue(id) }
            if (id !in link.profile.remoteIds) saveRemote(link, listOf(snapshot.session))
            var runtimeId = mutex.withLock { link.runtimes[id]?.id?.takeUnless { link.artifacts.hasExited(id, it) } }
            if (runtimeId == null) {
                val attached = WakuRuntimeAttachment.parse(link.client.request(wakuCommand("attachSession"), id))
                mutex.withLock {
                    ensureCurrent(link)
                    putSnapshot(link.profile, id, attached.applyTo(link.profile.snapshots.getValue(id)), false)
                }
                require(attached.pendingHistory == null) { "会话历史操作尚未完成，请在桌面恢复后重新同步；消息尚未发送" }
                link.artifacts.onAttached(id, attached)
                runtimeId = attached.runtimeId
                if (runtimeId == null) {
                    val daemonSettings = link.client.request(wakuCommand("getSettings"))["settings"] as? JsonObject ?: error("无法读取服务器设置")
                    val override = (daemonSettings["provider_binary_overrides"] as? JsonObject)?.get("deerFlow") ?: JsonNull
                    val probe = link.client.request(wakuCommand("probeProvider") {
                        put("provider", "deerFlow"); put("binaryOverride", override); put("discoverModels", false); put("probeVersion", false)
                    })["probe"] as? JsonObject ?: error("无法探测 DeerFlow")
                    require(probe.b("installed")) { "服务器未安装 DeerFlow" }
                    val binary = probe.s("path") ?: error("服务器未提供 DeerFlow 路径")
                    runtimeId = UUID.randomUUID().toString()
                    val runtime = requireNotNull(runtimeId)
                    mutex.withLock {
                        ensureCurrent(link)
                        snapshot = WakuProjection.bindRuntime(link.profile.snapshots.getValue(id), runtime)
                        putSnapshot(link.profile, id, snapshot, false)
                        link.runtimes[id] = Runtime(runtime)
                        flushLocal(link.profile)
                    }
                    val cwd = mutex.withLock { cwd(link.profile, snapshot.session) }
                    startDispatched = true
                    link.client.request(wakuCommand("start") {
                        put("options", buildJsonObject {
                            put("provider", "deerFlow"); put("binary", binary); put("cwd", cwd); put("mode", WakuToolApproval.RUNTIME_MODE)
                            put("model", primitive(link.profile.config.modelName))
                            put("reasoningEffort", snapshot.session["reasoning_effort"] ?: JsonNull)
                            put("thinkingMode", snapshot.session["thinking_mode"] ?: JsonNull)
                            put("serviceTier", snapshot.session["service_tier"] ?: JsonNull)
                            put("contextWindow", snapshot.session["context_window"] ?: JsonNull)
                            put("agentPreset", snapshot.session["agent_preset"] ?: JsonNull)
                            put("computerUseEnabled", false); put("providerCursor", snapshot.session["provider_cursor"] ?: JsonNull)
                        })
                    }, id, runtime, timeoutMs = 120_000)
                    createdRuntime = true
                } else mutex.withLock {
                    ensureCurrent(link)
                    putSnapshot(link.profile, id, WakuProjection.bindRuntime(link.profile.snapshots.getValue(id), requireNotNull(runtimeId)), false)
                    link.runtimes[id] = Runtime(requireNotNull(runtimeId))
                }
                catchUp(link, id, requireNotNull(runtimeId))
            }
            val desiredModel = primitive(link.profile.config.modelName)
            val current = mutex.withLock {
                ensureCurrent(link)
                link.profile.snapshots.getValue(id).also {
                    require(it.session.s("status") !in BUSY) { "服务器会话已开始执行；消息尚未发送" }
                    require(it.pendingHistory == null && id in link.profile.ready && id !in link.profile.syncing) {
                        "会话历史正在同步或恢复，消息尚未发送"
                    }
                }
            }
            if (!createdRuntime && (current.session["model"] ?: JsonNull) != desiredModel) {
                val applied = link.client.request(wakuCommand("applyOptions") {
                    put("options", buildJsonObject {
                        put("mode", WakuToolApproval.RUNTIME_MODE); put("model", desiredModel)
                        put("reasoningEffort", current.session["reasoning_effort"] ?: JsonNull)
                        put("thinkingMode", current.session["thinking_mode"] ?: JsonNull)
                        put("serviceTier", current.session["service_tier"] ?: JsonNull)
                        put("contextWindow", current.session["context_window"] ?: JsonNull)
                    })
                }, id, requireNotNull(runtimeId))
                require(applied.s("type") == "optionsApplied" && applied.b("applied")) { "服务器未应用所选模型，消息尚未发送；运行时未重启" }
            }
            // A resumed DeerFlow session retains its backend approval policy. A runtime mode
            // or an applyOptions ACK alone cannot confirm Full Access.
            link.toolApproval.ensureFullAccess(id, requireNotNull(runtimeId), allowUninitialized = true)
            mutex.withLock {
                ensureCurrent(link)
                val latest = link.profile.snapshots.getValue(id)
                require(latest.session.s("status") !in BUSY) { "服务器会话已开始执行；消息尚未发送" }
                require(latest.pendingHistory == null && id in link.profile.ready && id !in link.profile.syncing) {
                    "会话历史正在同步或恢复，消息尚未发送"
                }
                putSnapshot(link.profile, id, latest.copy(session = latest.session.patch(
                    "model" to desiredModel, "runtime_mode" to JsonPrimitive(WakuToolApproval.RUNTIME_MODE),
                )))
            }
            updatePending(link, id, requestId, "sending", uploaded)
            promptDispatched = true
            link.client.request(wakuCommand(if (uploaded.isEmpty()) "prompt" else "promptWithAttachments") {
                put("prompt", OssAttachmentEnvelope.append(text, prepared.files, prepared.ossSettings)); put("turnId", turnId); put("messageId", messageId)
                if (uploaded.isNotEmpty()) put("attachments", JsonArray(uploaded))
            }, id, requireNotNull(runtimeId), requestId)
            updatePending(link, id, requestId, "accepted", uploaded)
            link.checkpoint.trySend(Unit)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (!promptDispatched) removeUnsentMessage(link.profile, id, messageId)
                updatePending(link, id, requestId, if (promptDispatched) "unknown" else "notSent")
            }
            throw cancelled
        } catch (error: Exception) {
            if (!promptDispatched) removeUnsentMessage(link.profile, id, messageId)
            val state = if (!promptDispatched || (error is WakuRequestException && error.delivery == WakuDelivery.NOT_SENT)) "notSent"
                else if (error is WakuRequestException && error.delivery == WakuDelivery.REJECTED) "rejected" else "unknown"
            updatePending(link, id, requestId, state, errorMessage = error.message)
            if (startDispatched && !promptDispatched && (error !is WakuRequestException || error.delivery == WakuDelivery.RESULT_UNKNOWN)) {
                mutex.withLock {
                    link.profile.blocked[id] = "运行时启动结果未知，正在重新连接核对；消息未自动发送。"
                    publish(link.profile)
                }
                refresh()
            }
            throw error
        }
    }

    private suspend fun removeUnsentMessage(p: Profile, id: String, messageId: String) = mutex.withLock {
        val snapshot = p.snapshots[id] ?: return@withLock
        if (messageId in snapshot.sentMessages) {
            putSnapshot(p, id, snapshot.copy(sentMessages = snapshot.sentMessages - messageId), false)
        }
    }

    fun cancel() = control("cancel")
    fun respondPermission(requestId: String, optionId: String) = control("respond", requestId) { put("requestId", requestId); put("optionId", optionId) }
    fun respondUserInput(requestId: String, answers: JsonArray) = control("respondUserInput", requestId) { put("requestId", requestId); put("answers", answers) }

    private fun control(kind: String, target: String? = null, command: JsonObjectBuilder.() -> Unit = {}): Job {
        val captured = selection
        val p = captured?.profile
        val id = captured?.id ?: _state.value.threadId
        val requestId = UUID.randomUUID().toString()
        return scope.launch {
            if (p == null || id.startsWith("legacy:")) return@launch
            var dispatched = false
            try {
                val sequence = mutex.withLock { p.snapshots[id]?.cursor?.n("sequence") ?: 0 }
                recordPending(p, id, buildJsonObject {
                    put("kind", kind); put("requestId", requestId); put("targetRequestId", primitive(target)); put("state", "prepared"); put("afterSequence", sequence)
                })
                operations.withLock {
                    val link = active?.takeIf { it.profile === p && profile === p } ?: error("连接已改变，操作尚未发送")
                    val runtime = mutex.withLock {
                        ensureCurrent(link)
                        require(_isConnected.value && id in p.ready && id !in p.syncing) { "连接尚未同步，操作未发送" }
                        require(p.snapshots[id]?.pendingHistory == null) { "会话历史操作尚未完成，请在桌面恢复后重新同步；操作未发送" }
                        require(WakuCommandPolicy.canSubmitControl(p.snapshots[id]?.pendingCommands.orEmpty(), requestId, kind, target)) { "此操作已提交，请先核对结果" }
                        link.runtimes[id]?.id ?: error("会话没有可连接的运行时")
                    }
                    updatePending(p, id, requestId, "sending")
                    dispatched = true
                    link.client.request(wakuCommand(kind, command), id, runtime, requestId)
                    updatePending(p, id, requestId, "accepted")
                    mutex.withLock {
                        if (active === link && target != null) {
                            val old = p.snapshots.getValue(id)
                            putSnapshot(p, id, old.copy(
                                permissions = if (kind == "respond") old.permissions.filterNot { it.s("requestId") == target } else old.permissions,
                                userInputs = if (kind == "respondUserInput") old.userInputs.filterNot { it.s("requestId") == target } else old.userInputs,
                            ), false)
                            flushLocal(p); publish(p)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { updatePending(p, id, requestId, if (dispatched) "unknown" else "notSent") }
                throw cancelled
            } catch (error: Exception) {
                val state = if (!dispatched || error is WakuRequestException && error.delivery == WakuDelivery.NOT_SENT) "notSent"
                    else if (error is WakuRequestException && error.delivery == WakuDelivery.REJECTED) "rejected" else "unknown"
                runCatching { updatePending(p, id, requestId, state, errorMessage = error.message) }
                showError(p, error.message ?: "操作失败，请核对")
            }
        }
    }

    fun acknowledgeUnknown() = scope.launch {
        mutex.withLock {
            val p = profile ?: return@withLock
            val snapshot = p.snapshots[p.selected] ?: return@withLock
            if (snapshot.pendingCommands.any { it.s("state") in setOf("prepared", "sending") }) {
                _connection.value = "当前提交仍在进行，请等待结果后再核对"
                publish(p)
                return@withLock
            }
            putSnapshot(p, p.selected, snapshot.copy(pendingCommands = WakuCommandPolicy.acknowledge(snapshot.pendingCommands)), false)
            if (_isConnected.value && _connection.value != "已连接") _connection.value = "已连接"
            flushLocal(p); publish(p)
        }
    }

    fun deleteThread(id: String) = userOperation { link, _ ->
        require(!id.startsWith("legacy:")) { "旧 AG-UI 历史只读，未修改原文件" }
        val isRemote = mutex.withLock { id in link.profile.remoteIds }
        if (isRemote) {
            val runtime = mutex.withLock { link.runtimes[id]?.id ?: NIL_UUID }
            val requestId = UUID.randomUUID().toString()
            if (link.profile.snapshots[id] == null) hydrateAttach(link, id)
            addPending(link, id, buildJsonObject { put("kind", "removeSession"); put("requestId", requestId); put("state", "sending") })
            try { link.client.request(wakuCommand("removeSession"), id, runtime, requestId) }
            catch (error: Exception) { updatePending(link, id, requestId, "unknown"); throw error }
        }
        mutex.withLock {
            ensureCurrent(link)
            val p = link.profile
            link.artifactJobs.remove(id)?.cancel()
            p.store.delete(id); p.snapshots.remove(id); p.catalog.remove(id); p.remoteIds -= id; p.drafts -= id
            p.localDirty -= id; p.serverDirty -= id; p.ready -= id; p.syncing -= id
            p.blocked.remove(id); p.artifacts.remove(id); p.artifactErrors.remove(id)
            p.syncErrors.remove(id); p.syncStatus.remove(id); link.runtimes.remove(id)
            if (p.selected == id) { p.selected = UUID.randomUUID().toString(); createDraft(p, p.selected); p.ready += p.selected }
            publish(p)
        }
    }

    fun renameThread(id: String, newTitle: String) = scope.launch {
        if (id.startsWith("legacy:")) {
            mutex.withLock { profile?.let { _connection.value = "旧历史只读，无法重命名"; publish(it) } }
            return@launch
        }
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        val (p, link, needsHydrate) = mutex.withLock {
            val p = profile ?: return@launch
            val nowSec = System.currentTimeMillis() / 1000
            val snapshot = p.snapshots[id]
            if (snapshot != null) {
                val updatedSession = snapshot.session.patch("title" to JsonPrimitive(trimmed), "updated_at" to JsonPrimitive(nowSec))
                putSnapshot(p, id, snapshot.copy(session = updatedSession), serverDirty = true)
            }
            p.catalog[id]?.let { summary ->
                p.catalog[id] = summary.patch("title" to JsonPrimitive(trimmed), "updated_at" to JsonPrimitive(nowSec))
            }
            flushLocal(p)
            publish(p)
            Triple(p, active?.takeIf { it.profile === p }, id in p.remoteIds && (snapshot == null || id !in p.ready))
        }
        if (needsHydrate && link != null) {
            operations.withLock {
                runCatching { hydrateAttach(link, id) }
                mutex.withLock {
                    if (profile === p) {
                        val snapshot = p.snapshots[id]
                        if (snapshot != null) {
                            val nowSec = System.currentTimeMillis() / 1000
                            val updatedSession = snapshot.session.patch("title" to JsonPrimitive(trimmed), "updated_at" to JsonPrimitive(nowSec))
                            putSnapshot(p, id, snapshot.copy(session = updatedSession), serverDirty = true)
                            p.catalog[id] = p.catalog[id]?.patch("title" to JsonPrimitive(trimmed), "updated_at" to JsonPrimitive(nowSec)) ?: updatedSession
                            flushLocal(p)
                            publish(p)
                        }
                    }
                }
            }
        }
        active?.takeIf { it.profile === p }?.checkpoint?.trySend(Unit)
    }

    suspend fun takeRestorablePrompt(id: String): Pair<String, List<PendingAttachment>>? = mutex.withLock {
        val p = profile ?: return@withLock null
        val snapshot = p.snapshots[id] ?: return@withLock null
        if (snapshot.pendingCommands.any { it.s("state") in setOf("prepared", "sending") }) {
            _connection.value = "当前提交仍在进行，请等待结果后再恢复"
            publish(p)
            return@withLock null
        }
        val promptCmd = snapshot.pendingCommands.firstOrNull {
            it.s("kind") == "prompt" && it.s("state") in setOf("unknown", "notSent", "rejected")
        } ?: return@withLock null
        val text = promptCmd.s("text").orEmpty()
        val attachments = promptCmd.objs("attachments").mapNotNull { item ->
            val uriStr = item.s("uri")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@mapNotNull null
            PendingAttachment(
                uri = uri,
                displayName = item.s("name").orEmpty().ifBlank { "attachment" },
                mimeType = item.s("mimeType"),
                sizeBytes = (item["size"] as? JsonPrimitive)?.content?.toLongOrNull(),
                uploadToOss = (item["uploadToOss"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            )
        }
        putSnapshot(p, id, snapshot.copy(pendingCommands = WakuCommandPolicy.acknowledge(snapshot.pendingCommands)), false)
        if (_isConnected.value && _connection.value != "已连接") _connection.value = "已连接"
        flushLocal(p)
        publish(p)
        text to attachments
    }

    private fun userOperation(action: suspend (Link, String) -> Unit): Job {
        val capturedLink = active
        val capturedId = selection?.id ?: _state.value.threadId
        return scope.launch { operations.withLock {
            val link = capturedLink ?: run { _connection.value = "请先连接并同步服务器"; return@launch }
            try {
                ensureCurrent(link)
                require(_isConnected.value) { "请先连接并同步服务器" }
                require(!capturedId.startsWith("legacy:")) { "旧历史只读，请新建 Waku 会话" }
                action(link, capturedId)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(link.profile, error.message ?: "操作失败") }
        } }
    }

    private suspend fun addPending(link: Link, id: String, command: JsonObject) {
        ensureCurrent(link)
        recordPending(link.profile, id, command)
    }
    private suspend fun recordPending(p: Profile, id: String, command: JsonObject) = mutex.withLock {
        val snapshot = p.snapshots[id] ?: error("会话尚未加载")
        active?.takeIf { it.profile === p }?.artifactJobs?.values?.forEach { it.cancel() }
        val hadPrevious = snapshot.pendingCommands.isNotEmpty()
        putSnapshot(p, id, snapshot.copy(pendingCommands = snapshot.pendingCommands + command), false)
        publish(p)
        flushLocal(p) // Must complete before the first side-effecting request is sent.
        hadPrevious
    }

    private suspend fun updatePending(
        link: Link,
        id: String,
        requestId: String,
        state: String,
        attachments: List<JsonObject>? = null,
        errorMessage: String? = null,
    ) = updatePending(link.profile, id, requestId, state, attachments, errorMessage)

    private suspend fun updatePending(
        p: Profile,
        id: String,
        requestId: String,
        state: String,
        attachments: List<JsonObject>? = null,
        errorMessage: String? = null,
    ) = mutex.withLock {
        // An interrupted operation can still update its own profile cache, never the currently selected profile.
        val snapshot = p.snapshots[id] ?: return@withLock
        val commands = snapshot.pendingCommands.map { command ->
            if (command.s("requestId") != requestId) command else JsonObject(command.toMutableMap().apply {
                put("state", JsonPrimitive(state))
                attachments?.let { put("uploadedAttachments", JsonArray(it)) }
                if (!errorMessage.isNullOrBlank()) put("error", JsonPrimitive(errorMessage))
                else if (state in setOf("sending", "accepted")) remove("error")
            })
        }
        putSnapshot(p, id, snapshot.copy(pendingCommands = commands), false)
        if (profile === p) {
            if (state == "accepted" && _isConnected.value && _connection.value != "已连接") _connection.value = "已连接"
            publish(p)
        }
        flushLocal(p)
    }

    private class PreparedFiles(val native: List<JsonObject>, val files: List<MessageFile>, val ossSettings: com.deerflow.app.data.oss.S3Settings)

    private suspend fun uploadAttachments(link: Link, files: List<PendingAttachment>): PreparedFiles {
        require(files.size <= 8) { "每条消息最多添加 8 个附件" }
        val ossSettings = settings.currentOss()
        if (files.any { it.uploadToOss }) require(ossSettings.configured) { "请先在设置中配置 OSS 账号，或切换为上传到服务器" }
        val native = mutableListOf<JsonObject>()
        val cache = messageCache(link.profile)
        var total = 0L
        var ossTotal = 0L
        val displayed = files.map { pending ->
            ensureCurrent(link)
            val name = pending.displayName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }
            val mime = pending.mimeType ?: "application/octet-stream"
            val isImage = pending.mimeType?.startsWith("image/") == true
            val maximum = if (pending.uploadToOss) { if (isImage) 20L * 1024 * 1024 else S3Storage.MAX_FILE_BYTES } else 8L * 1024 * 1024
            require(pending.sizeBytes == null || pending.sizeBytes <= maximum) { "附件超过当前上传方式的单文件上限：$name" }
            val staged = appContext.contentResolver.openInputStream(pending.uri)?.use { cache.stage(it, name, maximum) }
                ?: error("无法读取附件：$name")
            val fileId = UUID.randomUUID().toString()
            val base = MessageFile(fileId, name, mime, staged.size, staged.file.toURI().toString(), sha256 = staged.sha256)
            if (pending.uploadToOss) {
                ossTotal += staged.size
                require(ossTotal <= 100L * 1024 * 1024) { "每条消息的 OSS 附件总量最多 100 MiB" }
                val safeName = staged.file.name.substringAfter('-')
                val obj = S3Storage().upload(ossSettings, staged.file, "deerflow-app/attachments/$fileId/$safeName", name, mime)
                return@map base.copy(oss = obj)
            }
            total += staged.size
            require(total <= 16L * 1024 * 1024) { "每条消息上传到服务器的附件总量最多 16 MiB" }
            val encoded = Base64.getEncoder().encodeToString(staged.file.readBytes())
            val result = if (isImage) link.client.request(wakuCommand("storeBlob") {
                put("mimeType", pending.mimeType!!); put("bytes", encoded)
            }, timeoutMs = 120_000) else link.client.request(wakuCommand("importAttachment") {
                put("name", name); put("upload", buildJsonObject { put("kind", "file"); put("data_base64", encoded) })
            }, timeoutMs = 120_000)
            val stored = if (isImage) result else result["attachment"] as? JsonObject ?: error("附件上传未返回引用")
            val path = stored.s("path") ?: error("附件缺少服务器路径")
            val reference = stored.s("reference") ?: error("附件缺少服务器引用")
            native += buildJsonObject {
                put("path", path); put("mention", path); put("name", name); put("is_dir", false); put("is_image", isImage); put("blob_reference", reference)
            }
            base.copy(serverReference = reference, serverPath = path)
        }
        return PreparedFiles(native, displayed, ossSettings)
    }

    private fun createDraft(p: Profile, id: String) {
        val projectId = UUID.nameUUIDFromBytes("${p.key}/${p.config.workspace}".toByteArray()).toString()
        p.projects.putIfAbsent(projectId, WakuProjection.createProject(projectId, "Mobile workspace", p.config.workspace))
        p.snapshots[id] = WakuProjection.fromSession(WakuProjection.createSession(id, projectId, p.config.modelName))
        p.drafts += id
    }
    private fun cwd(p: Profile, session: JsonObject): String {
        val workspace = session["workspace"] as? JsonObject
        return if (workspace?.s("kind") == "worktree") workspace.s("path") ?: p.config.workspace
        else p.projects[session.s("project_id")]?.s("path") ?: p.config.workspace
    }
    private fun putSnapshot(p: Profile, id: String, snapshot: WakuSnapshot, serverDirty: Boolean = true) {
        p.snapshots[id] = snapshot; p.localDirty += id
        p.artifacts[id] = snapshot.artifactFiles
        if (serverDirty) p.serverDirty += id
    }
    private fun flushLocal(p: Profile) {
        for (id in p.localDirty.toList()) { p.snapshots[id]?.let(p.store::save); p.localDirty -= id }
    }
    private fun ensureCurrent(link: Link) {
        if (active !== link || profile !== link.profile) throw CancellationException("Connection was superseded")
    }
    private suspend fun setConnection(p: Profile, text: String, connected: Boolean) = mutex.withLock {
        if (profile === p) { _connection.value = text; _isConnected.value = connected; publish(p) }
    }
    private suspend fun showError(p: Profile, text: String) = mutex.withLock {
        if (profile === p) { _connection.value = text; publish(p) }
    }

    private fun publish(p: Profile) {
        if (profile !== p) return
        val rows = p.catalog.toMutableMap().apply { p.snapshots.forEach { (id, snapshot) -> put(id, snapshot.session) } }
        _threads.value = rows.values.filter { it.s("provider") == "deerFlow" }.mapNotNull { session ->
            val id = session.s("id") ?: return@mapNotNull null
            ThreadMeta(id, WakuSessionTitles.display(session), session.n("updated_at") * 1000, true)
        }.sortedByDescending { it.lastActive } + legacyIndex
        _runningThreadIds.value = rows.filterValues { it.s("status") in BUSY }.keys
        val running = rows.values.filter { it.s("provider") == "deerFlow" && it.s("status") in BUSY }
        backgroundSummary.value = BackgroundWork(
            runningTasks = running.size,
            waitingTasks = running.count { it.s("status") == "waiting" },
            pendingOperations = p.snapshots.values.filter { it.session.s("provider") == "deerFlow" }
                .sumOf { stored -> stored.pendingCommands.count { it.s("state") in setOf("prepared", "sending", "accepted", "unknown") } },
        )
        val snapshot = p.snapshots[p.selected]
        val readOnly = p.selected.startsWith("legacy:")
        val submission = WakuSubmissionStatus.from(snapshot?.pendingCommands.orEmpty())
        _submissionStatus.value = submission
        _state.value = if (readOnly) legacyStates.getOrPut(p.selected) { readLegacy(p.selected) } else snapshot?.let { WakuProjection.display(it) }
            ?: ConversationState(p.selected, status = "正在加载会话…")
        submission.progress?.let { _state.value = _state.value.copy(status = it) }
        p.blocked[p.selected]?.let { _state.value = _state.value.copy(status = it) }
        snapshot?.pendingHistory?.let {
            _state.value = _state.value.copy(status = "会话历史操作尚未完成，请在桌面恢复后重新同步。")
        }
        p.syncErrors[p.selected]?.let { _state.value = _state.value.copy(status = it) }
        if (p.selected in p.syncing) _state.value = _state.value.copy(status = p.syncStatus[p.selected] ?: "正在同步会话…")
        _taskFiles.value = TaskFilesState(p.artifacts[p.selected].orEmpty(),
            active?.takeIf { it.profile === p }?.artifactJobs?.get(p.selected)?.isActive == true,
            p.artifactErrors[p.selected])
        val actionsReady = p.selected in p.ready && p.selected !in p.syncing && snapshot?.interactionsVerified == true && snapshot.pendingHistory == null
        _pendingPermissions.value = if (actionsReady) snapshot?.permissions.orEmpty() else emptyList()
        _pendingQuestions.value = if (actionsReady) snapshot?.userInputs.orEmpty() else emptyList()
        _canSend.value = _isConnected.value && !readOnly && p.selected in p.ready && p.selected !in p.syncing &&
            p.selected !in p.blocked && snapshot != null && snapshot.pendingHistory == null && snapshot.session.s("status") !in BUSY && snapshot.pendingCommands.isEmpty()
        selection = Selection(p, p.selected)
    }

    private fun readLegacyIndex(): List<ThreadMeta> = runCatching {
        val file = File(appContext.filesDir, "threads/threads_index.json")
        if (!file.exists() || file.length() > 2 * 1024 * 1024) return@runCatching emptyList()
        AguiJson.decodeFromString(ListSerializer(ThreadMeta.serializer()), file.readText()).filter { validLegacyId(it.id) }
            .map { it.copy(id = "legacy:${it.id}", title = "[旧历史·只读] ${it.title}") }
    }.getOrDefault(emptyList())
    private fun readLegacy(id: String): ConversationState = runCatching {
        val rawId = id.removePrefix("legacy:")
        require(validLegacyId(rawId))
        val file = File(appContext.filesDir, "threads/thread_$rawId.json")
        require(file.exists() && file.length() <= 16 * 1024 * 1024)
        val content = file.readText()
        val stored = runCatching { AguiJson.decodeFromString(ThreadStore.serializer(), content) }.getOrElse {
            ThreadStore(messages = AguiJson.decodeFromString(ListSerializer(ChatMessage.serializer()), content))
        }
        ConversationReducer.reduce(ConversationState(id), AguiEvent("MESSAGES_SNAPSHOT", buildJsonObject {
            put("messages", AguiJson.encodeToJsonElement(ListSerializer(ChatMessage.serializer()), stored.messages))
        })).appendArtifacts(stored.artifacts).copy(status = "旧 AG-UI 历史只读；请新建会话使用 Waku", running = false)
    }.getOrElse { ConversationState(id, status = "旧历史无法读取；原文件未修改") }

    companion object {
        private val BUSY = setOf("connecting", "working", "waiting", "background")
        @Volatile private var instance: ConversationRepository? = null
        fun get(context: Context): ConversationRepository = instance ?: synchronized(this) {
            instance ?: ConversationRepository(context.applicationContext, SettingsStore(context.applicationContext)).also { instance = it }
        }
    }
}

private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.n(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0
private fun JsonObject.b(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.objs(key: String): List<JsonObject> = arr(key).filterIsInstance<JsonObject>()
private fun JsonObject.patch(vararg fields: Pair<String, JsonElement>) = JsonObject(toMutableMap().apply { putAll(fields) })
private fun primitive(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
private fun validUuid(value: String) = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
private fun validLegacyId(value: String) = validUuid(value) || Regex("thread-[a-fA-F0-9]{32}").matches(value)
