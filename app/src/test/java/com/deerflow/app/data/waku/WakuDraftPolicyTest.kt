package com.deerflow.app.data.waku

import com.deerflow.app.domain.model.ThreadMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakuDraftPolicyTest {
    private fun emptyDraft(
        id: String,
        updatedAtSec: Long = 1_000L,
        title: String? = "New task",
        autoTitle: String? = null,
    ): WakuSnapshot = WakuProjection.fromSession(
        WakuProjection.createSession(id, "project-1", nowMillis = updatedAtSec * 1_000L).updated(
            "title" to (title?.let(::JsonPrimitive) ?: JsonNull),
            "auto_title" to (autoTitle?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    private fun message(id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("role", "user")
        put("content", "Hello")
    }

    @Test
    fun ephemeralEmptyLocalDraftIsHiddenFromThreadList() {
        val draft = emptyDraft("draft-1")
        assertTrue(WakuDraftPolicy.isEphemeralEmptyDraft("draft-1", draft, emptySet(), emptySet()))
        assertFalse(WakuDraftPolicy.shouldShowInThreadList("draft-1", draft, emptySet(), emptySet()))

        val blankTitle = emptyDraft("draft-2", title = "   ")
        assertTrue(WakuDraftPolicy.isEphemeralEmptyDraft("draft-2", blankTitle, emptySet(), emptySet()))
        assertFalse(WakuDraftPolicy.shouldShowInThreadList("draft-2", blankTitle, emptySet(), emptySet()))
    }

    @Test
    fun remoteEmptySessionRemainsVisibleInThreadList() {
        val remoteEmpty = emptyDraft("remote-1")
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("remote-1", remoteEmpty, setOf("remote-1"), emptySet()))
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("remote-catalog-only", null, setOf("remote-catalog-only"), emptySet()))

        val knownRemote = remoteEmpty.copy(remoteKnown = true)
        assertFalse(WakuDraftPolicy.isEphemeralEmptyDraft("remote-2", knownRemote, emptySet(), emptySet()))
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("remote-2", knownRemote, emptySet(), emptySet()))
    }

    @Test
    fun localDraftWithActivityOrCustomMetadataRemainsVisibleInThreadList() {
        val base = emptyDraft("draft-active")
        val withPending = base.copy(pendingCommands = listOf(buildJsonObject { put("kind", "prompt"); put("state", "prepared") }))
        val withMessages = base.copy(session = base.session.updated("messages" to JsonArray(listOf(message("m1")))))
        val withCursor = base.copy(cursor = buildJsonObject { put("sequence", 1) })
        val withSent = base.copy(sentMessages = mapOf("m1" to WakuSentMessage("Hello", emptyList())))
        val withTitle = emptyDraft("draft-titled", title = "Custom task")
        val withAutoTitle = emptyDraft("draft-auto", autoTitle = "Auto title")

        assertTrue(WakuDraftPolicy.shouldShowInThreadList("draft-active", withPending, emptySet(), emptySet()))
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("draft-active", withMessages, emptySet(), emptySet()))
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("draft-active", withCursor, emptySet(), emptySet()))
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("draft-active", withSent, emptySet(), emptySet()))
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("draft-active", base, emptySet(), setOf("draft-active")))
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("draft-titled", withTitle, emptySet(), emptySet()))
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("draft-auto", withAutoTitle, emptySet(), emptySet()))
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("legacy:123", emptyDraft("legacy:123"), emptySet(), emptySet()))
    }

    @Test
    fun reusableEmptyDraftIdPrefersCurrentDraftThenNewestEmptyDraft() {
        val oldEmpty = emptyDraft("empty-old", updatedAtSec = 100L)
        val newEmpty = emptyDraft("empty-new", updatedAtSec = 200L)
        val nonEmpty = emptyDraft("real-1", updatedAtSec = 300L, title = "Real task")
        val snapshots = mapOf(
            "empty-old" to oldEmpty,
            "empty-new" to newEmpty,
            "real-1" to nonEmpty,
        )

        assertEquals(
            "empty-old",
            WakuDraftPolicy.reusableEmptyDraftId(snapshots, emptySet(), emptySet(), preferredId = "empty-old"),
        )
        assertEquals(
            "empty-new",
            WakuDraftPolicy.reusableEmptyDraftId(snapshots, emptySet(), emptySet(), preferredId = "real-1"),
        )
        assertNull(
            WakuDraftPolicy.reusableEmptyDraftId(mapOf("real-1" to nonEmpty), emptySet(), emptySet(), preferredId = "real-1"),
        )
    }

    @Test
    fun redundantEmptyDraftIdsReturnsOnlyExtraEphemeralDrafts() {
        val snapshots = mapOf(
            "keep-empty" to emptyDraft("keep-empty", updatedAtSec = 100L),
            "stale-empty-1" to emptyDraft("stale-empty-1", updatedAtSec = 200L),
            "stale-empty-2" to emptyDraft("stale-empty-2", updatedAtSec = 300L),
            "remote-empty" to emptyDraft("remote-empty", updatedAtSec = 400L),
            "blocked-empty" to emptyDraft("blocked-empty", updatedAtSec = 500L),
            "real-session" to emptyDraft("real-session", updatedAtSec = 600L, title = "Real"),
        )

        val redundant = WakuDraftPolicy.redundantEmptyDraftIds(
            snapshots = snapshots,
            remoteIds = setOf("remote-empty"),
            blockedIds = setOf("blocked-empty"),
            keepId = "keep-empty",
        )
        assertEquals(setOf("stale-empty-1", "stale-empty-2"), redundant.toSet())
    }

    @Test
    fun initialSelectedIdPrefersRealSessionsOverNewerEmptyDrafts() {
        val realOlder = emptyDraft("real-1", updatedAtSec = 100L, title = "Real task")
        val realNewer = emptyDraft("real-2", updatedAtSec = 200L, title = "Newer real task")
        val newerEmpty = emptyDraft("empty-newer", updatedAtSec = 500L)

        assertEquals(
            "real-2",
            WakuDraftPolicy.initialSelectedId(
                snapshots = mapOf("real-1" to realOlder, "real-2" to realNewer, "empty-newer" to newerEmpty),
                remoteIds = emptySet(),
                blockedIds = emptySet(),
                fallbackId = "fallback",
            ),
        )
        assertEquals(
            "empty-newer",
            WakuDraftPolicy.initialSelectedId(
                snapshots = mapOf("empty-old" to emptyDraft("empty-old", 100L), "empty-newer" to newerEmpty),
                remoteIds = emptySet(),
                blockedIds = emptySet(),
                fallbackId = "fallback",
            ),
        )
        assertEquals(
            "fallback",
            WakuDraftPolicy.initialSelectedId(
                snapshots = emptyMap(),
                remoteIds = emptySet(),
                blockedIds = emptySet(),
                fallbackId = "fallback",
            ),
        )
    }

    @Test
    fun remoteDeletedSnapshotRemainsVisibleAndDefaultsSafelyOnDeserialization() {
        val deleted = emptyDraft("deleted-1").copy(remoteDeleted = true)
        assertFalse(WakuDraftPolicy.isEphemeralEmptyDraft("deleted-1", deleted, emptySet(), emptySet()))
        assertTrue(WakuDraftPolicy.shouldShowInThreadList("deleted-1", deleted, emptySet(), emptySet()))

        val json = Json { ignoreUnknownKeys = true }
        val legacyThreadJson = """{"id":"t1","title":"Old","lastActive":1000}"""
        val decodedLegacyThread = json.decodeFromString(ThreadMeta.serializer(), legacyThreadJson)
        assertFalse(decodedLegacyThread.remoteDeleted)

        val deletedThread = ThreadMeta(id = "t2", title = "Gone", lastActive = 2000L, isTitleFetched = true, remoteDeleted = true)
        assertEquals(deletedThread, json.decodeFromString(ThreadMeta.serializer(), json.encodeToString(ThreadMeta.serializer(), deletedThread)))

        val sessionJson = json.encodeToString(JsonObject.serializer(), emptyDraft("snap-legacy").session)
        val legacySnapshotJson = """{"session":$sessionJson}"""
        val decodedLegacySnapshot = json.decodeFromString(WakuSnapshot.serializer(), legacySnapshotJson)
        assertFalse(decodedLegacySnapshot.remoteDeleted)

        val roundTrippedSnapshot = json.decodeFromString(
            WakuSnapshot.serializer(),
            json.encodeToString(WakuSnapshot.serializer(), deleted),
        )
        assertTrue(roundTrippedSnapshot.remoteDeleted)
    }
}
