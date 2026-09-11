package com.deerflow.app.data.models

import com.deerflow.app.data.RunSession
import com.deerflow.app.data.RunSessionStore
import com.deerflow.app.data.agui.AguiClient
import com.deerflow.app.data.agui.AguiJson
import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.domain.model.ChatMessage
import com.deerflow.app.ui.settings.SettingsUiState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelSelectionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun http(status: Int = 200, body: String, capture: (Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            capture(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun `endpoint aliases share selection while different servers and prefixes stay separate`() {
        val settings = AppSettings(endpoint = "https://example.test/root/").withModel("model-a")
        assertEquals("model-a", settings.copy(endpoint = "https://example.test/root/api/chat/agui").modelName)
        assertEquals("model-a", settings.copy(endpoint = "https://example.test/root/api/").modelName)
        assertNull(settings.copy(endpoint = "https://other.test/root").modelName)
        assertNull(settings.copy(endpoint = "https://example.test/other").modelName)
        val second = settings.copy(endpoint = "https://other.test").withModel("model-b")
        assertEquals("model-a", second.copy(endpoint = settings.endpoint).modelName)
        assertEquals("model-b", second.modelName)
        assertNull(second.withModel(null).modelName)
        assertEquals("model-a", second.withModel(null).copy(endpoint = settings.endpoint).modelName)
    }

    @Test fun `legacy agent URL resolves API catalog without query or fragment`() {
        assertEquals("https://example.test/api", resolveApiBaseUrl("https://example.test/agent?x=1#fragment").toString())
    }

    @Test fun `catalog request uses edited server and bearer token with safe display fallback`() = runBlocking {
        var request: Request? = null
        val client = http(body = """{"models":[{"name":"a","display_name":null,"supports_vision":true},{"name":"b","display_name":"Friendly","supports_thinking":true}]}""") { request = it }
        val result = ModelApi(client).listModels("https://example.test/prefix/api/chat/agui", mapOf("Authorization" to "Bearer test-token"))
        assertEquals("https://example.test/prefix/api/models", request!!.url.toString())
        assertEquals("Bearer test-token", request!!.header("Authorization"))
        assertEquals("a", result[0].label)
        assertTrue(result[0].supportsVision)
        assertEquals("Friendly", result[1].label)
        assertTrue(result[1].supportsThinking)
    }

    @Test fun `catalog authentication failure is distinguishable`() = runBlocking {
        try {
            ModelApi(http(401, "unauthorized")).listModels("https://example.test", emptyMap())
            fail("Expected authentication failure")
        } catch (e: ModelApiException) {
            assertEquals(401, e.statusCode)
        }
    }

    @Test fun `new sends use explicit models and default omits modelName on wire`() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        val client = AguiClient("https://example.test", emptyMap(), JsonObject(emptyMap()),
            http(body = "data: {\"type\":\"RUN_FINISHED\"}\n\n") { request ->
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                payloads += AguiJson.parseToJsonElement(buffer.readUtf8()).jsonObject
            })
        val history = listOf(ChatMessage("user", JsonPrimitive("hello"), "user-1"))
        client.runStream("thread-1", "run-1", history, modelName = "model-a").toList()
        client.runStream("thread-1", "run-2", history, modelName = "model-b").toList()
        client.runStream("thread-1", "run-3", history).toList()
        assertEquals("model-a", payloads[0]["modelName"]!!.jsonPrimitive.content)
        assertEquals("model-b", payloads[1]["modelName"]!!.jsonPrimitive.content)
        assertFalse(payloads[2].containsKey("modelName"))
        assertTrue(payloads.all { it["threadId"]!!.jsonPrimitive.content == "thread-1" })
    }

    @Test fun `persisted run keeps original model for reconnect after settings change`() = runBlocking {
        val store = RunSessionStore(temporary.newFile())
        val original = AppSettings().withModel("model-a")
        store.save(listOf(RunSession("thread-1", "run-1", modelName = original.modelName,
            lastEventId = "event-1", reconnectUntilEpochMillis = 100, updatedAtEpochMillis = 10)))
        val updated = original.withModel("model-b")
        val restored = store.load().single()
        assertEquals("model-b", updated.modelName)
        var request: Request? = null
        val client = AguiClient(updated.endpoint, emptyMap(), JsonObject(emptyMap()),
            http(body = "data: {\"type\":\"RUN_FINISHED\"}\n\n") { request = it })
        client.runStream(restored.threadId, restored.runId, restored.history,
            lastEventId = restored.lastEventId, modelName = restored.modelName).toList()
        val buffer = Buffer()
        request!!.body!!.writeTo(buffer)
        assertEquals("model-a", AguiJson.parseToJsonElement(buffer.readUtf8()).jsonObject["modelName"]!!.jsonPrimitive.content)
        assertEquals("event-1", request!!.header("Last-Event-ID"))
    }

    @Test fun `old run metadata loads with unspecified model`() {
        val file = temporary.newFile()
        file.writeText("""{"sessions":[{"threadId":"t","runId":"r","reconnectUntilEpochMillis":100,"updatedAtEpochMillis":10}]}""")
        assertNull(RunSessionStore(file).load().single().modelName)
    }

    @Test fun `removed selection prevents save until default or valid model chosen`() {
        val state = SettingsUiState(draft = AppSettings().withModel("removed"), loaded = true,
            modelsLoaded = true, models = listOf(AvailableModel("valid")))
        assertTrue(state.unavailableModel)
        assertFalse(state.canSave)
        assertTrue(state.copy(draft = state.draft.withModel(null)).canSave)
        assertTrue(state.copy(draft = state.draft.withModel("valid")).canSave)
        assertFalse(state.copy(draft = state.draft.copy(endpoint = "invalid")).canSave)
    }
}
