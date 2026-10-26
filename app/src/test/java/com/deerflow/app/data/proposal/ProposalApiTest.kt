package com.deerflow.app.data.proposal

import com.deerflow.app.data.waku.WakuJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ProposalApiTest {
    private fun proposal(script: Boolean = false) = SkillProposal(
        id = "p-1", status = "pending_review", action = "create", skillName = "example", stateVersion = 7,
        scriptReviewRequired = script,
    )

    @Test fun `review binds exact state and preserves explicit null base for new skill`() {
        val request = proposalReviewCommand(proposal(), "approve", " reviewed ")
        assertEquals("proposal.approve", request["operation"]!!.jsonPrimitive.content)
        assertEquals("7", request["expected_state_version"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, request["expected_base_sha256"])
        assertEquals("reviewed", request["note"]!!.jsonPrimitive.content)
        assertFalse(request.containsKey("acknowledge_unverified_scripts"))
    }

    @Test fun `script acknowledgment is tied to approval of the displayed candidate`() {
        assertEquals(JsonPrimitive(true), proposalReviewCommand(proposal(true), "approve", null)["acknowledge_unverified_scripts"])
        assertFalse(proposalReviewCommand(proposal(true), "reject", null).containsKey("acknowledge_unverified_scripts"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing state version cannot be submitted`() {
        proposalReviewCommand(proposal().copy(stateVersion = 0), "approve", null)
    }

    @Test fun `management projection tolerates additional fields and retains review version`() {
        val decoded = WakuJson.decodeFromString(SkillProposal.serializer(), """
            {"id":"p-1","status":"pending_review","action":"create","skill_name":"example",
            "state_version":7,"base_sha256":null,"script_review_required":true,"new_field":{},
            "approval":{"review":{"ExpectedStateVersion":6}},"publication":{"state":"approved_pending"}}
        """.trimIndent())
        assertEquals(7L, decoded.stateVersion)
        assertTrue(decoded.scriptReviewRequired)
        assertNotNull(decoded.approval)
        assertTrue(publicationNotice(decoded).contains("尚未完成"))
        assertFalse(publicationNotice(decoded).contains("已批准并发布"))
    }

    @Test fun `publication success requires published authoritative state`() {
        assertEquals("提案已批准并发布", publicationNotice(proposal().copy(publication = JsonObject(mapOf("state" to JsonPrimitive("published"))))))
        assertTrue(publicationNotice(proposal().copy(publication = JsonObject(mapOf("state" to JsonPrimitive("blocked"))))).contains("被阻止"))
    }
}