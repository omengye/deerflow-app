package com.deerflow.app.data.background

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class BackgroundConnectionState(
    val visible: Boolean = false,
    val serviceActive: Boolean = false,
    val graceActive: Boolean = false,
    val pauseReason: String? = null,
) {
    val keepConnected: Boolean get() = visible || (pauseReason == null && (serviceActive || graceActive))
}

/** UI visibility changes must not replace a live socket when retention is still allowed. */
class BackgroundConnectionPolicy(
    private val scope: CoroutineScope,
    private val idleGraceMillis: Long = 60_000,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(BackgroundConnectionState())
    private var graceJob: Job? = null
    private var generation = 0L
    val state = mutableState.asStateFlow()
    val keepConnected = state.map { it.keepConnected }.distinctUntilChanged()

    init { require(idleGraceMillis > 0) }

    fun setVisible(visible: Boolean) = synchronized(lock) {
        val old = mutableState.value
        if (old.visible == visible) return@synchronized
        val ticket = ++generation
        graceJob?.cancel()
        mutableState.value = if (visible) old.copy(visible = true, graceActive = false, pauseReason = null)
            else old.copy(visible = false, graceActive = old.pauseReason == null)
        if (!visible && old.pauseReason == null) {
            graceJob = scope.launch {
                delay(idleGraceMillis)
                synchronized(lock) {
                    if (generation == ticket) mutableState.value = mutableState.value.copy(graceActive = false)
                }
            }
        }
    }

    fun serviceStarted() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(serviceActive = true)
    }

    fun serviceStopped() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(serviceActive = false)
    }

    /** Pause only the subscription; execution/cancel commands are deliberately outside this policy. */
    fun pause(reason: String) = synchronized(lock) {
        ++generation
        graceJob?.cancel()
        mutableState.value = mutableState.value.copy(graceActive = false, pauseReason = reason)
    }
}

data class BackgroundWork(
    val runningTasks: Int = 0,
    val waitingTasks: Int = 0,
    val pendingOperations: Int = 0,
    val transmitting: Int = 0,
) {
    val hasWork: Boolean get() = runningTasks > 0 || pendingOperations > 0 || transmitting > 0
    val progressText: String get() = when {
        transmitting > 0 -> "正在提交消息或附件"
        waitingTasks > 0 -> "$waitingTasks 个任务等待授权或输入"
        runningTasks > 0 -> "正在同步 $runningTasks 个任务的执行进度"
        pendingOperations > 0 -> "正在核对任务状态，不会重复提交"
        else -> "任务状态已同步"
    }
}
