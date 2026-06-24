package com.deerflow.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.deerflow.app.data.ConversationRepository
import com.deerflow.app.data.PendingAttachment
import com.deerflow.app.domain.ConversationState
import com.deerflow.app.domain.model.ThreadMeta
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin wrapper over [ConversationRepository]. Exposes multi-thread history list,
 * thread switching/deletion, and relays intents to the repository layer.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ConversationRepository.get(app)

    val state: StateFlow<ConversationState> = repo.state
    val threads: StateFlow<List<ThreadMeta>> = repo.threads
    val runningThreadIds: StateFlow<Set<String>> = repo.runningThreadIds

    fun submit(text: String, attachments: List<PendingAttachment> = emptyList()) {
        if (state.value.awaitingInterrupt) repo.resume(text) else repo.send(text, attachments)
    }

    fun cancel() = repo.cancel()
    fun newThread() = repo.newThread()
    fun selectThread(id: String) = repo.selectThread(id)
    fun deleteThread(id: String) = repo.deleteThread(id)
}
