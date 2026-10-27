package com.deerflow.app.data.memory

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryRecoveryTest {
    private class FakeApi : PersonalMemoryApi {
        var profile = "profile-one"
        var saved: MemoryReceipt? = null
        var lostReply = false
        var failBeforeCommit = false
        var conflict = false
        var calls = 0
        val operationIds = mutableListOf<String>()
        override suspend fun list() = MemoryPage(profileId = profile)
        override suspend fun receipt(id: String) = saved?.takeIf { it.id == id }
        override suspend fun mutate(operation: MemoryOperation): MemoryReceipt {
            calls++
            operationIds += operation.id
            if (conflict) throw MemoryApiException("memory_conflict", "changed")
            if (failBeforeCommit) throw IOException("offline")
            val receipt = MemoryReceipt(operation.id, "updated", "fact", 2, operation.key, "global")
            saved = receipt
            if (lostReply) throw IOException("reply lost after commit")
            return receipt
        }
    }
    private fun operation() = MemoryOperation("profile-one", id = "operation-one",
        action = "remember", key = "profile.last_reported_location", content = "用户在上海", expectedRevision = 1)

    @Test fun lostReplyIsRecoveredWithoutDuplicateMutation() = runBlocking {
        val api = FakeApi().apply { lostReply = true }
        var pending: MemoryOperation? = null
        val recovery = MemoryRecovery(api, { pending }, { pending = it })
        try { recovery.submit(operation()); fail("expected disconnect") } catch (_: IOException) {}
        assertEquals(operation(), pending)
        // A new coordinator simulates screen/process recreation with persisted pending data.
        val reopened = MemoryRecovery(api, { pending }, { pending = it })
        assertEquals("operation-one", reopened.reconcile()?.id)
        assertEquals(1, api.calls)
        assertNull(pending)
    }
    @Test fun notCommittedResubmitsTheSameOperationId() = runBlocking {
        val api = FakeApi().apply { failBeforeCommit = true }
        var pending: MemoryOperation? = null
        val recovery = MemoryRecovery(api, { pending }, { pending = it })
        try { recovery.submit(operation()); fail("expected disconnect") } catch (_: IOException) {}
        api.failBeforeCommit = false
        recovery.reconcile()
        assertEquals(listOf("operation-one", "operation-one"), api.operationIds)
        assertNull(pending)
    }
    @Test fun profileChangeCannotReplayIntoAnotherInstallation() = runBlocking {
        val api = FakeApi().apply { profile = "another-profile" }
        var pending: MemoryOperation? = operation()
        val recovery = MemoryRecovery(api, { pending }, { pending = it })
        try { recovery.reconcile(); fail("expected profile mismatch") } catch (_: IllegalStateException) {}
        assertEquals(0, api.calls)
        assertNotNull(pending)
    }
    @Test fun revisionConflictRequiresFreshUserEdit() = runBlocking {
        val api = FakeApi().apply { conflict = true }
        var pending: MemoryOperation? = null
        val recovery = MemoryRecovery(api, { pending }, { pending = it })
        try { recovery.submit(operation()); fail("expected conflict") } catch (error: MemoryApiException) {
            assertEquals("memory_conflict", error.code)
        }
        assertNull(pending)
    }
    @Test fun pendingOperationCannotBeOverwritten() = runBlocking {
        val api = FakeApi()
        var pending: MemoryOperation? = operation()
        val recovery = MemoryRecovery(api, { pending }, { pending = it })
        try { recovery.submit(operation().copy(id = "different")); fail("expected pending") } catch (_: IllegalStateException) {}
        assertEquals("operation-one", pending?.id)
        assertEquals(0, api.calls)
    }
}
