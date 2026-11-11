package com.deerflow.app.data.waku

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WakuCommandPolicyTest {
    @Test fun distinctApprovalsCanProceedBeforeToolsProduceOutput() {
        val pending = listOf(command("first", "respond", "accepted", "tool-a"), command("second", "respond", "prepared", "tool-b"))
        assertTrue(WakuCommandPolicy.canSubmitControl(pending, "second", "respond", "tool-b"))
        assertFalse(WakuCommandPolicy.canSubmitControl(pending, "third", "respond", "tool-a"))
    }
    @Test fun unknownApprovalDoesNotAuthorizeResubmittingTheSameDecision() {
        val pending = listOf(command("first", "respond", "unknown", "tool-a"))
        assertFalse(WakuCommandPolicy.canSubmitControl(pending, "second", "respond", "tool-a"))
        assertTrue(WakuCommandPolicy.canSubmitControl(pending, "second", "respond", "tool-b"))
    }
    @Test fun cancelCanStopARequestWhoseSubmissionResultIsUnknown() {
        val pending = listOf(command("prompt", "prompt", "unknown"), command("approval", "respond", "unknown", "tool-a"))
        assertTrue(WakuCommandPolicy.canSubmitControl(pending, "stop", "cancel", null))
        assertFalse(WakuCommandPolicy.canSubmitControl(pending + command("stop", "cancel", "sending"), "stop-again", "cancel", null))
    }
    @Test fun aSecondClickCannotSendPastAnUnresolvedFirstSubmission() {
        val pending = listOf(command("first", "prompt", "unknown"), command("second", "prompt", "prepared"))
        assertFalse(WakuCommandPolicy.canSubmitPrompt(pending, "second"))
        assertTrue(WakuCommandPolicy.canSubmitPrompt(listOf(command("first", "prompt", "prepared"), command("second", "prompt", "prepared")), "first"))
    }
    @Test fun acknowledgementCannotEraseWorkStillBeingSubmitted() {
        val pending = listOf(command("prepared", "prompt", "prepared"), command("sending", "prompt", "sending"), command("unknown", "prompt", "unknown"))
        assertEquals(listOf("prepared", "sending"), WakuCommandPolicy.acknowledge(pending).map { it["requestId"]!!.jsonPrimitive.content })
    }
    @Test fun acknowledgingAnUnknownPromptKeepsAcceptedCommandsUntilTheyAreObserved() {
        val pending = listOf(command("unknown", "prompt", "unknown"), command("approval", "respond", "accepted", "tool-a"),
            command("stop", "cancel", "accepted"), command("prompt", "prompt", "accepted"))
        assertEquals(listOf("approval", "stop", "prompt"), WakuCommandPolicy.acknowledge(pending).map { it["requestId"]!!.jsonPrimitive.content })
    }
    private fun command(id: String, kind: String, state: String, target: String? = null) = buildJsonObject {
        put("requestId", id); put("kind", kind); put("state", state); put("targetRequestId", target?.let(::JsonPrimitive) ?: JsonNull)
    }
}
