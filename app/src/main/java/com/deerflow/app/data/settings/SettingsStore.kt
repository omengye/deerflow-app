package com.deerflow.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deerflow.app.data.agui.AguiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "deerflow_settings")

/** User-configurable connection settings. Port of internal/config/config.go. */
data class AppSettings(
    val endpoint: String = DEFAULT_ENDPOINT,
    val headersJson: String = "",
    val initialStateJson: String = "",
) {
    /** Parse [headersJson] into a header map, ignoring malformed input. */
    fun headers(): Map<String, String> {
        if (headersJson.isBlank()) return emptyMap()
        return runCatching {
            AguiJson.parseToJsonElement(headersJson).jsonObject.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.let { k to it.content }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Parse [initialStateJson] into a JSON object, ignoring malformed input. */
    fun initialState(): JsonObject {
        if (initialStateJson.isBlank()) return JsonObject(emptyMap())
        return runCatching { AguiJson.parseToJsonElement(initialStateJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://10.0.2.2:8000" // host loopback from emulator
    }
}

class SettingsStore(private val context: Context) {
    private val endpointKey = stringPreferencesKey("endpoint")
    private val headersKey = stringPreferencesKey("headers")
    private val initialStateKey = stringPreferencesKey("initial_state")

    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            endpoint = prefs[endpointKey]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_ENDPOINT,
            headersJson = prefs[headersKey].orEmpty(),
            initialStateJson = prefs[initialStateKey].orEmpty(),
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[endpointKey] = settings.endpoint.trim()
            prefs[headersKey] = settings.headersJson.trim()
            prefs[initialStateKey] = settings.initialStateJson.trim()
        }
    }
}
