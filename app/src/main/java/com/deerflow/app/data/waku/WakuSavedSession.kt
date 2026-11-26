package com.deerflow.app.data.waku

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Adopt server-externalized image references only for the projection actually acknowledged. */
object WakuSavedSession {
    fun merge(current: WakuSnapshot, submitted: JsonObject, confirmed: JsonObject): WakuSnapshot {
        require(confirmed.string("id") == submitted.string("id") &&
            WakuRecovery.historyRevision(confirmed) == WakuRecovery.historyRevision(submitted) &&
            (confirmed["runtime_event_cursor"] ?: JsonNull) == (submitted["runtime_event_cursor"] ?: JsonNull)) {
            "服务器会话状态已变化，请重新同步后核对"
        }
        return current.copy(
            session = if (current.session === submitted) confirmed else current.session,
            remoteKnown = true,
            remoteDeleted = false,
        )
    }
}
