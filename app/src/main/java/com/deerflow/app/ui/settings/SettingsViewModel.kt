package com.deerflow.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val draft: AppSettings = AppSettings(),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
    val saved: Boolean = false,
) {
    val canSave: Boolean get() = loaded && !saving && draft.validationError == null
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                val stored = store.current()
                mutableState.update { it.copy(draft = stored, loaded = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(saveError = "无法读取设置，请返回后重试") } }
        }
    }
    private fun edit(transform: AppSettings.() -> AppSettings) {
        if (!state.value.saving) mutableState.update { it.copy(draft = it.draft.transform(), saveError = null) }
    }
    fun endpointChanged(value: String) = edit { copy(endpoint = value) }
    fun tokenChanged(value: String) = edit { copy(token = value) }
    fun workspaceChanged(value: String) = edit { copy(workspace = value) }
    fun selectModel(value: String?) = edit { copy(modelName = value?.takeIf(String::isNotBlank)) }
    fun save() {
        if (!state.value.canSave) return
        val draft = state.value.draft
        mutableState.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                store.save(draft)
                mutableState.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(saving = false, saveError = "保存失败，请重试") } }
        }
    }
}