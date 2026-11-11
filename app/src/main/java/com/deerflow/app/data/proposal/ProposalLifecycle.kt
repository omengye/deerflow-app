package com.deerflow.app.data.proposal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SkillTarget(
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("source_identity") val sourceIdentity: String = "",
    val name: String = "",
    val scope: String = "",
)

@Serializable
data class SkillHead(val version: String = "", val exists: Boolean = false)

@Serializable
data class SkillEvaluation(
    val security: String = "",
    val behavior: String = "",
    @SerialName("isolated_scripts") val isolatedScripts: Boolean = false,
    @SerialName("validator_version") val validatorVersion: String = "",
    val evidence: List<String> = emptyList(),
    @SerialName("model_checks") val modelChecks: List<SkillModelCheck> = emptyList(),
)

@Serializable
data class SkillModelCheck(val stage: String = "", val passed: Boolean = false, val reason: String = "")

@Serializable
data class CandidateLifecycle(
    @SerialName("state_version") val stateVersion: Long = 0,
    val status: String = "",
    val reason: String = "",
    @SerialName("can_cancel") val canCancel: Boolean = false,
    @SerialName("automatic_retry") val automaticRetry: Boolean = false,
    @SerialName("job_state") val jobState: String = "",
    val observation: SkillObservation? = null,
)

@Serializable
data class SkillObservation(
    val status: String = "",
    val successes: Int = 0,
    val failures: Int = 0,
    val ignored: Int = 0,
    val deadline: String = "",
    val reason: String = "",
    @SerialName("rollback_revision") val rollbackRevision: Long? = null,
)

enum class EvolutionGroup(val label: String) {
    ACTIVE("进行中"), OBSERVING("观察中"), COMPLETED("已完成"), WITHDRAWN("已撤回"), HISTORY("历史记录"), UNKNOWN("状态待同步"),
}

/** Only a lifecycle tied to these exact candidate bytes can enable actions. */
val SkillProposal.currentLifecycle: CandidateLifecycle?
    get() = lifecycle?.takeIf { stateVersion > 0 && it.stateVersion == stateVersion && it.status.isNotBlank() }

val SkillProposal.canCancel: Boolean
    get() = currentLifecycle?.let { it.canCancel && it.status != "historical" } == true &&
        approval == null && status in setOf("validating", "pending_review")

val SkillProposal.userConfirmed: Boolean
    get() = approval?.get("user_confirmation") is JsonObject

val SkillProposal.evolutionGroup: EvolutionGroup
    get() = when (currentLifecycle?.status) {
        "evaluating", "awaiting_adoption", "blocked_runtime", "blocked_evidence", "waiting_evidence", "waiting_observation", "publishing" -> EvolutionGroup.ACTIVE
        "observing" -> EvolutionGroup.OBSERVING
        "stable", "passed", "adopted", "published", "rejected", "superseded" -> EvolutionGroup.COMPLETED
        "withdrawn", "rolled_back", "insufficient_evidence" -> EvolutionGroup.WITHDRAWN
        "historical" -> EvolutionGroup.HISTORY
        else -> EvolutionGroup.UNKNOWN
    }

val SkillProposal.isActive: Boolean
    get() = evolutionGroup in setOf(EvolutionGroup.ACTIVE, EvolutionGroup.OBSERVING)

val SkillProposal.statusLabel: String
    get() {
        if (currentLifecycle == null) return when (status) {
            "published" -> "已发布 · 状态待同步"
            "rejected" -> "已结束"
            "failed" -> "验证未通过"
            "stale", "superseded" -> "已被替代"
            else -> "状态待同步"
        }
        return when (currentLifecycle?.status) {
            "evaluating" -> "验证中"
            "awaiting_adoption" -> "等待自动采用"
            "blocked_runtime" -> "等待运行环境"
            "blocked_evidence", "waiting_evidence" -> "等待验证证据"
            "waiting_observation" -> "等待观察结果"
            "publishing" -> "发布中"
            "observing" -> "已采用 · 观察中"
            "stable", "passed" -> "已稳定"
            "adopted" -> "已采用"
            "published" -> if (userConfirmed) "按会话指令发布" else "已发布"
            "withdrawn", "rolled_back", "insufficient_evidence" -> "已撤回"
            "rejected" -> when {
                status == "failed" -> "验证未通过"
                reviewedBy == "desktop" -> "已取消"
                else -> "未采用"
            }
            "superseded" -> "已被替代"
            "historical" -> "历史记录"
            else -> "状态待同步"
        }
    }

val SkillProposal.progressDescription: String
    get() {
        val progress = currentLifecycle ?: return "服务端尚未提供完整演进状态。可查看变更和验证记录，更新服务后可同步进度。"
        if (filePublicationPending) return "发布记录已保存，技能文件仍在同步。"
        if (userConfirmed) return "已根据会话中的明确发布指令处理；未执行或未通过的验证仍按实际结果展示。"
        if (progress.automaticRetry) return "系统正在安排重试，无需审批。"
        return when (progress.status) {
            "historical" -> "旧流程记录仅供查阅，不会重新审批或补跑。"
            "blocked_runtime" -> "等待隔离执行环境或依赖就绪，系统将继续验证。"
            "blocked_evidence", "waiting_evidence" -> "验证证据尚不充分，等待有效的新任务证据。"
            "waiting_observation" -> "等待已有版本的观察结果，再继续处理。"
            "awaiting_adoption" -> "系统将检查发布条件并自动采用，无需人工批准。"
            "observing" -> "已在有限范围采用，系统正观察任务效果。"
            "stable", "passed" -> "已满足服务端观察要求。"
            "withdrawn", "rolled_back", "insufficient_evidence" -> progress.observation?.reason?.takeIf(String::isNotBlank) ?: "当前候选已撤回，请查看验证和版本记录。"
            "evaluating" -> "系统正在检查候选内容和行为效果。"
            else -> ""
        }
    }
