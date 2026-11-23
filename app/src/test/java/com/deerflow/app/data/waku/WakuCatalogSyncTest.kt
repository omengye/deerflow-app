package com.deerflow.app.data.waku

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakuCatalogSyncTest {
    private fun summary(
        id: String,
        status: String = "idle",
        updatedAt: Long = 1_000L,
        lastReplyAt: Long? = null,
        historyRevision: Long = 0L,
        title: String = "Task",
    ): JsonObject = WakuProjection.createSession(id, "project-1", nowMillis = updatedAt * 1000).updated(
        "status" to JsonPrimitive(status),
        "updated_at" to JsonPrimitive(updatedAt),
        "last_reply_at" to (lastReplyAt?.let(::JsonPrimitive) ?: JsonNull),
        "history_revision" to JsonPrimitive(historyRevision),
        "title" to JsonPrimitive(title),
    )

    @Test
    fun historyRevisionIncreaseForcesHydrateForSelectedAttachedOrBusyAndDeduplicatesFingerprint() {
        val selected = summary("s-selected", historyRevision = 1)
        val attached = summary("s-attached", historyRevision = 1)
        val busyOther = summary("s-busy", status = "working", historyRevision = 1)
        val idleOther = summary("s-other", historyRevision = 1)
        val snapshots = mapOf(
            "s-selected" to WakuProjection.fromSession(summary("s-selected", historyRevision = 0)),
            "s-attached" to WakuProjection.fromSession(summary("s-attached", historyRevision = 0)),
            "s-busy" to WakuProjection.fromSession(summary("s-busy", historyRevision = 0)),
            "s-other" to WakuProjection.fromSession(summary("s-other", historyRevision = 0)),
        )
        val catalog = mapOf(
            "s-selected" to selected,
            "s-attached" to attached,
            "s-busy" to busyOther,
            "s-other" to idleOther,
        )
        val targets = WakuCatalogSync.sessionsToSync(
            selectedId = "s-selected",
            previousCatalog = emptyMap(),
            currentCatalog = catalog,
            snapshots = snapshots,
            attachedSessionIds = setOf("s-attached"),
            syncedFingerprints = emptyMap(),
            isLiveUpdate = true,
        )
        assertEquals(
            setOf(
                WakuCatalogSync.SyncTarget("s-selected", forceHydrate = true),
                WakuCatalogSync.SyncTarget("s-attached", forceHydrate = true),
                WakuCatalogSync.SyncTarget("s-busy", forceHydrate = true),
            ),
            targets.toSet(),
        )

        val deduped = WakuCatalogSync.sessionsToSync(
            selectedId = "s-selected",
            previousCatalog = catalog,
            currentCatalog = catalog,
            snapshots = snapshots,
            attachedSessionIds = setOf("s-attached"),
            syncedFingerprints = mapOf(
                "s-selected" to WakuCatalogSync.fingerprint(selected),
                "s-attached" to WakuCatalogSync.fingerprint(attached),
                "s-busy" to WakuCatalogSync.fingerprint(busyOther),
            ),
            isLiveUpdate = true,
        )
        assertTrue(deduped.isEmpty())
    }

    @Test
    fun unattachedBusySessionTriggersSyncAndDeduplicatesSameFingerprint() {
        val busy = summary("s-desktop", status = "working", updatedAt = 2_000L)
        val first = WakuCatalogSync.sessionsToSync(
            selectedId = "s-phone",
            previousCatalog = mapOf("s-desktop" to summary("s-desktop", status = "idle", updatedAt = 1_000L)),
            currentCatalog = mapOf("s-desktop" to busy),
            snapshots = mapOf("s-desktop" to WakuProjection.fromSession(summary("s-desktop", status = "idle"))),
            attachedSessionIds = setOf("s-phone"),
            syncedFingerprints = emptyMap(),
            isLiveUpdate = true,
        )
        assertEquals(listOf(WakuCatalogSync.SyncTarget("s-desktop", forceHydrate = false)), first)

        val second = WakuCatalogSync.sessionsToSync(
            selectedId = "s-phone",
            previousCatalog = mapOf("s-desktop" to busy),
            currentCatalog = mapOf("s-desktop" to busy),
            snapshots = mapOf("s-desktop" to WakuProjection.fromSession(summary("s-desktop", status = "idle"))),
            attachedSessionIds = setOf("s-phone"),
            syncedFingerprints = mapOf("s-desktop" to WakuCatalogSync.fingerprint(busy)),
            isLiveUpdate = true,
        )
        assertTrue(second.isEmpty())
    }

    @Test
    fun liveCatalogReplyOrStatusChangeTriggersSyncWithoutClockSkewLoop() {
        val prev = summary("s-other", status = "idle", updatedAt = 5_000L, lastReplyAt = 4_900L)
        val next = summary("s-other", status = "idle", updatedAt = 5_020L, lastReplyAt = 5_020L)
        // Local device clock lags behind server clock (1_000 < 5_000), yet unchanged catalog does not loop.
        val localSkewed = WakuProjection.fromSession(summary("s-other", status = "idle", updatedAt = 1_000L, lastReplyAt = 1_000L))

        val unchanged = WakuCatalogSync.sessionsToSync(
            selectedId = "s-selected",
            previousCatalog = mapOf("s-other" to prev),
            currentCatalog = mapOf("s-other" to prev),
            snapshots = mapOf("s-other" to localSkewed),
            attachedSessionIds = emptySet(),
            syncedFingerprints = emptyMap(),
            isLiveUpdate = true,
        )
        assertTrue(unchanged.isEmpty())

        val replied = WakuCatalogSync.sessionsToSync(
            selectedId = "s-selected",
            previousCatalog = mapOf("s-other" to prev),
            currentCatalog = mapOf("s-other" to next),
            snapshots = mapOf("s-other" to localSkewed),
            attachedSessionIds = emptySet(),
            syncedFingerprints = emptyMap(),
            isLiveUpdate = true,
        )
        assertEquals(listOf(WakuCatalogSync.SyncTarget("s-other", forceHydrate = false)), replied)
    }

    @Test
    fun pureTitleRenameOnUnrelatedIdleSessionDoesNotTriggerHydration() {
        val prev = summary("s-other", status = "idle", updatedAt = 1_000L, lastReplyAt = 900L, title = "Old")
        val renamed = summary("s-other", status = "idle", updatedAt = 1_050L, lastReplyAt = 900L, title = "New")
        val targets = WakuCatalogSync.sessionsToSync(
            selectedId = "s-selected",
            previousCatalog = mapOf("s-other" to prev),
            currentCatalog = mapOf("s-other" to renamed),
            snapshots = mapOf("s-other" to WakuProjection.fromSession(prev)),
            attachedSessionIds = emptySet(),
            syncedFingerprints = emptyMap(),
            isLiveUpdate = true,
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun alreadyAttachedSessionDoesNotResyncOnSaveTaskStateEcho() {
        val prev = summary("s-live", status = "working", updatedAt = 1_000L, lastReplyAt = 1_000L)
        val echo = summary("s-live", status = "idle", updatedAt = 1_010L, lastReplyAt = 1_010L)
        val targets = WakuCatalogSync.sessionsToSync(
            selectedId = "s-live",
            previousCatalog = mapOf("s-live" to prev),
            currentCatalog = mapOf("s-live" to echo),
            snapshots = mapOf("s-live" to WakuProjection.fromSession(echo)),
            attachedSessionIds = setOf("s-live"),
            syncedFingerprints = emptyMap(),
            isLiveUpdate = true,
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun newDesktopSessionAppearingInLiveCatalogTriggersSync() {
        val brandNew = summary("s-brand-new", status = "idle", updatedAt = 2_000L, lastReplyAt = 2_000L)
        val initialLoad = WakuCatalogSync.sessionsToSync(
            selectedId = "s-selected",
            previousCatalog = emptyMap(),
            currentCatalog = mapOf("s-brand-new" to brandNew),
            snapshots = emptyMap(),
            attachedSessionIds = emptySet(),
            syncedFingerprints = emptyMap(),
            isLiveUpdate = false,
        )
        assertTrue(initialLoad.isEmpty())

        val liveUpdate = WakuCatalogSync.sessionsToSync(
            selectedId = "s-selected",
            previousCatalog = emptyMap(),
            currentCatalog = mapOf("s-brand-new" to brandNew),
            snapshots = emptyMap(),
            attachedSessionIds = emptySet(),
            syncedFingerprints = emptyMap(),
            isLiveUpdate = true,
        )
        assertEquals(listOf(WakuCatalogSync.SyncTarget("s-brand-new", forceHydrate = false)), liveUpdate)
    }

    @Test
    fun mergeDisplayRowSurfacesServerStatusAndTimestampForUnattachedSession() {
        val snapshot = WakuProjection.fromSession(summary("s-1", status = "idle", updatedAt = 1_000L, title = "Local title"))
        val catalog = summary("s-1", status = "working", updatedAt = 2_500L, title = "Catalog title")
        val merged = WakuCatalogSync.mergeDisplayRow(catalog, snapshot, liveAttached = false)!!
        assertEquals("working", merged.string("status"))
        assertEquals(2_500L, merged.long("updated_at"))
        assertEquals("Local title", merged.string("title"))
    }

    @Test
    fun mergeDisplayRowKeepsLiveAttachedSessionAuthoritative() {
        val snapshot = WakuProjection.fromSession(summary("s-1", status = "idle", updatedAt = 3_000L))
        val laggingCatalog = summary("s-1", status = "working", updatedAt = 2_500L)
        val merged = WakuCatalogSync.mergeDisplayRow(laggingCatalog, snapshot, liveAttached = true)!!
        assertEquals("idle", merged.string("status"))
        assertEquals(3_000L, merged.long("updated_at"))
    }

    @Test
    fun mergeDisplayRowPreservesLocalStatusWhilePendingCommandsExist() {
        val pending = buildJsonObject { put("kind", "prompt"); put("state", "sending") }
        val snapshot = WakuProjection.fromSession(summary("s-1", status = "connecting", updatedAt = 1_000L))
            .copy(pendingCommands = listOf(pending))
        val catalog = summary("s-1", status = "idle", updatedAt = 1_500L)
        val merged = WakuCatalogSync.mergeDisplayRow(catalog, snapshot, liveAttached = false)!!
        assertEquals("connecting", merged.string("status"))
        assertEquals(1_500L, merged.long("updated_at"))
        assertNull(WakuCatalogSync.mergeDisplayRow(null, null, liveAttached = false))
    }
}
