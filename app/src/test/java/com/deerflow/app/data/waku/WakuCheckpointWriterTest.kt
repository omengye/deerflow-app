package com.deerflow.app.data.waku

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WakuCheckpointWriterTest {
    private fun session(id: String) = buildJsonObject { put("id", id) }

    @Test fun oversizedHistoryIsPausedWhileOtherSessionsSaveAndNormalizedHistoryCanResume() = runBlocking {
        val writer = WakuCheckpointWriter()
        val large = session("large")
        val small = session("small")
        val attempts = mutableListOf<String>()
        val persist: suspend (JsonObject) -> Unit = {
            attempts += it.string("id")!!
            if (it === large) throw WakuRequestException(WakuDelivery.NOT_SENT, "request", "too large", failure = WakuRequestFailure.TOO_LARGE)
        }
        repeat(3) { writer.save(listOf(large, small), persist, { true }) { _, _ -> } }
        assertEquals(listOf("large", "small", "small", "small"), attempts)
        writer.save(listOf(session("large")), persist, { true }) { _, error -> assertNull(error) }
        assertEquals("large", attempts.last())
    }

    @Test fun temporaryFailuresBackOffPerSessionInsteadOfBlockingAllSaves() = runBlocking {
        var now = 0L
        val writer = WakuCheckpointWriter { now }
        val items = listOf(session("retry"), session("healthy"))
        val attempts = mutableListOf<String>()
        val persist: suspend (JsonObject) -> Unit = {
            attempts += it.string("id")!!
            if (it.string("id") == "retry") error("server refused save")
        }
        writer.save(items, persist, { true }) { _, _ -> }
        now = 1_999
        writer.save(items, persist, { true }) { _, _ -> }
        assertEquals(listOf("retry", "healthy", "healthy"), attempts)
        now = 2_000
        writer.save(items, persist, { true }) { _, _ -> }
        assertEquals(listOf("retry", "healthy"), attempts.takeLast(2))
    }

    @Test fun disconnectedTransportStopsSavingOnTheRetiredSocket() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = runCatching { WakuCheckpointWriter().save(listOf(session("first"), session("second")), {
            attempts += it.string("id")!!
            error("connection lost")
        }, { false }) { _, _ -> } }
        assertTrue(result.isFailure)
        assertEquals(listOf("first"), attempts)
    }
}
