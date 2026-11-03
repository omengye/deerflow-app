package com.deerflow.app.domain.model

import kotlinx.serialization.Serializable

/** Object identity is durable; download signatures are temporary. */
@Serializable
data class S3Object(
    val endpoint: String,
    val bucket: String,
    val key: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class MessageFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val localUrl: String = "",
    val serverReference: String? = null,
    val serverPath: String? = null,
    val oss: S3Object? = null,
    val sha256: String = "",
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    fun asArtifact() = AgentArtifact(path = id, name = name, url = localUrl, mimeType = mimeType,
        kind = if (isImage) "image" else "file", size = size)
}

data class TaskFilesState(
    val files: List<AgentArtifact> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)
