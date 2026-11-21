package com.deerflow.app.data.waku

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakuSessionDeletionTest {
    private class Fixture {
        var pending = emptyList<JsonObject>()
        var localHistory = true
        var draft = "保留我的草稿"
        var deleteRequests = 0
        var failJournal = false
        var prepare: suspend () -> Unit = {}
        var reply: suspend (String, Long) -> JsonObject = { _, _ -> wakuCommand("ack") }
        private val deletion = WakuSessionDeletion(
            prepare = { prepare() },
            record = { pending = pending + it; check(!failJournal) { "磁盘写入失败" } },
            settle = { id, state, message ->
                pending = pending.mapNotNull {
                    if (it.string("requestId") != id) it
                    else state?.let { value -> it.updated("state" to JsonPrimitive(value), "error" to JsonPrimitive(message)) }
                }
            },
            request = { id, timeout -> deleteRequests++; reply(id, timeout) },
        )
        suspend fun delete() {
            deletion.remove()
            localHistory = false
            draft = ""
            pending = emptyList()
        }
    }

    @Test fun deletionKeepsHistoryAndDraftUntilServerAcknowledges() = runTest {
        val f = Fixture()
        var prepared = false
        val acknowledgement = CompletableDeferred<Unit>()
        f.prepare = { prepared = true }
        f.reply = { _, timeout ->
            assertTrue(prepared)
            assertEquals("sending", f.pending.single().string("state"))
            assertTrue(timeout > 35_000)
            acknowledgement.await()
            wakuCommand("ack")
        }
        val deleting = async { f.delete() }
        runCurrent()
        assertEquals(1, f.deleteRequests)
        assertTrue(f.localHistory)
        assertEquals("保留我的草稿", f.draft)
        acknowledgement.complete(Unit)
        deleting.await()
        assertEquals(1, f.deleteRequests)
        assertFalse(f.localHistory)
        assertEquals("", f.draft)
    }

    @Test fun actualBusyRefusalPreservesHistoryAndDraftWithoutLeavingAnUnknownCommand() = runTest {
        val f = Fixture()
        f.reply = { id, _ -> throw WakuRequestException(WakuDelivery.REJECTED, id, "会话仍有运行或等待中的任务，请先完成或停止任务后再删除") }
        assertTrue(runCatching { f.delete() }.exceptionOrNull() is WakuRequestException)
        assertTrue(f.localHistory)
        assertEquals("保留我的草稿", f.draft)
        assertTrue(f.pending.isEmpty())
        assertEquals(1, f.deleteRequests)
    }

    @Test fun preparationFailureNeverSendsDeleteOrClearsDraft() = runTest {
        val f = Fixture()
        f.prepare = { error("本地快照不可用") }
        assertTrue(runCatching { f.delete() }.isFailure)
        assertEquals(0, f.deleteRequests)
        assertTrue(f.pending.isEmpty())
        assertTrue(f.localHistory)
        assertEquals("保留我的草稿", f.draft)
    }

    @Test fun journalFailureBeforeDispatchDoesNotLeaveAPermanentSendingCommand() = runTest {
        val f = Fixture()
        f.failJournal = true
        assertTrue(runCatching { f.delete() }.isFailure)
        assertEquals(0, f.deleteRequests)
        assertTrue(f.pending.isEmpty())
        assertTrue(f.localHistory)
    }

    @Test fun unknownOutcomeAndUnexpectedReplyRetainDeletionForRecovery() = runTest {
        for (mode in listOf("timeout", "invalid-ack", "native-unknown")) {
            val f = Fixture()
            f.reply = { id, _ -> when (mode) {
                "timeout" -> throw WakuRequestException(WakuDelivery.RESULT_UNKNOWN, id, "响应超时")
                "native-unknown" -> throw WakuRequestException(WakuDelivery.REJECTED, id, "删除结果尚未确认，请重试删除")
                else -> wakuCommand("taskStateSaved")
            } }
            assertTrue(runCatching { f.delete() }.isFailure)
            assertTrue(f.localHistory)
            assertEquals("保留我的草稿", f.draft)
            assertEquals("unknown", f.pending.single().string("state"))
            assertFalse(f.pending.single().string("error").isNullOrBlank())
            assertEquals(1, f.deleteRequests)
        }
    }

    @Test fun cancellationAfterDispatchRetainsAnUncertainDeletion() = runTest {
        val f = Fixture()
        f.reply = { _, _ -> CompletableDeferred<JsonObject>().await() }
        val deleting = async { f.delete() }
        runCurrent()
        deleting.cancelAndJoin()
        assertEquals("unknown", f.pending.single().string("state"))
        assertTrue(f.localHistory)
        assertEquals("保留我的草稿", f.draft)
    }

    @Test fun unsentRetryCannotEraseAnEarlierUncertainDeletion() = runTest {
        val f = Fixture()
        val old = buildJsonObject { put("kind", "removeSession"); put("requestId", "old"); put("state", "unknown") }
        f.pending = listOf(old)
        f.reply = { id, _ -> throw WakuRequestException(WakuDelivery.NOT_SENT, id, "网络未连接") }
        assertTrue(runCatching { f.delete() }.isFailure)
        assertEquals(listOf(old), f.pending)
        assertTrue(f.localHistory)
    }
}
