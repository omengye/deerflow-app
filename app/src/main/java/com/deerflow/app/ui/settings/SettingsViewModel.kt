package com.deerflow.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.data.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    val settings: StateFlow<AppSettings> =
        store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun save(endpoint: String, headersJson: String, initialStateJson: String) {
        viewModelScope.launch {
            store.save(AppSettings(endpoint, headersJson, initialStateJson))
        }
    }
}
