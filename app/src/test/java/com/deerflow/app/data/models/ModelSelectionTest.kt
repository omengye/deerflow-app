package com.deerflow.app.data.models

import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.ui.settings.SettingsUiState
import org.junit.Assert.*
import org.junit.Test

class ModelSelectionTest {
    private fun configured() = AppSettings(token = "test-token")

    @Test fun `legacy HTTP endpoint cannot be used as a Waku connection`() {
        val legacy = configured().copy(endpoint = "http://10.0.2.2:8000/api/chat/agui", needsReconfiguration = true)
        assertFalse(legacy.isConfigured)
        assertFalse(SettingsUiState(draft = legacy, loaded = true).canSave)
        assertEquals("http://10.0.2.2:8000/api/chat/agui", legacy.endpoint)
    }

    @Test fun `explicit valid endpoint still requires saving migrated settings`() {
        val draft = configured().copy(needsReconfiguration = true)
        assertFalse(draft.isConfigured)
        assertTrue(SettingsUiState(draft = draft, loaded = true).canSave)
        assertTrue(draft.copy(needsReconfiguration = false).isConfigured)
    }

    @Test fun `Waku address rejects credentials query and wrong protocol path`() {
        for (endpoint in listOf("https://host/v1", "ws://user:secret@host/v1", "ws://host/v1?token=secret", "ws://host/v1#x", "ws://host/api", "ws:///v1")) {
            assertFalse(endpoint, configured().copy(endpoint = endpoint).isConfigured)
        }
        assertTrue(configured().copy(endpoint = "wss://example.test:443/v1").isConfigured)
        assertFalse(configured().copy(token = "").isConfigured)
        assertFalse(configured().copy(workspace = " ").isConfigured)
    }

    @Test fun `model ID is explicit and no REST catalog is required to save`() {
        val settings = configured().copy(modelName = "configured-server-model")
        assertTrue(SettingsUiState(draft = settings, loaded = true).canSave)
        assertEquals("configured-server-model", settings.modelName)
        assertNull(settings.copy(modelName = null).modelName)
        assertNotEquals(settings.connectionKey, settings.copy(workspace = "/other").connectionKey)
    }
}