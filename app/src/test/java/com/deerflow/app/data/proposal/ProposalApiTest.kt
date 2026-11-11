package com.deerflow.app.data.proposal

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

internal fun candidate(id: String = "p-1", version: Long = 7, status: String = "validating", lifecycle: String = "evaluating") = SkillProposal(
    id = id, status = status, action = "create", skillName = "example", stateVersion = version, origin = "automatic",
    lifecycle = CandidateLifecycle(stateVersion = version, status = lifecycle, canCancel = status in setOf("validating", "pending_review")),
)

class ProposalApiTest {
    @Test fun `cancellation binds displayed version without any approval or script override`() {
        val request = proposalCancellationCommand(candidate().copy(scriptReviewRequired = true), " cancelled ")
        assertEquals("proposal.reject", request["operation"]!!.jsonPrimitive.content)
        assertEquals("7", request["expected_state_version"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, request["expected_base_sha256"])
        assertEquals("cancelled", request["note"]!!.jsonPrimitive.content)
        assertFalse(request.containsKey("acknowledge_unverified_scripts"))
    }

    @Test fun `legacy and mismatched lifecycle cannot authorize cancellation`() {
        val rows = listOf(candidate().copy(lifecycle = null), candidate().copy(stateVersion = 8),
            candidate(lifecycle = "historical"), candidate(status = "published", lifecycle = "observing"))
        rows.forEach {
            assertFalse(it.canCancel)
            assertThrows(IllegalArgumentException::class.java) { proposalCancellationCommand(it, null) }
        }
    }

    @Test fun `wire projection retains observation and actual validation results`() {
        val row = ProposalJson.decodeFromString<SkillProposal>("""
            {"id":"p-1","status":"published","action":"create","skill_name":"example","state_version":7,
             "evaluation":{"security":"passed","behavior":"not_run","isolated_scripts":false},
             "lifecycle":{"state_version":7,"status":"withdrawn","reason":"rolled_back","can_cancel":false,
               "observation":{"status":"rolled_back","successes":1,"failures":2,"rollback_revision":4}},
             "new_field":{},"file_publication_pending":false}
        """.trimIndent())
        assertEquals(EvolutionGroup.WITHDRAWN, row.evolutionGroup)
        assertEquals("已撤回", row.statusLabel)
        assertEquals(2, row.currentLifecycle!!.observation!!.failures)
        assertEquals("not_run", row.evaluation.behavior)
        assertFalse(row.evaluation.isolatedScripts)
        assertFalse(row.canCancel)
    }

    @Test fun `storage publication never implies stable adoption`() {
        val row = candidate(status = "published", lifecycle = "observing")
        assertEquals(EvolutionGroup.OBSERVING, row.evolutionGroup)
        assertEquals("已采用 · 观察中", row.statusLabel)
        assertEquals("已发布 · 状态待同步", row.copy(lifecycle = null).statusLabel)
        assertFalse(row.copy(lifecycle = null).isActive)
    }

    @Test fun `historical pending rows and failed candidates are not active approvals`() {
        val old = candidate(status = "pending_review", lifecycle = "historical")
        assertEquals(EvolutionGroup.HISTORY, old.evolutionGroup)
        assertFalse(old.isActive)
        assertFalse(old.canCancel)
        assertEquals("验证未通过", candidate(status = "failed", lifecycle = "rejected").statusLabel)
        assertEquals("已取消", candidate(status = "rejected", lifecycle = "rejected").copy(reviewedBy = "desktop").statusLabel)
    }

    @Test fun `pagination cursor is retained instead of draining every page`() {
        val page = ProposalJson.decodeFromString<ProposalListResponse>("""{"proposals":[],"next_cursor":"page2","catalog_version":2147483648}""")
        assertEquals("page2", page.nextCursor)
        assertEquals(2147483648L, page.catalogVersion)
    }
}
