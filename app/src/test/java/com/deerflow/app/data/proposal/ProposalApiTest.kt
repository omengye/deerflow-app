package com.deerflow.app.data.proposal

import com.deerflow.app.data.agui.AguiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProposalApiTest {
    @Test
    fun `resolves plain base endpoint`() {
        assertEquals(
            "http://10.0.2.2:8000/api/admin/evolution/proposals",
            resolveProposalBaseUrl("http://10.0.2.2:8000").toString().removeSuffix("/"),
        )
    }

    @Test
    fun `resolves AG UI and legacy agent endpoints`() {
        assertEquals(
            "https://example.test/root/api/admin/evolution/proposals",
            resolveProposalBaseUrl("https://example.test/root/api/chat/agui").toString().removeSuffix("/"),
        )
        assertEquals(
            "https://example.test/api/admin/evolution/proposals",
            resolveProposalBaseUrl("https://example.test/agent").toString().removeSuffix("/"),
        )
    }

    @Test
    fun `decodes proposal detail returned by admin API`() {
        val proposal = AguiJson.decodeFromString(
            SkillProposal.serializer(),
            """
                {
                  "id": "p_123",
                  "status": "pending_review",
                  "action": "patch",
                  "skill_name": "research-flow",
                  "trigger": {"type": "agent_request", "thread_id": "thread-1", "summary": "retry"},
                  "base_sha256": "abc",
                  "risk": "low",
                  "created_at": "2026-07-23T10:00:00Z",
                  "updated_at": "2026-07-23T10:00:00Z",
                  "diff": "-old\n+new"
                }
            """.trimIndent(),
        )

        assertEquals("research-flow", proposal.skillName)
        assertEquals("thread-1", proposal.trigger.threadId)
        assertEquals("abc", proposal.baseSha256)
        assertEquals("-old\n+new", proposal.diff)
        assertNull(proposal.reviewedAt)
    }
}

