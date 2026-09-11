package com.deerflow.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.data.settings.SettingsStore
import com.deerflow.app.data.models.AvailableModel
import com.deerflow.app.data.models.ModelApi
import com.deerflow.app.data.models.ModelApiException
import com.deerflow.app.data.models.resolveApiBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val draft: AppSettings = AppSettings(),
    val loaded: Boolean = false,
    val loadingModels: Boolean = false,
    val modelsLoaded: Boolean = false,
    val models: List<AvailableModel> = emptyList(),
    val modelError: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val saved: Boolean = false,
) {
    val unavailableModel: Boolean
        get() = modelsLoaded && draft.modelName != null && models.none { it.name == draft.modelName }
    val canSave: Boolean
        get() = loaded && !saving && !unavailableModel && (!loadingModels || draft.modelName == null) &&
            runCatching { resolveApiBaseUrl(draft.endpoint) }.isSuccess
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val api = ModelApi()
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state = mutableState.asStateFlow()
    private var modelJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val stored = store.current()
                mutableState.update { it.copy(draft = stored, loaded = true) }
                refreshModels()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(saveError = "无法读取设置，请返回后重试") }
            }
        }
    }

    fun endpointChanged(value: String) = editConnection { copy(endpoint = value) }
    fun tokenChanged(value: String) = editConnection { copy(token = value) }

    private fun editConnection(edit: AppSettings.() -> AppSettings) {
        if (state.value.saving) return
        mutableState.update { it.copy(draft = it.draft.edit(), saveError = null) }
        refreshModels(debounce = true)
    }

    fun initialStateChanged(value: String) {
        if (!state.value.saving) mutableState.update { it.copy(draft = it.draft.copy(initialStateJson = value), saveError = null) }
    }

    fun selectModel(name: String?) {
        if (state.value.saving) return
        runCatching { state.value.draft.withModel(name) }.onSuccess { draft ->
            mutableState.update { it.copy(draft = draft, saveError = null) }
        }
    }

    fun refreshModels(debounce: Boolean = false) {
        modelJob?.cancel()
        val connection = state.value.draft
        mutableState.update { it.copy(loadingModels = true, modelsLoaded = false, models = emptyList(), modelError = null) }
        modelJob = viewModelScope.launch {
            try {
                if (debounce) delay(500)
                val models = api.listModels(connection.endpoint, connection.headers())
                mutableState.update { it.copy(loadingModels = false, modelsLoaded = true, models = models) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when (e) {
                    is ModelApiException -> when (e.statusCode) {
                        401, 403 -> "认证失败，请检查 Token"
                        404 -> "服务器未提供模型列表，可使用后端默认模型"
                        else -> "模型列表加载失败（HTTP ${e.statusCode}），请重试"
                    }
                    is IllegalArgumentException -> "请输入有效的 HTTP 或 HTTPS 服务器地址"
                    else -> "模型列表加载失败，请检查网络或服务器后重试"
                }
                mutableState.update { it.copy(loadingModels = false, modelError = message) }
            }
        }
    }

    fun save() {
        if (!state.value.canSave) return
        val draft = state.value.draft
        mutableState.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                store.save(draft)
                mutableState.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(saving = false, saveError = "保存失败，请重试") }
            }
        }
    }
}
