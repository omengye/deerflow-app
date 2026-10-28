package com.deerflow.app.data.waku

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Selecting a session during reconnect joins its recovery, including its failure. */
class WakuSessionRecovery {
    private val mutex = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()

    suspend fun run(id: String, action: suspend () -> Unit) {
        val (result, owner) = mutex.withLock {
            pending[id]?.let { it to false } ?: (CompletableDeferred<Unit>() to true).also { pending[id] = it.first }
        }
        if (!owner) { result.await(); return }
        var failure: Throwable? = null
        try { action() }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            withContext(NonCancellable) {
                mutex.withLock { if (pending[id] === result) pending.remove(id) }
                if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
            }
        }
    }
}
