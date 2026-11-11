package com.deerflow.app.data.waku

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WakuSubmissionStatusTest {
    @Test fun `normal prompt lifecycle shows progress without recovery actions and settles on echo`() {
        for (state in listOf("prepared", "sending", "accepted")) {
            val pending = command(state)
            val snapshot = WakuProjection.fromSession(WakuProjection.createSession("session", "project"))
                .copy(pendingCommands = listOf(pending))
            val status = WakuSubmissionStatus.from(snapshot.pendingCommands)
            assertNotNull(state, status.progress)
            assertNull(state, status.issue)
            assertFalse(state, status.needsReview)
            assertFalse(state, status.canRestorePrompt)
            // Hiding recovery actions must not remove the command used to prevent duplicate sends.
            assertEquals(listOf(pending), WakuRecovery.resolveObservedCommands(snapshot).pendingCommands)
            val observed = snapshot.copy(session = snapshot.session.updated("messages" to JsonArray(listOf(
                buildJsonObject { put("id", "message") },
            ))))
            val resolved = WakuRecovery.resolveObservedCommands(observed)
            assertTrue(resolved.pendingCommands.isEmpty())
            assertEquals(WakuSubmissionStatus(), WakuSubmissionStatus.from(resolved.pendingCommands))
        }
    }

    @Test fun `failed prompts retain the original and reason without asking for confirmation`() {
        for (state in listOf("notSent", "rejected")) {
            val status = WakuSubmissionStatus.from(listOf(command(state).updated("error" to JsonPrimitive("附件上传失败"))))
            assertNull(status.progress)
            assertTrue(status.issue!!.contains("附件上传失败"))
            assertTrue(status.issue.contains("原始消息"))
            assertTrue(status.canRestorePrompt)
            assertTrue(status.canResolve)
            assertFalse(status.needsReview)
        }
    }

    @Test fun `unknown delivery retains review while other messages being sent stay out of the issue`() {
        val unknown = command("unknown")
        val sending = command("sending").updated("text" to JsonPrimitive("正常发送的另一条消息"))
        val status = WakuSubmissionStatus.from(listOf(unknown, sending))
        assertTrue(status.needsReview)
        assertTrue(status.canRestorePrompt)
        assertTrue(status.issue!!.contains("原始消息"))
        assertFalse(status.issue.contains("正常发送的另一条消息"))
        assertFalse(status.canResolve)
    }

    @Test fun `control acknowledgements need no confirmation and control failures have no draft actions`() {
        for (kind in listOf("respond", "respondUserInput", "cancel")) {
            assertEquals(WakuSubmissionStatus(), WakuSubmissionStatus.from(listOf(command("accepted", kind))))
            val status = WakuSubmissionStatus.from(listOf(command("unknown", kind)))
            assertNotNull(status.issue)
            assertTrue(status.needsReview)
            assertFalse(status.canRestorePrompt)
        }
    }

    private fun command(state: String, kind: String = "prompt") = buildJsonObject {
        put("kind", kind); put("state", state); put("requestId", "request"); put("messageId", "message")
        if (kind == "prompt") put("text", "原始消息")
    }
}
