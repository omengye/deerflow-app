package com.deerflow.app.data.proposal

import com.deerflow.app.data.settings.AppSettings
import com.deerflow.app.data.waku.WakuDelivery
import com.deerflow.app.data.waku.WakuConnectionException
import com.deerflow.app.data.waku.WakuConnectionFailure
import com.deerflow.app.data.waku.WakuRequestException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProposalRepositoryTest {
    private val settings = AppSettings(endpoint = "ws://localhost:18787/v1", token = "test-token")
    private class Api : ProposalService {
        var row = candidate().copy(diff = "full diff")
        var listing: suspend (String?) -> ProposalListResponse = { ProposalListResponse(listOf(row.copy(diff = null))) }
        var detail: suspend (String) -> SkillProposal = { row }
        var cancellation: suspend (SkillProposal) -> SkillProposal = { it.copy(status = "rejected") }
        val cursors = mutableListOf<String?>()
        var cancellations = 0
        override suspend fun listProposals(cursor: String?): ProposalListResponse { cursors += cursor; return listing(cursor) }
        override suspend fun getProposal(proposalId: String) = detail(proposalId)
        override suspend fun cancel(proposal: SkillProposal, note: String?): SkillProposal { cancellations++; return cancellation(proposal) }
    }

    @Test fun `refresh preserves old diff until fresh detail arrives and updates same version observation`() = runTest {
        val api = Api()
        val repository = ProposalRepository(MutableStateFlow(settings), backgroundScope) { api }
        runCurrent()
        repository.select(api.row.id); runCurrent()
        assertEquals("full diff", repository.state.value.selected!!.diff)
        val next = candidate(version = 8, status = "published", lifecycle = "observing").copy(diff = "new diff")
        api.row = next
        val response = CompletableDeferred<SkillProposal>()
        api.detail = { response.await() }
        repository.refresh(); runCurrent()
        assertEquals("full diff", repository.state.value.selected!!.diff)
        assertFalse(repository.state.value.detailFresh)
        response.complete(next); runCurrent()
        assertEquals("new diff", repository.state.value.selected!!.diff)
        val withdrawn = next.copy(lifecycle = next.lifecycle!!.copy(status = "withdrawn"))
        api.detail = { withdrawn }
        repository.refreshVisible(); runCurrent()
        assertEquals(EvolutionGroup.WITHDRAWN, repository.state.value.selected!!.evolutionGroup)
        assertEquals("new diff", repository.state.value.selected!!.diff)
    }

    @Test fun `failed detail load never permits cancellation from list summary`() = runTest {
        val api = Api().apply { detail = { throw IOException("offline") } }
        val repository = ProposalRepository(MutableStateFlow(settings), backgroundScope) { api }
        runCurrent(); repository.select("p-1"); runCurrent()
        assertFalse(repository.state.value.detailFresh)
        assertNotNull(repository.state.value.detailError)
        repository.cancelCandidate("p-1"); runCurrent()
        assertEquals(0, api.cancellations)
    }

    @Test fun `older detail cannot authorize cancelling a candidate updated in the list`() = runTest {
        val api = Api()
        val repository = ProposalRepository(MutableStateFlow(settings), backgroundScope) { api }
        runCurrent(); repository.select("p-1"); runCurrent()
        val old = api.row
        api.row = candidate(version = 8)
        api.detail = { old }
        repository.refresh(); runCurrent()
        assertFalse(repository.state.value.detailFresh)
        assertNotNull(repository.state.value.detailError)
        repository.cancelCandidate("p-1"); runCurrent()
        assertEquals(0, api.cancellations)
    }

    @Test fun `lost cancellation acknowledgement is only read back and is not replayed`() = runTest {
        val api = Api()
        val repository = ProposalRepository(MutableStateFlow(settings), backgroundScope) { api }
        runCurrent(); repository.select("p-1"); runCurrent()
        api.cancellation = {
            api.row = candidate(version = 8, status = "rejected", lifecycle = "rejected")
            throw WakuRequestException(WakuDelivery.RESULT_UNKNOWN, "request", "lost ack")
        }
        repository.cancelCandidate("p-1"); runCurrent()
        assertEquals(1, api.cancellations)
        assertEquals("rejected", repository.state.value.selected!!.status)
        assertTrue(repository.state.value.unconfirmedCancellations.isEmpty())
        repository.cancelCandidate("p-1"); runCurrent()
        assertEquals(1, api.cancellations)
    }

    @Test fun `unresolved cancellation blocks another mutation after refresh`() = runTest {
        val api = Api().apply { cancellation = { throw WakuRequestException(WakuDelivery.RESULT_UNKNOWN, "request", "lost ack") } }
        val repository = ProposalRepository(MutableStateFlow(settings), backgroundScope) { api }
        runCurrent(); repository.select("p-1"); runCurrent()
        repository.cancelCandidate("p-1"); runCurrent()
        repository.refreshDetail(); runCurrent()
        repository.cancelCandidate("p-1"); runCurrent()
        assertEquals(setOf("p-1"), repository.state.value.unconfirmedCancellations)
        assertEquals(1, api.cancellations)
    }

    @Test fun `handshake failure does not lock an unsent cancellation`() = runTest {
        val api = Api().apply { cancellation = { throw WakuConnectionException(WakuConnectionFailure.NETWORK, "offline") } }
        val repository = ProposalRepository(MutableStateFlow(settings), backgroundScope) { api }
        runCurrent(); repository.select("p-1"); runCurrent()
        repository.cancelCandidate("p-1"); runCurrent()
        assertTrue(repository.state.value.unconfirmedCancellations.isEmpty())
        assertTrue(repository.state.value.detailFresh)
        api.cancellation = { candidate(version = 8, status = "rejected", lifecycle = "rejected") }
        repository.cancelCandidate("p-1"); runCurrent()
        assertEquals(2, api.cancellations)
        assertEquals("rejected", repository.state.value.selected!!.status)
    }

    @Test fun `pages load on demand and history reading is not reset by polling`() = runTest {
        val api = Api().apply { listing = { cursor ->
            if (cursor == null) ProposalListResponse(listOf(candidate(version = 9)), "next")
            else ProposalListResponse(listOf(candidate(version = 8), candidate(id = "p-2")))
        } }
        val repository = ProposalRepository(MutableStateFlow(settings), backgroundScope) { api }
        runCurrent()
        assertEquals(listOf<String?>(null), api.cursors)
        repository.loadMore(); runCurrent()
        assertEquals(2, repository.state.value.proposals.size)
        assertEquals(9L, repository.state.value.proposals.first().stateVersion)
        repository.refreshVisible(); runCurrent()
        assertEquals(listOf(null, "next"), api.cursors)
    }

    @Test fun `repeated cursor does not loop or corrupt loaded records`() = runTest {
        val api = Api().apply { listing = { ProposalListResponse(listOf(candidate()), "same") } }
        val repository = ProposalRepository(MutableStateFlow(settings), backgroundScope) { api }
        runCurrent(); repository.loadMore(); runCurrent()
        assertNotNull(repository.state.value.listError)
        assertEquals(1, repository.state.value.loadedPages)
        assertEquals(2, api.cursors.size)
    }

    @Test fun `late response cannot reopen closed detail`() = runTest {
        val api = Api()
        val reply = CompletableDeferred<SkillProposal>()
        api.detail = { withContext(NonCancellable) { reply.await() } }
        val repository = ProposalRepository(MutableStateFlow(settings), backgroundScope) { api }
        runCurrent(); repository.select("p-1"); runCurrent()
        repository.clearSelection()
        reply.complete(api.row); runCurrent()
        assertNull(repository.state.value.selected)
        assertNull(repository.state.value.selectedId)
    }

    @Test fun `server switch drops old responses and resets pagination`() = runTest {
        val old = Api()
        val fresh = Api().apply { row = candidate(id = "p-new") }
        val reply = CompletableDeferred<ProposalListResponse>()
        old.listing = { withContext(NonCancellable) { reply.await() } }
        val profile = MutableStateFlow(settings)
        val repository = ProposalRepository(profile, backgroundScope) { if (it.token == settings.token) old else fresh }
        runCurrent()
        profile.value = settings.copy(token = "different-server")
        runCurrent()
        reply.complete(ProposalListResponse(listOf(candidate(id = "p-old")), "old-page")); runCurrent()
        assertEquals(listOf("p-new"), repository.state.value.proposals.map { it.id })
        assertNull(repository.state.value.nextCursor)
    }
}
