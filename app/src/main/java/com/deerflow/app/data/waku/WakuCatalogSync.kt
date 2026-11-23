package com.deerflow.app.data.waku

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure server-catalog reconciliation rules for multi-client and background sync.
 * Always compares server-originated catalog fields against previous server snapshots
 * so device/server clock skew cannot trigger hydration loops.
 */
object WakuCatalogSync {
    private val BUSY_STATUSES = setOf("connecting", "working", "waiting", "background")

    data class Fingerprint(
        val historyRevision: Long,
        val status: String?,
        val updatedAt: Long,
        val lastReplyAt: Long?,
    )

    data class SyncTarget(
        val sessionId: String,
        val forceHydrate: Boolean,
        val expectedRuntimeId: String? = null,
    )

    fun fingerprint(summary: JsonObject): Fingerprint = Fingerprint(
        historyRevision = WakuRecovery.historyRevision(summary),
        status = summary.string("status"),
        updatedAt = summary.long("updated_at"),
        lastReplyAt = (summary["last_reply_at"] as? JsonPrimitive)?.longOrNull,
    )

    fun sessionsToSync(
        selectedId: String,
        previousCatalog: Map<String, JsonObject>,
        currentCatalog: Map<String, JsonObject>,
        snapshots: Map<String, WakuSnapshot>,
        attachedSessionIds: Set<String>,
        syncedFingerprints: Map<String, Fingerprint>,
        isLiveUpdate: Boolean,
    ): List<SyncTarget> {
        val targets = mutableListOf<SyncTarget>()
        for ((id, summary) in currentCatalog) {
            if (id.isBlank() || id.startsWith("legacy:")) continue
            val snapshot = snapshots[id]
            val attached = id in attachedSessionIds
            val fp = fingerprint(summary)
            if (WakuRecovery.needsHistoryRefresh(snapshot?.session, summary)) {
                if ((id == selectedId || attached || fp.status in BUSY_STATUSES) && syncedFingerprints[id] != fp) {
                    targets += SyncTarget(id, forceHydrate = true)
                }
                continue
            }
            if (attached) continue
            if (syncedFingerprints[id] == fp) continue
            if (fp.status in BUSY_STATUSES) {
                targets += SyncTarget(id, forceHydrate = false)
                continue
            }
            if (!isLiveUpdate) continue
            val previous = previousCatalog[id]
            val changed = when {
                previous == null -> snapshot == null
                else -> {
                    val prevFp = fingerprint(previous)
                    fp.lastReplyAt != prevFp.lastReplyAt ||
                        fp.status != prevFp.status ||
                        (id == selectedId && fp.updatedAt != prevFp.updatedAt)
                }
            }
            if (changed) targets += SyncTarget(id, forceHydrate = false)
        }
        return targets
    }

    /**
     * Combines the latest server catalog summary with a cached snapshot for thread-list and
     * background-work calculation without mutating the durable local snapshot.
     * Live-attached sessions keep their event-projected state authoritative.
     */
    fun mergeDisplayRow(
        catalogRow: JsonObject?,
        snapshot: WakuSnapshot?,
        liveAttached: Boolean,
    ): JsonObject? {
        if (snapshot == null) return catalogRow
        if (catalogRow == null || liveAttached) return snapshot.session
        val local = snapshot.session
        val updates = mutableMapOf<String, JsonElement>()
        val remoteUpdated = catalogRow.long("updated_at")
        if (remoteUpdated > local.long("updated_at")) {
            updates["updated_at"] = JsonPrimitive(remoteUpdated)
        }
        val remoteStatus = catalogRow.string("status")
        if (!remoteStatus.isNullOrBlank() && remoteStatus != local.string("status") &&
            snapshot.pendingCommands.isEmpty()) {
            updates["status"] = JsonPrimitive(remoteStatus)
        }
        return if (updates.isEmpty()) local else JsonObject(local.toMutableMap().apply { putAll(updates) })
    }
}
