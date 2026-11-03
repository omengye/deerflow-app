package com.deerflow.app.data.waku

import com.deerflow.app.domain.model.MessageFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class WakuSentMessage(val text: String, val attachments: List<MessageFile>)

/** Only new submissions are remembered here. Never guess attachment ownership from message text. */
object WakuMessageFiles {
    fun remember(snapshot: WakuSnapshot, messageId: String, text: String, files: List<MessageFile>): WakuSnapshot =
        snapshot.copy(sentMessages = snapshot.sentMessages + (messageId to WakuSentMessage(text, files)))

    /** Keep normal attachments in the standard session format so ordinary Waku clients retain them. */
    fun materialize(snapshot: WakuSnapshot): WakuSnapshot {
        val messages = snapshot.session.array("messages").map { value ->
            val message = value as? JsonObject ?: return@map value
            val remembered = snapshot.sentMessages[message.string("id")] ?: return@map value
            val nativeFiles = remembered.attachments.filter { it.oss == null && it.serverReference != null }
            message.updated("display_content" to JsonPrimitive(remembered.text),
                "attachments" to JsonArray(nativeFiles.map { file -> buildJsonObject {
                    put("path", file.serverPath); put("mention", file.serverPath); put("name", file.name)
                    put("is_dir", false); put("is_image", file.isImage); put("blob_reference", file.serverReference)
                } }))
        }
        var session = snapshot.session.updated("messages" to JsonArray(messages))
        val firstUser = messages.filterIsInstance<JsonObject>().firstOrNull { it.string("role") == "user" }
        val firstSent = snapshot.sentMessages[firstUser?.string("id")]
        val automatic = session.string("auto_title")
        if (firstSent != null && (automatic.isNullOrBlank() || automatic.contains("[DEERFLOW"))) {
            val text = firstSent.text.ifBlank { firstSent.attachments.firstOrNull()?.name.orEmpty() }
            WakuSessionTitles.summarize(text)?.let { session = session.updated("auto_title" to JsonPrimitive(it)) }
        }
        return snapshot.copy(session = session)
    }
}
