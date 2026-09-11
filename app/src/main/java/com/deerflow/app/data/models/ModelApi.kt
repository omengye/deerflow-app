package com.deerflow.app.data.models

import com.deerflow.app.data.agui.AguiJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class AvailableModel(
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("supports_thinking") val supportsThinking: Boolean = false,
    @SerialName("supports_vision") val supportsVision: Boolean = false,
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class ModelListResponse(val models: List<AvailableModel> = emptyList())

/** Same API prefix rules as the AG-UI client, including reverse proxy prefixes. */
fun resolveApiBaseUrl(endpoint: String): HttpUrl {
    val parsed = endpoint.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("请输入有效的 HTTP 或 HTTPS 服务器地址")
    val segments = parsed.pathSegments.filter { it.isNotEmpty() }
    val apiIndex = segments.indexOf("api")
    val prefix = when {
        apiIndex >= 0 -> segments.take(apiIndex + 1)
        segments.contains("agent") -> listOf("api")
        else -> segments + "api"
    }
    return parsed.newBuilder().encodedPath("/").query(null).fragment(null)
        .apply { prefix.forEach(::addPathSegment) }.build()
}

class ModelApiException(val statusCode: Int) : IOException("Model list request failed: $statusCode")

class ModelApi(private val http: OkHttpClient = defaultHttp) {
    suspend fun listModels(endpoint: String, headers: Map<String, String>): List<AvailableModel> {
        val url = resolveApiBaseUrl(endpoint).newBuilder().addPathSegment("models").build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        val body = suspendCancellableCoroutine<String> { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw ModelApiException(it.code)
                            it.body?.string() ?: throw IOException("Empty model list response")
                        }
                    }
                    result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
        return AguiJson.decodeFromString(ModelListResponse.serializer(), body).models
            .filter { it.name.isNotBlank() }.distinctBy { it.name }
    }

    companion object {
        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }
}
