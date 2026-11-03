package com.deerflow.app.data.waku

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakuClientTest {
    private val clientId = "10000000-0000-0000-0000-000000000001"
    private val requestId = "20000000-0000-0000-0000-000000000001"

    @Test fun defaultHandshakeBudgetAllowsDelayedAuthenticationBeyondOldFifteenSeconds() = runTest {
        val factory = FakeFactory()
        val events = mutableListOf<WakuConnectionDiagnostic>()
        val client = WakuClient("ws://example.test:18787/v1", "test-private-token", clientId, factory,
            clockMillis = { currentTime }, diagnostics = events::add)
        val connection = async { client.connect() }
        runCurrent()
        advanceTimeBy(10_000); factory.sockets.single().open()
        advanceTimeBy(15_000); factory.sockets.single().hello(); runCurrent()
        connection.await()
        assertEquals(WakuConnectionState.Connected, client.state.value)
        assertEquals(listOf("connecting", "authenticating", "connected"), events.map { it.stage })
        assertEquals(listOf(0L, 10_000L, 25_000L), events.map { it.elapsedMillis })
        assertFalse(events.toString().contains("test-private-token"))
        client.close()
    }

    @Test fun actualConnectedLifetimeResetsBackoffAfterStableSessionAndClosePreservesDuration() = runTest {
        val factory = FakeFactory()
        val client = WakuClient("ws://example.test:18787/v1", "test-private-token", clientId, factory,
            clockMillis = { currentTime })
        val attempts = mutableListOf<Int>()
        val supervisor = WakuConnectionSupervisor(retryDelay = { attempts += it; 1 })
        supervisor.awaitRetry(null); supervisor.awaitRetry(null)
        open(client, factory)
        advanceTimeBy(20_000)
        factory.sockets.single().failure()
        client.close()
        assertFalse(client.health.connected)
        assertEquals(20_000L, client.health.connectedForMillis)
        advanceTimeBy(5_000)
        assertEquals(20_000L, client.health.connectedForMillis)
        supervisor.awaitRetry(client.health.connectedForMillis)
        assertEquals(listOf(0, 1, 0), attempts)
    }

    @Test fun oldCallbacksCannotRefreshNewSocketHealth() = runTest {
        val factory = FakeFactory()
        val client = WakuClient("ws://example.test:18787/v1", "test-private-token", clientId, factory,
            clockMillis = { currentTime })
        open(client, factory)
        val old = factory.sockets.single()
        advanceTimeBy(1_000); old.failure()
        advanceTimeBy(1_000); open(client, factory)
        assertEquals(2_000L, client.health.lastReceivedAtMillis)
        advanceTimeBy(3_000)
        old.message("""{"type":"taskStateChanged","revision":1}""")
        assertEquals(2_000L, client.health.lastReceivedAtMillis)
        factory.sockets.last().message("""{"type":"taskStateChanged","revision":2}""")
        assertEquals(5_000L, client.health.lastReceivedAtMillis)
        client.close()
    }

    @Test fun handshakeTimeoutRemainsBoundedAndClassifiesWithoutExceptionDetails() = runTest {
        val factory = FakeFactory()
        val events = mutableListOf<WakuConnectionDiagnostic>()
        val client = WakuClient("ws://example.test:18787/v1", "test-private-token", clientId, factory,
            clockMillis = { currentTime }, diagnostics = events::add)
        val connection = async { runCatching { client.connect() } }
        runCurrent(); factory.sockets.single().open()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(WakuConnectionFailure.TIMEOUT, (connection.await().exceptionOrNull() as WakuConnectionException).kind)
        assertEquals("authenticating", events.last().stage)
        assertEquals(30_000L, events.last().elapsedMillis)
        assertTrue(factory.sockets.single().cancelled)
        client.close()
    }

    @Test
    fun normalizesOnlyAppServerEndpoints() {
        assertEquals("ws://example.test/v1", normalizeWakuEndpoint("http://example.test/"))
        assertEquals("wss://example.test:9443/v1", normalizeWakuEndpoint("https://example.test:9443/v1"))
        assertEquals("ws://[::1]:18787/v1", normalizeWakuEndpoint("ws://[::1]:18787"))
        for (url in listOf(
            "https://example.test/api/chat/agui", "https://example.test/agent",
            "https://example.test/v1/", "https://name:secret@example.test",
            "https://example.test?token=secret", "https://example.test/#fragment",
            "ftp://example.test", "example.test:18787", "https://example.test/%76%31",
        )) {
            assertTrue(url, runCatching { normalizeWakuEndpoint(url) }.isFailure)
        }
    }

    @Test
    fun handshakeAndRequestPayload() = runBlocking {
        val factory = FakeFactory()
        val client = client(factory)
        open(client, factory)
        val socket = factory.sockets.single()
        val hello = socket.sent.first().json()
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals("12", hello["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(clientId, hello["clientId"]!!.jsonPrimitive.content)
        assertEquals("[]", hello["resumeFrom"].toString())
        val result = async { client.request(wakuCommand("listTasks"), requestId = requestId) }
        yield()
        val wire = socket.sent.last().json()
        assertEquals("request", wire["type"]!!.jsonPrimitive.content)
        assertEquals(NIL_UUID, wire["sessionId"]!!.jsonPrimitive.content)
        assertEquals(requestId, wire["requestId"]!!.jsonPrimitive.content)
        assertEquals("listTasks", wire["command"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        socket.message("""{"type":"response","requestId":"$requestId","outcome":{"status":"ok","payload":{"type":"tasks","items":[]}}}""")
        assertEquals("tasks", result.await()["type"]!!.jsonPrimitive.content)
        assertEquals(2, socket.sent.size)
        client.close()
    }

    @Test
    fun authenticationAndProtocolRejectionsArePermanent() = runBlocking {
        for (reply in listOf(
            """{"type":"rejected","message":"invalid token"}""",
            """{"type":"hello","protocolVersion":10,"daemonVersion":"test"}""",
            """{"type":"hello","protocolVersion":11,"daemonVersion":"test"}""",
            """{"type":"hello","protocolVersion":13,"daemonVersion":"test"}""",
        )) {
            val factory = FakeFactory()
            val client = client(factory)
            val result = async { runCatching { client.connect() } }
            yield()
            val socket = factory.sockets.single()
            socket.open()
            socket.message(reply)
            val error = result.await().exceptionOrNull() as WakuConnectionException
            assertFalse(error.retryable)
            assertTrue(socket.cancelled)
            val expected = if (reply.contains("rejected")) WakuConnectionFailure.AUTHENTICATION else WakuConnectionFailure.PROTOCOL
            assertEquals(expected, error.kind)
            client.close()
        }
    }

    @Test
    fun handshakeTimeoutFencesOldCallbacks() = runBlocking {
        val factory = FakeFactory()
        val client = client(factory, handshakeTimeoutMs = 100)
        val first = async { runCatching { client.connect() } }
        yield()
        val old = factory.sockets.single()
        old.open()
        assertEquals(WakuConnectionFailure.TIMEOUT, (first.await().exceptionOrNull() as WakuConnectionException).kind)
        assertTrue(old.cancelled)

        val second = async { runCatching { client.connect() } }
        yield()
        val current = factory.sockets.last()
        old.hello()
        old.failure()
        old.listener.onClosed(old, 1000, "late close")
        assertEquals(WakuConnectionState.Connecting, client.state.value)
        current.open()
        current.hello()
        second.await().getOrThrow()
        old.message("""{"type":"taskStateChanged","revision":1}""")
        assertTrue(client.incoming.tryReceive().isFailure)
        assertEquals(WakuConnectionState.Connected, client.state.value)
        client.close()
    }

    @Test
    fun disconnectReleasesAllPendingWithoutResending() = runBlocking {
        val factory = FakeFactory()
        val client = client(factory)
        open(client, factory)
        val socket = factory.sockets.single()
        val first = async { runCatching { client.request(wakuCommand("prompt"), requestId = requestId) } }
        val second = async { runCatching { client.request(wakuCommand("cancel")) } }
        yield()
        socket.failure()
        for (result in listOf(first.await(), second.await())) {
            assertEquals(WakuDelivery.RESULT_UNKNOWN, (result.exceptionOrNull() as WakuRequestException).delivery)
        }
        assertEquals(3, socket.sent.size)
        open(client, factory)
        assertEquals(1, factory.sockets.last().sent.size)
        client.close()
    }

    @Test
    fun unconnectedAndUnqueuedRequestsAreNotSent() = runBlocking {
        val factory = FakeFactory()
        val client = client(factory)
        val disconnected = runCatching { client.request(wakuCommand("prompt")) }.exceptionOrNull() as WakuRequestException
        assertEquals(WakuDelivery.NOT_SENT, disconnected.delivery)
        open(client, factory)
        factory.sockets.single().acceptSends = false
        val unqueued = runCatching { client.request(wakuCommand("prompt")) }.exceptionOrNull() as WakuRequestException
        assertEquals(WakuDelivery.NOT_SENT, unqueued.delivery)
        assertEquals(1, factory.sockets.single().sent.size)
        client.close()
    }

    @Test
    fun requestTimeoutIsUnknownAndNeverRetried() = runBlocking {
        val factory = FakeFactory()
        val client = client(factory)
        open(client, factory)
        val socket = factory.sockets.single()
        val result = runCatching { client.request(wakuCommand("prompt"), requestId = requestId, timeoutMs = 20) }
        assertEquals(WakuDelivery.RESULT_UNKNOWN, (result.exceptionOrNull() as WakuRequestException).delivery)
        socket.message("""{"type":"response","requestId":"$requestId","outcome":{"status":"ok","payload":{"type":"ok"}}}""")
        assertEquals(2, socket.sent.size)
        assertEquals(WakuConnectionState.Connected, client.state.value)
        val nextId = "20000000-0000-0000-0000-000000000002"
        val next = async { client.request(wakuCommand("listTasks"), requestId = nextId) }
        yield()
        socket.message("""{"type":"response","requestId":"$nextId","outcome":{"status":"ok","payload":{"type":"ok"}}}""")
        next.await()
        assertEquals(3, socket.sent.size)
        client.close()
    }

    @Test
    fun explicitServerRejectionIsDistinct() = runBlocking {
        val factory = FakeFactory()
        val client = client(factory)
        open(client, factory)
        val result = async { runCatching { client.request(wakuCommand("prompt"), requestId = requestId) } }
        yield()
        factory.sockets.single().message(
            """{"type":"response","requestId":"$requestId","outcome":{"status":"error","error":{"message":"busy"}}}""",
        )
        val error = result.await().exceptionOrNull() as WakuRequestException
        assertEquals(WakuDelivery.REJECTED, error.delivery)
        assertEquals("busy", error.message)
        assertEquals(WakuConnectionState.Connected, client.state.value)
        client.close()
    }

    @Test
    fun eventOverflowDisconnectsWithObservableFailure() = runBlocking {
        val factory = FakeFactory()
        val client = client(factory, incomingCapacity = 1)
        open(client, factory)
        val socket = factory.sockets.single()
        socket.message("""{"type":"taskStateChanged","revision":1}""")
        socket.message("""{"type":"taskStateChanged","revision":2}""")
        val state = client.state.value as WakuConnectionState.Disconnected
        assertEquals(WakuConnectionFailure.OVERFLOW, state.failure!!.kind)
        assertTrue(socket.cancelled)
        assertEquals("1", client.incoming.receive()["revision"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun malformedResponseFailsPendingRequests() = runBlocking {
        val factory = FakeFactory()
        val client = client(factory)
        open(client, factory)
        val result = async { runCatching { client.request(wakuCommand("prompt"), requestId = requestId) } }
        yield()
        factory.sockets.single().message("""{"type":"response","requestId":"$requestId","outcome":{"status":"ok"}}""")
        assertEquals(WakuDelivery.RESULT_UNKNOWN, (result.await().exceptionOrNull() as WakuRequestException).delivery)
        assertEquals(WakuConnectionFailure.PROTOCOL, (client.state.value as WakuConnectionState.Disconnected).failure!!.kind)
        client.close()
    }

    @Test
    fun closeReleasesRequestAndClosesChannel() = runBlocking {
        val factory = FakeFactory()
        val client = client(factory)
        open(client, factory)
        val result = async { runCatching { client.request(wakuCommand("prompt")) } }
        yield()
        client.close()
        assertEquals(WakuDelivery.RESULT_UNKNOWN, (result.await().exceptionOrNull() as WakuRequestException).delivery)
        assertTrue(client.incoming.receiveCatching().isClosed)
        assertEquals(WakuConnectionFailure.ABORTED, (runCatching { client.connect() }.exceptionOrNull() as WakuConnectionException).kind)
    }

    private fun client(factory: FakeFactory, handshakeTimeoutMs: Long = 1_000, incomingCapacity: Int = 64) =
        WakuClient("ws://example.test:18787/v1", "test-private-token", clientId, factory, handshakeTimeoutMs, incomingCapacity)

    private suspend fun open(client: WakuClient, factory: FakeFactory) = coroutineScope {
        val result = async { client.connect() }
        yield()
        factory.sockets.last().open()
        factory.sockets.last().hello()
        result.await()
    }

    private fun String.json() = WakuJson.parseToJsonElement(this).jsonObject

    private class FakeFactory : WebSocket.Factory {
        val sockets = mutableListOf<FakeSocket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request, listener).also(sockets::add)
    }

    private class FakeSocket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        var acceptSends = true
        var cancelled = false
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (!acceptSends || cancelled) return false
            sent += text
            return true
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
        fun open() = listener.onOpen(
            this,
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build(),
        )
        fun hello() = message("""{"type":"hello","protocolVersion":12,"daemonVersion":"test"}""")
        fun message(text: String) = listener.onMessage(this, text)
        fun failure() = listener.onFailure(this, IOException("network unavailable"), null)
    }
}
