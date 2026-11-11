package com.deerflow.app.data.proposal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProposalTrigger(
    val type: String = "agent_request",
    @SerialName("thread_id")
    val threadId: String? = null,
    val summary: String = "",
)

@Serializable
data class SkillProposal(
    val id: String,
    val status: String,
    val action: String,
    @SerialName("skill_name")
    val skillName: String,
    @SerialName("file_path")
    val filePath: String? = null,
    val reason: String = "",
    val trigger: ProposalTrigger = ProposalTrigger(),
    val author: String = "agent",
    val origin: String = "manual_agent",
    @SerialName("base_revision")
    val baseRevision: Int? = null,
    @SerialName("base_sha256")
    val baseSha256: String? = null,
    @SerialName("candidate_sha256")
    val candidateSha256: String? = null,
    @SerialName("changed_files")
    val changedFiles: List<String> = emptyList(),
    val evaluation: SkillEvaluation = SkillEvaluation(),
    val lifecycle: CandidateLifecycle? = null,
    val target: SkillTarget? = null,
    val base: SkillHead? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("validation_failure") val validationFailure: JsonObject? = null,
    @SerialName("file_publication_pending") val filePublicationPending: Boolean = false,
    @SerialName("publication_error") val publicationError: String? = null,
    @SerialName("content_expired") val contentExpired: Boolean = false,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
    @SerialName("reviewed_at")
    val reviewedAt: String? = null,
    @SerialName("review_note")
    val reviewNote: String? = null,
    @SerialName("published_revision")
    val publishedRevision: Int? = null,
    val error: String? = null,
    @SerialName("archived_at")
    val archivedAt: String? = null,
    @SerialName("archived_by")
    val archivedBy: String? = null,
    val diff: String? = null,
    @SerialName("state_version") val stateVersion: Long = 0,
    @SerialName("script_review_required") val scriptReviewRequired: Boolean = false,
    val approval: JsonObject? = null,
    val publication: JsonObject? = null,
)

@Serializable
data class ProposalListResponse(
    val proposals: List<SkillProposal> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("catalog_version")
    val catalogVersion: Long? = null,
)

