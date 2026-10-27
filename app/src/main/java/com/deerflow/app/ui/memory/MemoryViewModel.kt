package com.deerflow.app.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deerflow.app.data.memory.*
import com.deerflow.app.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryUiState(
    val page: MemoryPage? = null,
    val busy: Boolean = false,
    val pending: Boolean = false,
    val message: String? = null,
)

class MemoryViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val mutableState = MutableStateFlow(MemoryUiState())
    val state = mutableState.asStateFlow()
    fun refresh() = perform(null)
    fun update(fact: PersonalMemory, content: String) {
        val page = state.value.page ?: return
        if (content.isBlank() || content.length > 512) return
        perform(MemoryOperation(page.profileId, action = "remember", key = fact.key,
            content = content.trim(), category = fact.category, expectedRevision = fact.revision))
    }
    fun forget(fact: PersonalMemory) {
        val page = state.value.page ?: return
        perform(MemoryOperation(page.profileId, action = "forget", key = fact.key,
            category = fact.category, expectedRevision = fact.revision))
    }
    private fun perform(operation: MemoryOperation?) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, message = if (operation == null) null else "正在保存…") }
        viewModelScope.launch {
            try {
                val config = settings.current()
                val api = MemoryApi(config)
                val recovery = MemoryRecovery(api,
                    readPending = { settings.pendingMemory(config.endpoint) },
                    savePending = { settings.savePendingMemory(config.endpoint, it) })
                var receipt: MemoryReceipt? = null
                var error: Exception? = null
                for (attempt in 0..2) {
                    try {
                        receipt = if (attempt == 0 && operation != null) recovery.submit(operation) else recovery.reconcile()
                        error = null
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        error = failure
                        val pending = settings.pendingMemory(config.endpoint) != null
                        mutableState.update { it.copy(pending = pending, message = if (pending) "正在核对保存结果…" else "正在读取记忆…") }
                        if (failure is MemoryApiException || !pending) break
                        if (attempt < 2) delay(1_000L shl attempt)
                    }
                }
                val pending = settings.pendingMemory(config.endpoint) != null
                if (error != null) {
                    mutableState.update { it.copy(pending = pending, message =
                        if (pending) "连接暂时不可用，保存结果待核对。恢复网络后点击重新核对。"
                        else if (error is MemoryApiException && error.code == "memory_conflict") "记忆已被其他会话修改，请刷新后再编辑。"
                        else "暂时无法读取记忆，请检查连接后重试。") }
                } else {
                    // Keep a confirmed receipt visible even if the following list refresh fails.
                    mutableState.update { it.copy(pending = false, message = receipt?.let {
                        when (it.status) { "forgotten" -> "已忘记"; "updated" -> "已更新"; "unchanged" -> "已保存"; else -> "已保存" }
                    }) }
                    val page = api.list()
                    mutableState.update { it.copy(page = page) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(message = it.message ?: "暂时无法连接服务器，请重试。") }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
}
