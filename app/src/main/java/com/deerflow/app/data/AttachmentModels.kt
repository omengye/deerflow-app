package com.deerflow.app.data

import android.net.Uri

/** Attachment selected on-device and waiting to be uploaded with the next user turn. */
data class PendingAttachment(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
)
