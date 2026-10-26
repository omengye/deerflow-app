package com.deerflow.app.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deerflow.app.data.ConversationRepository
import com.deerflow.app.data.PendingAttachment
import com.deerflow.app.data.drafts.ComposerDraft
import com.deerflow.app.data.drafts.ComposerDraftStore
import com.deerflow.app.data.drafts.DraftAttachment
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

data class ComposerState(
    val drafts: Map<String, ComposerDraft> = emptyMap(),
    val loaded: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val unavailable: Map<String, Set<String>> = emptyMap(),
    val checkingAttachments: Set<String> = emptySet(),
    val submitting: Set<String> = emptySet(),
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ConversationRepository.get(app)
    private val draftStore = ComposerDraftStore(File(app.filesDir, "composer_drafts"))
    private val mutableComposer = MutableStateFlow(ComposerState())
    val composer = mutableComposer.asStateFlow()
    private val loading = mutableSetOf<String>()
    private val saves = mutableMapOf<String, Job>()
    private val revisions = mutableMapOf<String, Long>()
    val state = repo.state
    val threads = repo.threads
    val runningThreadIds = repo.runningThreadIds
    val artifactHeaders = repo.artifactHeaders
    val connection = repo.connection
    val isConnected = repo.isConnected
    val profileKey = repo.profileKey
    val pendingPermissions = repo.pendingPermissions
    val pendingQuestions = repo.pendingQuestions
    val canSend = repo.canSend
    val unknownSubmission = repo.unknownSubmission
    fun acknowledgeUnknown() = repo.acknowledgeUnknown()

    fun loadDraft(key: String) {
        if (key in mutableComposer.value.loaded || !loading.add(key)) return
        val revision = revisions[key] ?: 0
        viewModelScope.launch {
            try {
                val draft = draftStore.load(key)
                val missing = unavailableFiles(draft.attachments)
                if ((revisions[key] ?: 0) != revision) return@launch
                val current = mutableComposer.value
                mutableComposer.value = current.copy(
                    drafts = current.drafts + (key to draft), loaded = current.loaded + key,
                    unavailable = current.unavailable + (key to missing), errors = current.errors - key,
                )
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutableComposer.value = mutableComposer.value.copy(errors = mutableComposer.value.errors + (key to "无法读取已保存草稿。请重试，或核对后清空草稿。"))
            } finally { loading.remove(key) }
        }
    }
    fun updateDraftText(key: String, text: String) {
        if (key !in mutableComposer.value.loaded) return
        setDraft(key, (mutableComposer.value.drafts[key] ?: ComposerDraft()).copy(text = text))
    }
    fun updateDraftAttachments(key: String, attachments: List<PendingAttachment>) {
        if (key !in mutableComposer.value.loaded) return
        val encoded = attachments.map { DraftAttachment(it.uri.toString(), it.displayName, it.mimeType, it.sizeBytes) }
        setDraft(key, (mutableComposer.value.drafts[key] ?: ComposerDraft()).copy(attachments = encoded))
        mutableComposer.value = mutableComposer.value.copy(checkingAttachments = mutableComposer.value.checkingAttachments + key)
        viewModelScope.launch {
            val missing = unavailableFiles(encoded)
            if (mutableComposer.value.drafts[key]?.attachments == encoded) {
                mutableComposer.value = mutableComposer.value.copy(unavailable = mutableComposer.value.unavailable + (key to missing), checkingAttachments = mutableComposer.value.checkingAttachments - key)
            }
        }
    }
    fun addDraftAttachments(key: String, attachments: List<PendingAttachment>) {
        if (key !in mutableComposer.value.loaded) {
            viewModelScope.launch {
                loadDraft(key)
                val loaded = composer.first { key in it.loaded || key in it.errors }
                if (key in loaded.loaded) addDraftAttachments(key, attachments)
                else mutableComposer.value = mutableComposer.value.copy(errors = mutableComposer.value.errors + (key to "无法读取原草稿，附件尚未加入。请重试草稿后重新选择附件。"))
            }
            return
        }
        val existing = mutableComposer.value.drafts[key]?.attachments.orEmpty().map { PendingAttachment(Uri.parse(it.uri), it.displayName, it.mimeType, it.sizeBytes) }
        updateDraftAttachments(key, existing + attachments)
    }
    fun retryDraft(key: String) {
        if (key !in mutableComposer.value.loaded) loadDraft(key)
        else {
            val draft = mutableComposer.value.drafts[key] ?: ComposerDraft()
            setDraft(key, draft, immediate = true)
            updateDraftAttachments(key, draft.attachments.map { PendingAttachment(Uri.parse(it.uri), it.displayName, it.mimeType, it.sizeBytes) })
        }
    }
    fun clearDraft(key: String) {
        val current = mutableComposer.value
        mutableComposer.value = current.copy(loaded = current.loaded + key, errors = current.errors - key, unavailable = current.unavailable - key, checkingAttachments = current.checkingAttachments - key)
        setDraft(key, ComposerDraft(), immediate = true)
    }
    private fun setDraft(key: String, draft: ComposerDraft, immediate: Boolean = false) {
        val current = mutableComposer.value
        mutableComposer.value = current.copy(drafts = current.drafts + (key to draft))
        val revision = (revisions[key] ?: 0) + 1
        revisions[key] = revision
        saves.remove(key)?.cancel()
        saves[key] = viewModelScope.launch {
            try {
                if (!immediate) delay(250)
                draftStore.save(key, draft)
                if (revisions[key] == revision) mutableComposer.value = mutableComposer.value.copy(errors = mutableComposer.value.errors - key)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (revisions[key] == revision) mutableComposer.value = mutableComposer.value.copy(errors = mutableComposer.value.errors + (key to "草稿尚未保存到设备，请保留当前界面并重试"))
            }
        }
    }
    private fun flushDrafts() {
        mutableComposer.value.drafts.forEach { (key, draft) -> setDraft(key, draft, immediate = true) }
    }
    private suspend fun unavailableFiles(attachments: List<DraftAttachment>): Set<String> = withContext(Dispatchers.IO) {
        attachments.filter { attachment ->
            runCatching {
                getApplication<Application>().contentResolver.openFileDescriptor(Uri.parse(attachment.uri), "r")?.use { true } ?: false
            }.getOrDefault(false).not()
        }.mapTo(mutableSetOf()) { it.uri }
    }
    fun submit(text: String, attachments: List<PendingAttachment> = emptyList(), draftKey: String? = null): Boolean {
        if (!canSend.value) return false
        val key = draftKey ?: (profileKey.value + "|" + state.value.threadId)
        val composer = mutableComposer.value
        if (key in composer.submitting) return false
        if (draftKey != null && (key !in composer.loaded || key in composer.errors || key in composer.checkingAttachments || composer.unavailable[key].orEmpty().isNotEmpty())) return false
        val revision = revisions[key] ?: 0
        mutableComposer.value = composer.copy(submitting = composer.submitting + key)
        try {
            val job = repo.send(text, attachments) {
                viewModelScope.launch {
                    // Do not erase text or attachments edited after the user pressed Send.
                    if ((revisions[key] ?: 0) == revision) clearDraft(key)
                }
            }
            job.invokeOnCompletion {
                viewModelScope.launch { mutableComposer.value = mutableComposer.value.copy(submitting = mutableComposer.value.submitting - key) }
            }
        } catch (_: Exception) {
            mutableComposer.value = mutableComposer.value.copy(submitting = mutableComposer.value.submitting - key)
            return false
        }
        return true
    }
    fun cancel() = repo.cancel()
    fun newThread() = repo.newThread()
    fun selectThread(id: String) = repo.selectThread(id)
    fun deleteThread(id: String) {
        clearDraft(profileKey.value + "|" + id)
        repo.deleteThread(id)
    }
    fun refresh() = repo.refresh()
    fun setForeground(value: Boolean) { if (!value) flushDrafts(); repo.setForeground(value) }
    fun respondPermission(requestId: String, optionId: String) = repo.respondPermission(requestId, optionId)
    fun respondUserInput(requestId: String, answers: JsonArray) = repo.respondUserInput(requestId, answers)
}