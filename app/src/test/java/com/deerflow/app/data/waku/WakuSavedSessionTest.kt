package com.deerflow.app.data.waku

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WakuSavedSessionTest {
    private fun session(sequence: Int, image: String) = WakuProjection.createSession("session", "project").updated(
        "runtime_event_cursor" to buildJsonObject { put("runtime_id", "runtime"); put("epoch", "epoch"); put("sequence", sequence) },
        "fixture_image" to JsonPrimitive(image),
    )

    @Test fun matchingSaveAdoptsBlobReferencesAndPreservesLocalCommandState() {
        val submitted = session(1, "data:image/png;base64,fixture")
        val command = buildJsonObject { put("kind", "cancel"); put("state", "sending") }
        val current = WakuProjection.fromSession(submitted).copy(session = submitted, pendingCommands = listOf(command))
        val saved = session(1, "waku-blob:image.png")
        val merged = WakuSavedSession.merge(current, submitted, saved)
        assertEquals(saved, merged.session)
        assertEquals(listOf(command), merged.pendingCommands)
        assertTrue(merged.remoteKnown)
    }

    @Test fun lateSaveAcknowledgementCannotOverwriteNewerStreamContent() {
        val submitted = session(1, "old")
        val latest = session(2, "new text and images")
        val current = WakuProjection.fromSession(latest)
        assertEquals(current.session, WakuSavedSession.merge(current, submitted, session(1, "waku-blob:old.png")).session)
    }

    @Test fun differentHistoryOrCursorCannotBeReportedAsSaved() {
        val submitted = session(1, "image")
        val current = WakuProjection.fromSession(submitted)
        for (saved in listOf(session(2, "other turn"), submitted.updated("history_revision" to JsonPrimitive(1)))) {
            assertTrue(runCatching { WakuSavedSession.merge(current, submitted, saved) }.isFailure)
        }
    }
}
