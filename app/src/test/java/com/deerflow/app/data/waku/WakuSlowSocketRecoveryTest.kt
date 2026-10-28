package com.deerflow.app.data.waku

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real OkHttp/WebSocket traffic: throttle bytes, then sever TCP halfway through a JSON frame. */
class WakuSlowSocketRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 90_000)
    fun longConversationSurvivesThreeMidFrameDisconnectsAt16KiBPerSecond() = runBlocking {
        val f = ReplayFixture
        val server = MockWebServer()
        server.start(InetAddress.getLoopbackAddress(), 0)
        val requests = Collections.synchronizedList(mutableListOf<Long>())
        val links = AtomicInteger()
        val proxy = SlowProxy(server.port)
        try {
            repeat(4) {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    var link = 0
                    var pages = 0
                    override fun onOpen(webSocket: WebSocket, response: Response) { link = links.incrementAndGet() }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val wire = WakuJson.parseToJsonElement(text).jsonObject
                        if (wire.string("type") == "hello") {
                            webSocket.send("""{"type":"hello","protocolVersion":10,"daemonVersion":"synthetic"}""")
                            return
                        }
                        val command = wire["command"]!!.jsonObject
                        check(command.string("type") == "replayEvents") // Recovery must never issue a prompt/mutation.
                        requests += command.long("afterSequence")
                        if (++pages == 2 && link <= 3) proxy.dropAfterBytes.set(1024)
                        webSocket.send(buildJsonObject {
                            put("type", "response"); put("requestId", wire["requestId"]!!)
                            put("outcome", buildJsonObject {
                                put("status", "ok"); put("payload", f.page(command, 100_512))
                            })
                        }.toString())
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                }))
            }
            val root = temporary.newFolder()
            val endpoint = "ws://127.0.0.1:${proxy.port}/v1"
            fun store() = WakuSessionStore(root, endpoint, "synthetic-token")
            val oldMessages = (1..10_000).map { n -> buildJsonObject {
                put("id", "old-$n"); put("role", if (n % 2 == 0) "assistant" else "user")
                put("content", "Synthetic archived message $n"); put("is_streaming", false)
            } }
            val initial = f.snapshot(100_000)
            store().save(initial.copy(session = initial.session.updated("messages" to JsonArray(oldMessages + initial.session.array("messages")))))
            val starts = mutableListOf<Long>()
            var failures = 0
            withTimeout(80_000) {
                repeat(4) {
                    var snapshot = store().load(f.session)!!
                    starts += snapshot.replayPosition()!!.sequence
                    val client = WakuClient(endpoint, "synthetic-token", UUID.randomUUID().toString())
                    try {
                        client.connect()
                        WakuReplayRunner().run(f.session, f.runtime, snapshot.replayPosition(),
                            request = { client.request(it, f.session, f.runtime) },
                            commit = { page -> snapshot = f.apply(snapshot, page); store().save(snapshot) })
                    } catch (_: WakuRequestException) { failures++ }
                    finally { client.close() }
                }
            }
            val final = store().load(f.session)!!
            assertEquals(3, failures)
            assertEquals(listOf(100_000L, 100_064L, 100_128L, 100_192L), starts)
            assertTrue(requests.all { it >= 100_000 })
            assertEquals(100_512L, final.replayPosition()!!.sequence)
            val messages = final.session.array("messages")
            assertEquals(10_002, messages.size)
            assertEquals(oldMessages, messages.take(10_000))
            assertEquals((100_001L..100_512L).joinToString("") { "[$it]" }, messages.last().jsonObject.string("content"))
            assertTrue("Historical transcript must not be downloaded again", proxy.transferred.get() < 400_000)
        } finally { proxy.close(); server.shutdown() }
    }

    private class SlowProxy(private val upstreamPort: Int) : Closeable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { work -> Thread(work, "test-slow-waku").apply { isDaemon = true } }
        private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        val port: Int get() = listener.localPort
        val dropAfterBytes = AtomicInteger(-1)
        val transferred = AtomicLong()

        init {
            workers.execute {
                while (!listener.isClosed) {
                    val client = runCatching { listener.accept() }.getOrNull() ?: break
                    val upstream = Socket(InetAddress.getLoopbackAddress(), upstreamPort)
                    sockets += client; sockets += upstream
                    workers.execute { pump(client, upstream, false) }
                    workers.execute { pump(upstream, client, true) }
                }
            }
        }

        private fun pump(from: Socket, to: Socket, slow: Boolean) {
            try {
                val buffer = ByteArray(if (slow) 256 else 4096)
                while (!from.isClosed && !to.isClosed) {
                    val count = from.getInputStream().read(buffer)
                    if (count < 0) break
                    if (slow) {
                        Thread.sleep(16) // At most 16 KiB/s, including fragmented WebSocket frames.
                        transferred.addAndGet(count.toLong())
                        if (dropAfterBytes.get() >= 0 && dropAfterBytes.addAndGet(-count) <= 0) {
                            dropAfterBytes.set(-1)
                            from.setSoLinger(true, 0); to.setSoLinger(true, 0)
                            break
                        }
                    }
                    to.getOutputStream().write(buffer, 0, count)
                    to.getOutputStream().flush()
                }
            } catch (_: Exception) {
                // Expected on the three injected handovers or test cleanup.
            } finally {
                runCatching { from.close() }; runCatching { to.close() }
                sockets.remove(from); sockets.remove(to)
            }
        }

        override fun close() {
            listener.close()
            synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            workers.shutdownNow(); workers.awaitTermination(3, TimeUnit.SECONDS)
        }
    }
}
