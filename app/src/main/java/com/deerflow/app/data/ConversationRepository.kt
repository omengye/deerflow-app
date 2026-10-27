package com.deerflow.app.data

import android.content.Context
import android.content.Intent
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
import java.io.ByteArrayOutputStream
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
    private val serviceRequested = AtomicBoolean(false)
    private val outgoingOperations = MutableStateFlow(0)
    private val backgroundSummary = MutableStateFlow(BackgroundWork())
    private val taskNotifications by lazy { TaskNotifications(appContext) }
    val backgroundConnection = retention.state
    val backgroundWork = combine(backgroundSummary, outgoingOperations) { work, count -> work.copy(transmitting = count) }
    private val refreshSignal = MutableStateFlow(0L)
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
    private val _unknownSubmission = MutableStateFlow<String?>(null)
    val state = _state.asStateFlow()
    val threads = _threads.asStateFlow()
    val runningThreadIds = _runningThreadIds.asStateFlow()
    val connection = _connection.asStateFlow()
    val isConnected = _isConnected.asStateFlow()
    val canSend = _canSend.asStateFlow()
    val profileKey = _profileKey.asStateFlow()
    val pendingPermissions = _pendingPermissions.asStateFlow()
    val pendingQuestions = _pendingQuestions.asStateFlow()
    val unknownSubmission = _unknownSubmission.asStateFlow()
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
        val ended = CompletableDeferred<Throwable>()
        val runtimes = mutableMapOf<String, Runtime>()
        val checkpoint = Channel<Unit>(Channel.CONFLATED)
        val titleRefresh = Channel<Unit>(Channel.CONFLATED)
        val titleChecked = mutableMapOf<String, JsonObject>()
        val catalogRefresh = Mutex()
        val artifactLoading = mutableSetOf<String>()
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
            combine(settings.flow, retention.keepConnected, network.isAvailable, refreshSignal, network.changes) { cfg, keepConnected, online, _, _ ->
                Triple(cfg, keepConnected, online)
            }.collectLatest { (cfg, keepConnected, online) ->
                try {
                    val current = activateProfile(cfg)
                    if (!cfg.isConfigured) {
                        setConnection(current, if (cfg.needsReconfiguration) "旧接口配置需要重新设置为 Waku /v1" else cfg.validationError ?: "请配置连接", false)
                    } else if (!keepConnected) setConnection(current, "后台接收已暂停，返回应用后自动恢复；服务器任务继续运行", false)
                    else if (!online) setConnection(current, "网络不可用，等待恢复", false)
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
    }

    fun setForeground(value: Boolean) {
        val returning = value && !retention.state.value.visible
        retention.setVisible(value)
        if (value && (backgroundSummary.value.hasWork || outgoingOperations.value > 0)) ensureBackgroundService()
        if (returning) active?.let { link ->
            scope.launch {
                try {
                    // A screen-off socket can be half-open even while Android still reports a network.
                    val response = link.client.request(wakuCommand("getSettings"), timeoutMs = 8_000)
                    require(response.s("type") == "settings")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (active === link && retention.state.value.visible) refresh()
                }
            }
        }
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

    fun refresh() { refreshSignal.update { it + 1 } }

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
                p.snapshots[stored.session.s("id") ?: return@forEach] = recovered
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
        while (currentCoroutineContext().isActive && profile === current) {
            setConnection(current, if (attempt == 0) "正在连接服务器…" else "连接中断，正在重新同步…", false)
            val link = Link(current, WakuClient(current.config.endpoint, current.config.token, UUID.randomUUID().toString(), incomingCapacity = 512))
            link.artifacts = WakuTaskArtifacts(File(appContext.cacheDir, "waku-${current.key}")) { command, session, runtime ->
                ensureCurrent(link)
                link.client.request(command, session, runtime)
            }
            link.toolApproval = WakuToolApproval { command, session, runtime ->
                ensureCurrent(link)
                link.client.request(command, session, runtime)
            }
            active = link
            val failure = try {
                runConnection(link)
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
                            active = null; current.ready.clear(); current.syncing.clear()
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
            delay(WakuRecovery.retryDelayMillis(attempt++))
        }
    }

    private suspend fun runConnection(link: Link): Throwable = coroutineScope {
        link.client.connect()
        ensureCurrent(link)
        val receive = launch {
            try {
                for (wire in link.client.incoming) {
                    ensureCurrent(link)
                    when (wire.s("type")) {
                        "event" -> receiveEvent(link, wire)
                        "taskStateChanged" -> launch { runCatching { loadCatalog(link) } }
                        "shuttingDown" -> link.ended.complete(IOException("服务器正在关闭"))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { link.ended.complete(error); link.client.close() }
        }
        val watch = launch {
            val closed = link.client.state.filterIsInstance<WakuConnectionState.Disconnected>().first()
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
        val serverWriter = launch {
            while (isActive) {
                withTimeoutOrNull(2_000) { link.checkpoint.receive() }
                try { checkpoint(link) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { setConnection(link.profile, "会话正在本地保存；服务器同步失败：${error.message}", true) }
            }
        }
        try {
            loadCatalog(link)
            val toRestore = mutex.withLock {
                ensureCurrent(link)
                val p = link.profile
                (listOf(p.selected) + p.catalog.filterValues { it.s("status") in BUSY }.keys).distinct().filterNot { it.startsWith("legacy:") }
            }
            for (id in toRestore) hydrateAttach(link, id)
            setConnection(link.profile, "已连接", true)
            link.ended.await()
        } finally {
            receive.cancel(); watch.cancel(); localWriter.cancel(); serverWriter.cancel(); titleReader.cancel()
        }
    }

    private suspend fun loadCatalog(link: Link) = link.catalogRefresh.withLock {
        val result = link.client.request(wakuCommand("loadTaskState"))
        require(result.s("type") == "taskState") { "服务器返回的会话列表无效" }
        mutex.withLock {
            ensureCurrent(link)
            val p = link.profile
            p.projects.putAll(result.objs("projects").associateBy { it.s("id").orEmpty() })
            p.catalog.clear()
            p.catalog.putAll(result.objs("sessions").filter { it.s("provider") == "deerFlow" }.associateBy { it.s("id").orEmpty() })
            p.remoteIds.clear(); p.remoteIds += p.catalog.keys
            // Only copy title metadata: catalog rows deliberately contain empty transcripts/cursors.
            for ((id, summary) in p.catalog) {
                val snapshot = p.snapshots[id] ?: continue
                val merged = WakuSessionTitles.mergeMetadata(snapshot.session, summary)
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
        }
        link.titleRefresh.trySend(Unit)
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
                            else current.copy(session = WakuSessionTitles.mergeMetadata(current.session, remote, fullSnapshot = true))
                        if (next != current) putSnapshot(p, id, next, false)
                        publish(p)
                    }
                }
            }
        }
    }

    private suspend fun hydrateAttach(link: Link, id: String) {
        val shouldHydrate = mutex.withLock {
            ensureCurrent(link)
            if (id in link.profile.syncing) return
            link.profile.syncing += id; link.profile.ready -= id; publish(link.profile)
            id in link.profile.remoteIds
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
            val response = link.client.request(wakuCommand("hydrateSession") { put("sessionId", id) })
            val remote = response["session"] as? JsonObject ?: error("服务器会话已不存在")
            mutex.withLock {
                ensureCurrent(link)
                val chosen = WakuRecovery.resolveObservedCommands(WakuRecovery.chooseSnapshot(link.profile.snapshots[id], remote)).copy(remoteKnown = true)
                putSnapshot(link.profile, id, chosen, serverDirty = false)
            }
            val attached = link.client.request(wakuCommand("attachSession"), id)
            require(attached.s("type") == "sessionRuntime") { "无法识别服务器运行时" }
            val runtimeId = attached.s("runtimeId")
            if (runtimeId == null) recoverNativeHistory(link, id)
            else {
                mutex.withLock {
                    ensureCurrent(link)
                    val p = link.profile
                    p.blocked.remove(id)
                    putSnapshot(p, id, WakuProjection.bindRuntime(p.snapshots.getValue(id), runtimeId), serverDirty = false)
                    link.runtimes[id] = Runtime(runtimeId)
                }
                catchUp(link, id, runtimeId)
            }
            mutex.withLock { ensureCurrent(link); link.profile.ready += id }
        } finally {
            mutex.withLock { if (active === link) { link.profile.syncing -= id; publish(link.profile) } }
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
        if (cursor != null) {
            val cwd = mutex.withLock { cwd(link.profile, snapshot.session) }
            val result = link.client.request(wakuCommand("loadProviderSession") { put("cursor", cursor); put("cwd", cwd) })
            val history = result["history"] as? JsonObject ?: error("无法读取原生会话历史")
            // Native imports are bounded, text-only and mint fresh message IDs. They cannot replace
            // an existing hydrated transcript or be merged by comparing message text.
            if (snapshot.session.arr("messages").isEmpty() && snapshot.session.arr("transcript_blocks").isEmpty()) {
                restored = WakuProjection.fromSession(snapshot.session.patch(
                    "messages" to history.arr("messages"), "turns" to history.arr("turns"),
                    "runtime_event_cursor" to JsonNull,
                )).copy(pendingCommands = restored.pendingCommands, remoteKnown = snapshot.remoteKnown)
            }
        }
        mutex.withLock {
            ensureCurrent(link)
            link.runtimes.remove(id)
            if (wasBusy) link.profile.blocked[id] = "运行时已不可用。已读取原生历史；请在桌面核对或恢复执行，手机不会自动继续。"
            else link.profile.blocked.remove(id)
            putSnapshot(link.profile, id, WakuRecovery.resolveObservedCommands(restored), serverDirty = false)
        }
    }

    private suspend fun catchUp(link: Link, id: String, runtimeId: String) {
        // A full log scan also recovers v10 permission cards, which AgentSession does not contain.
        var after = 0L
        var through: Long? = null
        var epoch: String? = null
        var permissions = emptyList<JsonObject>()
        var questions = emptyList<JsonObject>()
        do {
            val page = link.client.request(wakuCommand("replayEvents") {
                put("epoch", primitive(epoch)); put("afterSequence", after); put("throughSequence", through?.let(::JsonPrimitive) ?: JsonNull); put("limit", 256)
            }, id, runtimeId)
            val boundary = WakuRecovery.validateReplayPage(page, runtimeId, id, after, through)
            val pageEpoch = page.s("epoch") ?: error("重放缺少 epoch")
            require(epoch == null || epoch == pageEpoch) { "分页期间服务器已重启" }
            if (epoch == null) {
                val oldEpoch = mutex.withLock { ensureCurrent(link); link.profile.snapshots[id]?.cursor?.s("epoch") }
                if (oldEpoch != null && oldEpoch != pageEpoch) {
                    val hydrated = link.client.request(wakuCommand("hydrateSession") { put("sessionId", id) })["session"] as? JsonObject ?: error("无法刷新重启后的会话")
                    mutex.withLock {
                        ensureCurrent(link)
                        val pending = link.profile.snapshots[id]?.pendingCommands.orEmpty()
                        putSnapshot(link.profile, id, WakuProjection.bindRuntime(WakuProjection.fromSession(hydrated).copy(pendingCommands = pending, remoteKnown = true), runtimeId), false)
                    }
                }
                epoch = pageEpoch; through = boundary
            }
            for (wire in page.objs("events")) {
                link.artifacts.onWireEvent(wire)
                link.toolApproval.onWireEvent(wire)
                val event = wire["event"] as? JsonObject ?: continue
                val payload = event["payload"] as? JsonObject
                when (event.s("kind")) {
                    "permission" -> if (payload != null) permissions = permissions.filterNot { it.s("requestId") == payload.s("requestId") } + payload
                    "userInputRequested" -> if (payload != null) questions = questions.filterNot { it.s("requestId") == payload.s("requestId") } + payload
                    "promptSubmitted", "turnStarted", "turnFinished", "processExited" -> { permissions = emptyList(); questions = emptyList() }
                    "permissionResolved" -> permissions = permissions.filterNot { it.s("requestId") == payload?.s("requestId") }
                    "userInputResolved" -> questions = questions.filterNot { it.s("requestId") == payload?.s("requestId") }
                }
                mutex.withLock { ensureCurrent(link); applyEvent(link, wire) }
            }
            after = page.n("nextSequence")
        } while (page.b("hasMore"))
        mutex.withLock {
            ensureCurrent(link)
            val p = link.profile
            val runtime = link.runtimes[id] ?: error("运行时已改变")
            runtime.epoch = epoch
            var snapshot = p.snapshots.getValue(id)
            if (snapshot.session.s("status") == "waiting") {
                val accepted = snapshot.pendingCommands.filter { it.s("state") == "accepted" }.mapNotNull { it.s("targetRequestId") }.toSet()
                snapshot = snapshot.copy(permissions = permissions.filterNot { it.s("requestId") in accepted }, userInputs = questions.filterNot { it.s("requestId") in accepted })
                putSnapshot(p, id, snapshot, false)
            }
            val buffered = runtime.buffered.toList()
            runtime.buffered.clear(); runtime.bytes = 0; runtime.catchingUp = false
            for (wire in buffered) {
                require(wire.s("epoch") == epoch) { "重放期间运行时发生变化" }
                applyEvent(link, wire)
            }
            flushLocal(p)
            publish(p)
            if (p.snapshots[id]?.session?.s("status") !in BUSY) scheduleArtifacts(link, id, runtimeId)
        }
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
        var snapshot = WakuProjection.reduce(previous, wire)
        if (snapshot === previous) return
        snapshot = WakuRecovery.resolveObservedCommands(snapshot)
        val eventKind = (wire["event"] as? JsonObject)?.s("kind")
        if (eventKind in setOf("turnFinished", "processExited")) {
            snapshot = snapshot.copy(pendingCommands = snapshot.pendingCommands.filter { it.s("kind") == "prompt" })
            link.checkpoint.trySend(Unit)
            if (link.runtimes[id]?.catchingUp == false) wire.s("runtimeId")?.let { scheduleArtifacts(link, id, it) }
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
        if (!link.artifactLoading.add(id)) return
        scope.launch {
            try {
                val files = link.artifacts.loadArtifacts(id, runtimeId)
                mutex.withLock {
                    if (active === link) { link.profile.artifacts[id] = files; link.profile.artifactErrors.remove(id); publish(link.profile) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutex.withLock {
                    if (active === link) { link.profile.artifactErrors[id] = "产物读取失败，点击刷新可重试：${error.message}"; publish(link.profile) }
                }
            } finally { mutex.withLock { link.artifactLoading.remove(id) } }
        }
    }

    private suspend fun checkpoint(link: Link) {
        val captured = mutex.withLock {
            ensureCurrent(link)
            link.profile.serverDirty.filter { it in link.profile.ready && it !in link.profile.syncing && it !in link.profile.blocked && it in link.profile.remoteIds }
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
            createDraft(p, id); p.selected = id
            if (_isConnected.value) p.ready += id
            publish(p)
        }
    }

    fun selectThread(id: String) = scope.launch {
        val link = mutex.withLock {
            val p = profile ?: return@launch
            p.selected = id; publish(p)
            active?.takeIf { it.profile === p }
        }
        if (!id.startsWith("legacy:") && link != null) operations.withLock {
            try { hydrateAttach(link, id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(link.profile, error.message ?: "同步失败") }
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
                        require(WakuCommandPolicy.canSubmitPrompt(target.snapshots.getValue(id).pendingCommands, requestId)) { "上一项提交尚未确认，本条消息未发送" }
                    }
                    submit(link, id, text, attachments, requestId, turnId, messageId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutex.withLock {
                    val old = target.snapshots[id]
                    if (old != null) {
                        val commands = old.pendingCommands.map { if (it.s("requestId") == requestId && it.s("state") == "prepared") it.patch("state" to JsonPrimitive("notSent")) else it }
                        putSnapshot(target, id, old.copy(pendingCommands = commands), false); publish(target)
                        runCatching { flushLocal(target) }
                    }
                }
                showError(target, error.message ?: "发送失败，原文已保留")
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
            val uploaded = uploadAttachments(link, attachments)
            var snapshot = mutex.withLock { ensureCurrent(link); link.profile.snapshots.getValue(id) }
            if (id !in link.profile.remoteIds) saveRemote(link, listOf(snapshot.session))
            var runtimeId = mutex.withLock { link.runtimes[id]?.id }
            if (runtimeId == null) {
                val attached = link.client.request(wakuCommand("attachSession"), id)
                runtimeId = attached.s("runtimeId")
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
                link.profile.snapshots.getValue(id).also { require(it.session.s("status") !in BUSY) { "服务器会话已开始执行；消息尚未发送" } }
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
            link.toolApproval.ensureFullAccess(id, requireNotNull(runtimeId))
            mutex.withLock {
                ensureCurrent(link)
                val latest = link.profile.snapshots.getValue(id)
                require(latest.session.s("status") !in BUSY) { "服务器会话已开始执行；消息尚未发送" }
                putSnapshot(link.profile, id, latest.copy(session = latest.session.patch(
                    "model" to desiredModel, "runtime_mode" to JsonPrimitive(WakuToolApproval.RUNTIME_MODE),
                )))
            }
            updatePending(link, id, requestId, "sending", uploaded)
            promptDispatched = true
            link.client.request(wakuCommand(if (uploaded.isEmpty()) "prompt" else "promptWithAttachments") {
                put("prompt", text.trim()); put("turnId", turnId); put("messageId", messageId)
                if (uploaded.isNotEmpty()) put("attachments", JsonArray(uploaded))
            }, id, requireNotNull(runtimeId), requestId)
            updatePending(link, id, requestId, "accepted", uploaded)
            link.checkpoint.trySend(Unit)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { updatePending(link, id, requestId, if (promptDispatched) "unknown" else "notSent") }
            throw cancelled
        } catch (error: Exception) {
            val state = if (!promptDispatched || (error is WakuRequestException && error.delivery == WakuDelivery.NOT_SENT)) "notSent"
                else if (error is WakuRequestException && error.delivery == WakuDelivery.REJECTED) "rejected" else "unknown"
            updatePending(link, id, requestId, state)
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
                runCatching { updatePending(p, id, requestId, state) }
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
                return@withLock
            }
            putSnapshot(p, p.selected, snapshot.copy(pendingCommands = WakuCommandPolicy.acknowledge(snapshot.pendingCommands)), false)
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
            p.store.delete(id); p.snapshots.remove(id); p.catalog.remove(id); p.remoteIds -= id; p.drafts -= id
            p.localDirty -= id; p.serverDirty -= id; p.ready -= id; p.blocked.remove(id); p.artifacts.remove(id); link.runtimes.remove(id)
            if (p.selected == id) { p.selected = UUID.randomUUID().toString(); createDraft(p, p.selected); p.ready += p.selected }
            publish(p)
        }
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
        val hadPrevious = snapshot.pendingCommands.isNotEmpty()
        putSnapshot(p, id, snapshot.copy(pendingCommands = snapshot.pendingCommands + command), false)
        publish(p)
        flushLocal(p) // Must complete before the first side-effecting request is sent.
        hadPrevious
    }

    private suspend fun updatePending(link: Link, id: String, requestId: String, state: String, attachments: List<JsonObject>? = null) =
        updatePending(link.profile, id, requestId, state, attachments)

    private suspend fun updatePending(p: Profile, id: String, requestId: String, state: String, attachments: List<JsonObject>? = null) = mutex.withLock {
        // An interrupted operation can still update its own profile cache, never the currently selected profile.
        val snapshot = p.snapshots[id] ?: return@withLock
        val commands = snapshot.pendingCommands.map { command ->
            if (command.s("requestId") != requestId) command else JsonObject(command.toMutableMap().apply {
                put("state", JsonPrimitive(state)); attachments?.let { put("uploadedAttachments", JsonArray(it)) }
            })
        }
        putSnapshot(p, id, snapshot.copy(pendingCommands = commands), false)
        if (profile === p) publish(p)
        flushLocal(p)
    }

    private suspend fun uploadAttachments(link: Link, files: List<PendingAttachment>): List<JsonObject> {
        require(files.size <= 8) { "每条消息最多添加 8 个附件" }
        var total = 0L
        return files.map { pending ->
            ensureCurrent(link)
            require(pending.sizeBytes == null || pending.sizeBytes <= 8L * 1024 * 1024) { "附件最大 8 MiB：${pending.displayName}" }
            val data = appContext.contentResolver.openInputStream(pending.uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var size = 0
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 8 * 1024 * 1024 + 1 - size))
                    if (count < 0) break
                    size += count
                    require(size <= 8 * 1024 * 1024 && total + size <= 16L * 1024 * 1024) { "附件超过 8 MiB 单文件或 16 MiB 总量限制" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: error("无法读取附件：${pending.displayName}")
            total += data.size
            val name = pending.displayName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "attachment" }
            val isImage = pending.mimeType?.startsWith("image/") == true
            val encoded = Base64.getEncoder().encodeToString(data)
            val result = if (isImage) link.client.request(wakuCommand("storeBlob") {
                put("mimeType", pending.mimeType!!); put("bytes", encoded)
            }, timeoutMs = 120_000) else link.client.request(wakuCommand("importAttachment") {
                put("name", name); put("upload", buildJsonObject { put("kind", "file"); put("data_base64", encoded) })
            }, timeoutMs = 120_000)
            val stored = if (isImage) result else result["attachment"] as? JsonObject ?: error("附件上传未返回引用")
            val path = stored.s("path") ?: error("附件缺少服务器路径")
            val reference = stored.s("reference") ?: error("附件缺少服务器引用")
            buildJsonObject {
                put("path", path); put("mention", path); put("name", name); put("is_dir", false); put("is_image", isImage); put("blob_reference", reference)
            }
        }
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
        _state.value = if (readOnly) legacyStates.getOrPut(p.selected) { readLegacy(p.selected) } else snapshot?.let { WakuProjection.display(it).appendArtifacts(p.artifacts[p.selected].orEmpty()) }
            ?: ConversationState(p.selected, status = "正在加载会话…")
        p.blocked[p.selected]?.let { _state.value = _state.value.copy(status = it) }
        if (p.selected in p.syncing) _state.value = _state.value.copy(status = "正在恢复事件与历史…")
        p.artifactErrors[p.selected]?.let { error ->
            _state.value = _state.value.upsert("waku:artifact-error", BlockKind.ERROR, "产物", error)
        }
        _pendingPermissions.value = snapshot?.permissions.orEmpty()
        _pendingQuestions.value = snapshot?.userInputs.orEmpty()
        _unknownSubmission.value = snapshot?.pendingCommands?.filter { it.s("kind") == "prompt" || it.s("state") in setOf("unknown", "notSent", "rejected") }
            ?.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { command ->
            val state = when (command.s("state")) {
                "notSent" -> "消息未发送，原文已保留"
                "rejected" -> "服务器拒绝了操作，原文已保留"
                "accepted" -> "服务器已接受，等待事件确认；没有自动重发"
                "prepared", "sending" -> "正在提交；中断后请核对服务器状态"
                else -> "操作结果未知，请先核对服务器；没有自动重发"
            }
            state + command.s("text")?.let { "\n$it" }.orEmpty()
        }
        _canSend.value = _isConnected.value && !readOnly && p.selected in p.ready && p.selected !in p.syncing &&
            p.selected !in p.blocked && snapshot != null && snapshot.session.s("status") !in BUSY && snapshot.pendingCommands.isEmpty()
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
