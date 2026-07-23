package com.deerflow.app.data.proposal

import com.deerflow.app.data.agui.AguiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProposalApiException(
    val statusCode: Int,
    responseSnippet: String,
) : IOException("Proposal request failed: status=$statusCode $responseSnippet")

/** HTTP client for the existing single-user Admin Proposal API. */
class ProposalApi(
    endpoint: String,
    private val headers: Map<String, String>,
    private val http: OkHttpClient = defaultHttp,
) {
    private val proposalsUrl = resolveProposalBaseUrl(endpoint)

    suspend fun listProposals(): List<SkillProposal> {
        val request = requestBuilder(proposalsUrl).get().build()
        val body = execute(request)
        return AguiJson.decodeFromString(ProposalListResponse.serializer(), body).proposals
    }

    suspend fun getProposal(proposalId: String): SkillProposal {
        val request = requestBuilder(proposalUrl(proposalId)).get().build()
        return AguiJson.decodeFromString(SkillProposal.serializer(), execute(request))
    }

    suspend fun approve(proposal: SkillProposal, note: String?): SkillProposal =
        review(proposal, "approve", note)

    suspend fun reject(proposal: SkillProposal, note: String?): SkillProposal =
        review(proposal, "reject", note)

    private suspend fun review(proposal: SkillProposal, decision: String, note: String?): SkillProposal {
        val payload = ProposalReviewRequest(
            expectedBaseSha256 = proposal.baseSha256,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
        )
        val body = AguiJson.encodeToString(ProposalReviewRequest.serializer(), payload)
            .toRequestBody(JSON_MEDIA_TYPE)
        val url = proposalUrl(proposal.id).newBuilder().addPathSegment(decision).build()
        val request = requestBuilder(url).post(body).build()
        return AguiJson.decodeFromString(
            ProposalReviewResponse.serializer(),
            execute(request),
        ).proposal
    }

    private fun proposalUrl(proposalId: String): HttpUrl =
        proposalsUrl.newBuilder().addPathSegment(proposalId).build()

    private fun requestBuilder(url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ProposalApiException(response.code, body.take(MAX_ERROR_CHARS))
            }
            body
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val MAX_ERROR_CHARS = 500
        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

internal fun resolveProposalBaseUrl(endpoint: String): HttpUrl {
    val parsed = endpoint.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Invalid DeerFlow endpoint URL")
    val segments = parsed.pathSegments.filter { it.isNotEmpty() }
    val apiIndex = segments.indexOf("api")
    val prefixSegments = when {
        apiIndex >= 0 -> segments.subList(0, apiIndex + 1)
        segments.contains("agent") -> listOf("api")
        else -> segments + "api"
    }

    return parsed.newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .apply {
            prefixSegments.forEach(::addPathSegment)
            addPathSegment("admin")
            addPathSegment("evolution")
            addPathSegment("proposals")
        }
        .build()
}

