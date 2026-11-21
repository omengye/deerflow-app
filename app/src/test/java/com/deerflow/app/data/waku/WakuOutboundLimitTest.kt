package com.deerflow.app.data.waku

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WakuOutboundLimitTest {
    @Test fun oversizedSaveCannotPoisonRealOkHttpConnectionForNewPromptOrDeletion() = runBlocking {
        val server = MockWebServer()
        val received = CopyOnWriteArrayList<String>()
        val diagnostics = CopyOnWriteArrayList<WakuConnectionDiagnostic>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = WakuJson.parseToJsonElement(text).jsonObject
                if (message.string("type") == "hello") {
                    webSocket.send("""{"type":"hello","protocolVersion":12,"daemonVersion":"fixture"}""")
                } else {
                    received += message["command"]!!.jsonObject.string("type")!!
                    webSocket.send(buildJsonObject {
                        put("type", "response"); put("requestId", message["requestId"]!!)
                        put("outcome", buildJsonObject { put("status", "ok"); put("payload", wakuCommand("ack")) })
                    }.toString())
                }
            }
        }))
        server.start()
        val transport = OkHttpClient()
        val client = WakuClient(server.url("/v1").toString(), "fixture-private-token", UUID.randomUUID().toString(),
            transport, diagnostics = diagnostics::add)
        try {
            client.connect()
            val oversized = wakuCommand("saveTaskState") { put("sessions", buildJsonArray { add(buildJsonObject {
                put("id", UUID.randomUUID().toString()); put("content", "图".repeat(6 * 1024 * 1024))
            }) }) }
            val failure = runCatching { client.request(oversized) }.exceptionOrNull() as WakuRequestException
            assertEquals(WakuDelivery.NOT_SENT, failure.delivery)
            assertEquals(WakuRequestFailure.TOO_LARGE, failure.failure)
            assertEquals(WakuConnectionState.Connected, client.state.value)
            assertEquals("ack", client.request(wakuCommand("prompt") { put("prompt", "hi") }).string("type"))
            assertEquals("ack", client.request(wakuCommand("removeSession"), UUID.randomUUID().toString()).string("type"))
            assertEquals(listOf("prompt", "removeSession"), received)
            assertEquals("saveTaskState", diagnostics.last().commandType)
            assertTrue(diagnostics.last().requestBytes!! > 16 * 1024 * 1024)
            assertFalse(diagnostics.toString().contains("fixture-private-token"))
            assertFalse(diagnostics.toString().contains("图"))
        } finally {
            client.close()
            transport.dispatcher.executorService.shutdownNow()
            transport.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
