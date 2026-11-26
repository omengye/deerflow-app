package com.deerflow.app.data.waku

/**
 * Separates ephemeral empty local drafts from persisted or meaningful conversation history.
 * Ephemeral empty drafts back the blank "New Chat" canvas without appearing in the thread list.
 */
object WakuDraftPolicy {
    private const val DEFAULT_TITLE = "New task"

    fun isEphemeralEmptyDraft(
        id: String,
        snapshot: WakuSnapshot,
        remoteIds: Set<String>,
        blockedIds: Set<String>,
    ): Boolean = !id.startsWith("legacy:") &&
        id !in remoteIds &&
        !snapshot.remoteKnown &&
        !snapshot.remoteDeleted &&
        id !in blockedIds &&
        snapshot.session.array("messages").isEmpty() &&
        snapshot.pendingCommands.isEmpty() &&
        snapshot.cursor == null &&
        snapshot.sentMessages.isEmpty() &&
        snapshot.session.string("title").let { it.isNullOrBlank() || it == DEFAULT_TITLE } &&
        snapshot.session.string("auto_title").isNullOrBlank()

    fun shouldShowInThreadList(
        id: String,
        snapshot: WakuSnapshot?,
        remoteIds: Set<String>,
        blockedIds: Set<String>,
    ): Boolean = snapshot == null || !isEphemeralEmptyDraft(id, snapshot, remoteIds, blockedIds)

    fun reusableEmptyDraftId(
        snapshots: Map<String, WakuSnapshot>,
        remoteIds: Set<String>,
        blockedIds: Set<String>,
        preferredId: String? = null,
    ): String? {
        if (preferredId != null) {
            val preferred = snapshots[preferredId]
            if (preferred != null && isEphemeralEmptyDraft(preferredId, preferred, remoteIds, blockedIds)) {
                return preferredId
            }
        }
        return snapshots.entries
            .filter { (id, snapshot) -> isEphemeralEmptyDraft(id, snapshot, remoteIds, blockedIds) }
            .maxByOrNull { (_, snapshot) -> snapshot.session.long("updated_at") }
            ?.key
    }

    fun redundantEmptyDraftIds(
        snapshots: Map<String, WakuSnapshot>,
        remoteIds: Set<String>,
        blockedIds: Set<String>,
        keepId: String?,
    ): List<String> = snapshots.entries
        .filter { (id, snapshot) -> id != keepId && isEphemeralEmptyDraft(id, snapshot, remoteIds, blockedIds) }
        .map { it.key }

    fun initialSelectedId(
        snapshots: Map<String, WakuSnapshot>,
        remoteIds: Set<String>,
        blockedIds: Set<String>,
        fallbackId: String,
    ): String = snapshots.entries
        .filterNot { (id, snapshot) -> isEphemeralEmptyDraft(id, snapshot, remoteIds, blockedIds) }
        .maxByOrNull { (_, snapshot) -> snapshot.session.long("updated_at") }
        ?.key
        ?: reusableEmptyDraftId(snapshots, remoteIds, blockedIds)
        ?: fallbackId
}
