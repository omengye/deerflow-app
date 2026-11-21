package com.deerflow.app.data.waku

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** A failed transcript save must not keep resending or starve unrelated sessions. */
class WakuCheckpointWriter(private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Failure(val attempts: Int, val retryAfter: Long, val oversized: JsonObject?)
    private val failures = mutableMapOf<String, Failure>()

    suspend fun save(
        sessions: List<JsonObject>,
        persist: suspend (JsonObject) -> Unit,
        connected: () -> Boolean,
        report: suspend (String, Exception?) -> Unit,
    ) {
        for (session in sessions) {
            val id = session.string("id") ?: continue
            val previous = failures[id]
            if (previous?.oversized === session || previous != null && previous.oversized == null && clockMillis() < previous.retryAfter) continue
            try {
                persist(session)
                failures.remove(id)
                report(id, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val attempts = (previous?.attempts ?: 0) + 1
                val oversized = error is WakuRequestException && error.failure == WakuRequestFailure.TOO_LARGE
                failures[id] = Failure(attempts, clockMillis() + (2_000L shl (attempts - 1).coerceAtMost(4)).coerceAtMost(30_000),
                    session.takeIf { oversized })
                report(id, error)
                if (!connected()) throw error
            }
        }
    }

    fun forget(id: String) { failures.remove(id) }
}
