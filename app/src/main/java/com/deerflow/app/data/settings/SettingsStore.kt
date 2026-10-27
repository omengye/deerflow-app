package com.deerflow.app.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(
    name = "deerflow_settings",
    produceMigrations = { listOf(object : DataMigration<Preferences> {
        private val legacyKeys = listOf("token", "headers", "initial_state", "model_selections").map(::stringPreferencesKey)
        override suspend fun shouldMigrate(currentData: Preferences): Boolean = legacyKeys.any { currentData[it] != null }
        override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
            legacyKeys.forEach { remove(it) }
        }
        override suspend fun cleanUp() = Unit
    }) },
)

data class AppSettings(
    val endpoint: String = DEFAULT_ENDPOINT,
    val token: String = "",
    val workspace: String = "/workspaces",
    val modelName: String? = null,
    val needsReconfiguration: Boolean = false,
) {
    val validationError: String?
        get() {
            val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
            if (uri == null || uri.scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank() ||
                uri.userInfo != null || uri.fragment != null || uri.rawQuery != null || uri.path != "/v1") {
                return "请输入完整的 Waku 地址，例如 ws://10.0.2.2:18787/v1"
            }
            if (token.isBlank()) return "请输入服务器提供的连接 Token"
            if (workspace.isBlank()) return "请输入服务器工作目录"
            return null
        }
    val isConfigured: Boolean get() = !needsReconfiguration && validationError == null
    val connectionKey: String get() = endpoint.trim().removeSuffix("/") + "|" + workspace.trim()

    companion object {
        const val DEFAULT_ENDPOINT = "ws://10.0.2.2:18787/v1"
    }
}

/** Token plaintext exists only in memory; preferences contain an Android Keystore encrypted value. */
class SettingsStore(private val context: Context) {
    private val endpointKey = stringPreferencesKey("endpoint")
    private val tokenCipherKey = stringPreferencesKey("waku_token_encrypted_v1")
    private val workspaceKey = stringPreferencesKey("waku_workspace")
    private val modelKey = stringPreferencesKey("waku_model")
    private val configuredKey = stringPreferencesKey("waku_configured")
    private val oldTokenKey = stringPreferencesKey("token")
    private val oldHeadersKey = stringPreferencesKey("headers")
    private val oldStateKey = stringPreferencesKey("initial_state")
    private val oldModelsKey = stringPreferencesKey("model_selections")

    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val endpoint = prefs[endpointKey] ?: AppSettings.DEFAULT_ENDPOINT
        val cipher = prefs[tokenCipherKey].orEmpty()
        val decrypted = runCatching { if (cipher.isBlank()) "" else TokenCipher.decrypt(cipher) }
        AppSettings(
            endpoint = endpoint,
            token = decrypted.getOrDefault(""),
            workspace = prefs[workspaceKey] ?: "/workspaces",
            modelName = prefs[modelKey]?.takeIf(String::isNotBlank),
            needsReconfiguration = (prefs[endpointKey] != null && prefs[configuredKey] != "true") || decrypted.isFailure,
        )
    }

    suspend fun current(): AppSettings = flow.first()

    private fun memoryKey(endpoint: String) = stringPreferencesKey("memory_pending_" +
        java.security.MessageDigest.getInstance("SHA-256").digest(endpoint.trim().toByteArray())
            .joinToString("") { "%02x".format(it) })

    suspend fun pendingMemory(endpoint: String): com.deerflow.app.data.memory.MemoryOperation? {
        val cipher = context.dataStore.data.first()[memoryKey(endpoint)] ?: return null
        return com.deerflow.app.data.memory.MemoryJson.decodeFromString(TokenCipher.decrypt(cipher))
    }

    suspend fun savePendingMemory(endpoint: String, operation: com.deerflow.app.data.memory.MemoryOperation?) {
        val cipher = operation?.let { TokenCipher.encrypt(com.deerflow.app.data.memory.MemoryJson.encodeToString(
            com.deerflow.app.data.memory.MemoryOperation.serializer(), it)) }
        context.dataStore.edit { prefs ->
            if (cipher == null) prefs.remove(memoryKey(endpoint)) else prefs[memoryKey(endpoint)] = cipher
        }
    }

    suspend fun save(settings: AppSettings) {
        require(settings.validationError == null) { settings.validationError.orEmpty() }
        // Encrypt before editing: key errors must not commit a partially updated connection.
        val encrypted = settings.token.trim().takeIf(String::isNotBlank)?.let(TokenCipher::encrypt)
        context.dataStore.edit { prefs ->
            prefs[endpointKey] = settings.endpoint.trim()
            prefs[workspaceKey] = settings.workspace.trim()
            prefs[configuredKey] = "true"
            if (encrypted == null) prefs.remove(tokenCipherKey) else prefs[tokenCipherKey] = encrypted
            settings.modelName?.trim()?.takeIf(String::isNotBlank)?.let { prefs[modelKey] = it }
                ?: prefs.remove(modelKey)
            prefs.remove(oldTokenKey)
            prefs.remove(oldHeadersKey)
            prefs.remove(oldStateKey)
            prefs.remove(oldModelsKey)
        }
    }
}

private object TokenCipher {
    private const val ALIAS = "deerflow.waku.token.v1"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Token key is unavailable; enter the token again" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    @Synchronized fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}