package com.deerflow.app.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import com.deerflow.app.data.drafts.ComposerDraft
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.deerflow.app.R
import com.deerflow.app.data.PendingAttachment
import com.deerflow.app.data.proposal.SkillProposal
import com.deerflow.app.domain.ConversationState
import com.deerflow.app.domain.model.ThreadMeta
import com.deerflow.app.ui.proposal.ProposalCard
import com.deerflow.app.ui.proposal.ProposalViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    proposalVm: ProposalViewModel,
    onOpenApprovals: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val threads by vm.threads.collectAsStateWithLifecycle()
    val sessionTitle = threads.firstOrNull { it.id == state.threadId }?.title ?: "DeerFlow"
    val proposalState by proposalVm.state.collectAsStateWithLifecycle()
    val artifactHeaders by vm.artifactHeaders.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val canSend by vm.canSend.collectAsStateWithLifecycle()
    val permissions by vm.pendingPermissions.collectAsStateWithLifecycle()
    val questions by vm.pendingQuestions.collectAsStateWithLifecycle()
    val unknownSubmission by vm.unknownSubmission.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val composer by vm.composer.collectAsStateWithLifecycle()
    var reviewReset by rememberSaveable { mutableStateOf(0) }
    val connected by vm.isConnected.collectAsStateWithLifecycle()
    val profileKey by vm.profileKey.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val draftKey = profileKey + "|" + state.threadId
    val draft = composer.drafts[draftKey] ?: ComposerDraft()
    val draftReady = draftKey in composer.loaded && profileKey.isNotBlank()
    val attachments = draft.attachments.map { PendingAttachment(Uri.parse(it.uri), it.displayName, it.mimeType, it.sizeBytes, it.uploadToOss) }
    val taskFiles by vm.taskFiles.collectAsStateWithLifecycle()
    val transcriptListState = rememberLazyListState()
    val transcriptItemCount = remember(state.threadId) { mutableStateOf(0) }
    var filesVisible by rememberSaveable(state.threadId) { mutableStateOf(false) }
    if (filesVisible) {
        ArtifactsScreen(state = taskFiles, onBack = { filesVisible = false }, onRefresh = { vm.refreshArtifacts() },
            resolve = { vm.resolveArtifact(it, state.threadId) })
        return
    }
    val unavailableAttachments = composer.unavailable[draftKey].orEmpty()
    val draftError = composer.errors[draftKey]
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraName by rememberSaveable { mutableStateOf("") }
    var pickerDraftKey by rememberSaveable { mutableStateOf<String?>(null) }
    var renameThreadMeta by remember { mutableStateOf<ThreadMeta?>(null) }
    var renameText by remember { mutableStateOf("") }
    LaunchedEffect(draftKey) { vm.loadDraft(draftKey) }
    val threadProposals = remember(proposalState.proposals, state.threadId) {
        proposalState.proposals
            .filter { it.trigger.threadId == state.threadId }
            .filterNot { it.status in FINAL_PROPOSAL_STATUSES }
            .sortedBy { it.createdAt }
            .take(MAX_THREAD_PROPOSAL_CARDS)
    }

    LaunchedEffect(state.running) {
        if (!state.running) proposalVm.refresh()
    }
    LaunchedEffect(proposalState.error, proposalState.notice) {
        val message = proposalState.error ?: proposalState.notice ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        proposalVm.clearMessage()
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val selected = uris.mapNotNull { context.pendingAttachmentFromUri(it) }
        if (selected.size != uris.size) Toast.makeText(context, "部分附件无法访问，请重新选择", Toast.LENGTH_LONG).show()
        if (selected.isNotEmpty()) vm.addDraftAttachments(pickerDraftKey ?: draftKey, selected)
        pickerDraftKey = null
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val captured = pendingCameraUri?.let { PendingAttachment(Uri.parse(it), pendingCameraName, "image/jpeg", null) }
        if (success && captured != null) vm.addDraftAttachments(pickerDraftKey ?: draftKey, listOf(captured))
        pendingCameraUri = null
        pendingCameraName = ""
        pickerDraftKey = null
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = scheme.surface,
                drawerContentColor = scheme.onSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Prominent New Chat button inside the drawer, satisfying standard AI chat design
                    Button(
                        onClick = {
                            vm.newThread()
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.primaryContainer,
                            contentColor = scheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Chat")
                        Spacer(Modifier.width(8.dp))
                        Text("New Chat", fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))

                    val runningThreadIds by vm.runningThreadIds.collectAsStateWithLifecycle()
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(threads, key = { it.id }) { meta ->
                            val isActive = meta.id == state.threadId
                            val isRunning = meta.id in runningThreadIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isActive) scheme.primary.copy(alpha = 0.15f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        vm.selectThread(meta.id)
                                        coroutineScope.launch { drawerState.close() }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = meta.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isActive) scheme.primary else scheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isRunning) {
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 8.dp, end = 4.dp)
                                            .size(8.dp)
                                            .background(scheme.primary, CircleShape)
                                    )
                                }
                                if (!meta.id.startsWith("legacy:")) {
                                    IconButton(
                                        onClick = {
                                            renameThreadMeta = meta
                                            renameText = meta.title
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Rename thread",
                                            tint = scheme.onSurface.copy(alpha = 0.4f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { vm.deleteThread(meta.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete thread",
                                        tint = scheme.onSurface.copy(alpha = 0.4f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = scheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            sessionTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = scheme.background,
                        titleContentColor = scheme.onBackground
                    ),
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = scheme.onBackground)
                        }
                    },
                    actions = {
                        TextButton(onClick = { filesVisible = true; vm.refreshArtifacts() }) {
                            Text(if (taskFiles.files.isEmpty()) "产物" else "产物 ${taskFiles.files.size}")
                        }
                        IconButton(onClick = vm::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新同步会话和产物")
                        }
                        ProposalInboxButton(
                            pendingCount = proposalState.pendingCount,
                            onClick = onOpenApprovals,
                        )
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = scheme.onBackground.copy(alpha = 0.7f))
                        }
                    },
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .background(scheme.background)
                        .imePadding()
                ) {
                    val hasConnectionNotice = !connected || (connection.isNotBlank() && connection != "已连接" && unknownSubmission?.contains(connection) != true)
                    if (hasConnectionNotice || unknownSubmission != null) {
                        Column(Modifier.fillMaxWidth().background(scheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp)) {
                            if (hasConnectionNotice) {
                                Text(connection, style = MaterialTheme.typography.bodySmall)
                                if (!connected) {
                                    Row {
                                        TextButton(onClick = vm::refresh) { Text("重新同步") }
                                        TextButton(onClick = onOpenSettings) { Text("连接设置") }
                                    }
                                }
                            }
                            unknownSubmission?.let { pending ->
                                Text("这次操作的结果尚未确认。请先核对会话记录，避免重复执行。", style = MaterialTheme.typography.bodySmall)
                                Text(pending, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(pending)) }) { Text("复制原文") }
                                    TextButton(onClick = { vm.restorePendingToDraft(draftKey) }) { Text("恢复到输入框") }
                                    TextButton(onClick = { vm.acknowledgeUnknown(); reviewReset++ }) { Text("解除锁定") }
                                }
                            }
                        }
                    }
                    PendingActions(
                        permissions = permissions, requests = questions, enabled = connected, resetKey = reviewReset,
                        onPermission = vm::respondPermission, onAnswers = vm::respondUserInput,
                    )
                    StatusBar(state)
                    if (draftError != null || unavailableAttachments.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            if (draftError != null) Text(draftError, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                            if (unavailableAttachments.isNotEmpty()) Text(
                                "以下附件已不可访问，请移除或重新选择：" + attachments.filter { it.uri.toString() in unavailableAttachments }.joinToString { it.displayName },
                                color = scheme.error, style = MaterialTheme.typography.bodySmall,
                            )
                            Row {
                                TextButton(onClick = { vm.retryDraft(draftKey) }) { Text("重试草稿") }
                                TextButton(onClick = { vm.clearDraft(draftKey) }) { Text("清空草稿") }
                            }
                        }
                    }
                    InputBar(
                        running = state.running,
                        awaitingInterrupt = false,
                        canSend = canSend && draftReady && unavailableAttachments.isEmpty() && draftError == null && draftKey !in composer.checkingAttachments && draftKey !in composer.submitting,
                        editable = draftReady,
                        text = draft.text,
                        onTextChanged = { vm.updateDraftText(draftKey, it) },
                        attachments = attachments,
                        onOssChanged = { vm.setDraftOss(draftKey, it) },
                        onSubmit = { text, files ->
                            val accepted = vm.submit(text, files, draftKey)

                            accepted
                        },
                        onCancel = vm::cancel,
                        onPickFiles = { pickerDraftKey = draftKey; filePicker.launch(arrayOf("*/*")) },
                        onTakePhoto = {
                            context.createCameraAttachment()?.let { pending ->
                                pendingCameraUri = pending.uri.toString()
                                pendingCameraName = pending.displayName
                                pickerDraftKey = draftKey
                                try {
                                    cameraLauncher.launch(pending.uri)
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "未找到相机应用", Toast.LENGTH_SHORT).show()
                                    pendingCameraUri = null
                                    pendingCameraName = ""
                                    pickerDraftKey = null
                                }
                            }
                        },
                        onRemoveAttachment = { attachment ->
                            vm.updateDraftAttachments(draftKey, attachments.filterNot { it.uri == attachment.uri })
                        },
                    )
                }
            },
        ) { padding ->
            Transcript(
                state = state,
                listState = transcriptListState,
                itemCount = transcriptItemCount,
                artifactHeaders = artifactHeaders,
                resolveMessageFile = { vm.resolveMessageFile(it, state.threadId) },
                proposals = threadProposals,
                actionProposalId = proposalState.actionProposalId,
                onViewProposal = { proposalId ->
                    proposalVm.select(proposalId)
                    onOpenApprovals()
                },
                onApproveProposal = proposalVm::approve,
                onRejectProposal = proposalVm::reject,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (renameThreadMeta != null) {
        AlertDialog(
            onDismissRequest = { renameThreadMeta = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("会话标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = renameThreadMeta
                        if (target != null && renameText.isNotBlank()) {
                            vm.renameThread(target.id, renameText.trim())
                        }
                        renameThreadMeta = null
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameThreadMeta = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private sealed class TranscriptItem {
    abstract val key: String
    data class BlockItem(val block: com.deerflow.app.domain.DisplayBlock) : TranscriptItem() {
        override val key: String get() = block.key
    }
    data class ProposalItem(val proposal: SkillProposal) : TranscriptItem() {
        override val key: String get() = "proposal:${proposal.id}"
    }
}

@Composable
private fun Transcript(
    state: ConversationState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemCount: androidx.compose.runtime.MutableState<Int>,
    artifactHeaders: Map<String, String>,
    resolveMessageFile: suspend (com.deerflow.app.domain.model.MessageFile) -> com.deerflow.app.domain.model.AgentArtifact,
    proposals: List<SkillProposal>,
    actionProposalId: String?,
    onViewProposal: (String) -> Unit,
    onApproveProposal: (String, String?) -> Unit,
    onRejectProposal: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var previousItemCount by itemCount

    // Map each proposal ID to its anchor block index when first introduced in this thread session.
    val proposalAnchors = remember(state.threadId) { mutableMapOf<String, Int>() }
    val itemsList = remember(state.blocks, proposals, state.threadId) {
        proposals.forEach { proposal ->
            if (!proposalAnchors.containsKey(proposal.id)) {
                proposalAnchors[proposal.id] = state.blocks.size
            }
        }

        val proposalsByAnchor = proposals.groupBy { proposal ->
            (proposalAnchors[proposal.id] ?: state.blocks.size).coerceIn(0, state.blocks.size)
        }

        val result = mutableListOf<TranscriptItem>()
        for (i in 0..state.blocks.size) {
            proposalsByAnchor[i]?.sortedBy { it.createdAt }?.forEach { proposal ->
                result.add(TranscriptItem.ProposalItem(proposal))
            }
            if (i < state.blocks.size) {
                result.add(TranscriptItem.BlockItem(state.blocks[i]))
            }
        }
        result
    }

    LaunchedEffect(itemsList.size, state.threadId) {
        val currentItemCount = itemsList.size
        val itemsWereAdded = currentItemCount > previousItemCount
        previousItemCount = currentItemCount
        if (itemsWereAdded) listState.animateScrollToItem(currentItemCount - 1)
    }

    if (itemsList.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.deerflow_logo),
                contentDescription = "DeerFlow Logo",
                modifier = Modifier
                    .size(130.dp)
                    .clip(RoundedCornerShape(28.dp))
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "DeerFlow",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No messages yet. Type to start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        items(itemsList, key = { it.key }) { item ->
            when (item) {
                is TranscriptItem.BlockItem -> {
                    val isLastBlock = state.blocks.lastOrNull()?.key == item.block.key
                    val streamingKind = item.block.kind in STREAMING_KINDS
                    val isStreaming = state.running && isLastBlock && streamingKind
                    BlockCard(
                        block = item.block,
                        artifactHeaders = artifactHeaders,
                        isStreaming = isStreaming,
                        resolveMessageFile = resolveMessageFile,
                    )
                }
                is TranscriptItem.ProposalItem -> ProposalCard(
                    proposal = item.proposal,
                    busy = actionProposalId == item.proposal.id,
                    compact = true,
                    onView = { onViewProposal(item.proposal.id) },
                    onApprove = { onApproveProposal(item.proposal.id, null) },
                    onReject = { onRejectProposal(item.proposal.id, null) },
                )
            }
        }
    }
}

@Composable
private fun ProposalInboxButton(pendingCount: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (pendingCount > 0) {
                    Badge { Text(if (pendingCount > 99) "99+" else pendingCount.toString()) }
                }
            },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Rule,
                contentDescription = "Proposal 审批",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
        }
    }
}

private const val MAX_THREAD_PROPOSAL_CARDS = 3

private val FINAL_PROPOSAL_STATUSES = setOf(
    "published",
    "rejected",
    "failed",
    "stale",
)

private val STREAMING_KINDS = setOf(
    com.deerflow.app.domain.BlockKind.ASSISTANT,
    com.deerflow.app.domain.BlockKind.TOOL,
    com.deerflow.app.domain.BlockKind.THINKING,
    com.deerflow.app.domain.BlockKind.REASONING,
)

@Composable
private fun StatusBar(state: ConversationState) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusColor = if (state.running) scheme.primary else scheme.onSurface.copy(alpha = 0.35f)
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, shape = CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = state.status,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurface.copy(alpha = 0.7f)
            )
        }
        Text(
            text = "msgs: ${state.messageCount}  -  ${state.threadId.take(12)}",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun InputBar(
    running: Boolean,
    awaitingInterrupt: Boolean,
    canSend: Boolean,
    editable: Boolean,
    text: String,
    onTextChanged: (String) -> Unit,
    attachments: List<PendingAttachment>,
    onOssChanged: (Boolean) -> Unit,
    onSubmit: (String, List<PendingAttachment>) -> Boolean,
    onCancel: () -> Unit,
    onPickFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveAttachment: (PendingAttachment) -> Unit,
) {
    var toolsExpanded by remember { mutableStateOf(false) }
    val placeholder = if (awaitingInterrupt) "Reply to interrupt..." else "Type a message..."
    val scheme = MaterialTheme.colorScheme
    val canAttach = editable && !running && !awaitingInterrupt
    val context = LocalContext.current

    fun appendSpeechText(spokenText: String) {
        val trimmed = spokenText.trim()
        if (trimmed.isNotEmpty()) {
            onTextChanged(if (text.isBlank()) trimmed else "${text.trimEnd()} $trimmed")
        }
    }

    val speechInputLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            appendSpeechText(spokenText)
        }
    }

    fun requestSpeechInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        }
        val canHandleSpeechInput = intent.resolveActivity(context.packageManager) != null
        if (!canHandleSpeechInput) {
            Toast.makeText(context, "Speech recognition is not available", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            speechInputLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Speech recognition is not available", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(canAttach) {
        if (!canAttach) toolsExpanded = false
    }

    fun send() {
        val t = text.trim()
        val hasPayload = if (awaitingInterrupt) t.isNotEmpty() else t.isNotEmpty() || attachments.isNotEmpty()
        if (hasPayload && canSend && !running) {
            if (!onSubmit(t, if (awaitingInterrupt) emptyList() else attachments)) return

            toolsExpanded = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
    ) {
        if (attachments.isNotEmpty() && !awaitingInterrupt) {
            AttachmentRow(
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("上传到", style = MaterialTheme.typography.labelMedium)
                FilterChip(selected = attachments.none { it.uploadToOss }, onClick = { onOssChanged(false) },
                    enabled = editable && !running, label = { Text("服务器") })
                FilterChip(selected = attachments.all { it.uploadToOss }, onClick = { onOssChanged(true) },
                    enabled = editable && !running, label = { Text("OSS") })
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!awaitingInterrupt) {
                IconButton(
                    onClick = { toolsExpanded = !toolsExpanded },
                    enabled = canAttach,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (toolsExpanded) scheme.primaryContainer else scheme.surfaceVariant,
                        contentColor = if (toolsExpanded) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "More input options",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                enabled = editable,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        placeholder,
                        color = scheme.onSurface.copy(alpha = 0.4f)
                    )
                },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = scheme.primary.copy(alpha = 0.8f),
                    unfocusedBorderColor = scheme.outline.copy(alpha = 0.4f),
                    focusedContainerColor = scheme.surface,
                    unfocusedContainerColor = scheme.surface,
                    focusedTextColor = scheme.onSurface,
                    unfocusedTextColor = scheme.onSurface
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
            )

            val speechEnabled = editable && !running
            IconButton(
                onClick = { requestSpeechInput() },
                enabled = speechEnabled,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = scheme.surfaceVariant,
                    contentColor = scheme.onSurfaceVariant,
                    disabledContainerColor = scheme.surfaceVariant.copy(alpha = 0.55f),
                    disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.35f),
                ),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Start speech input",
                    modifier = Modifier.size(20.dp),
                )
            }

            if (running) {
                IconButton(
                    onClick = onCancel,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = scheme.errorContainer,
                        contentColor = scheme.onErrorContainer
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Cancel run",
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                val sendEnabled = canSend && (text.trim().isNotEmpty() || attachments.isNotEmpty())
                IconButton(
                    onClick = { send() },
                    enabled = sendEnabled,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (sendEnabled) scheme.primary else scheme.surfaceVariant,
                        contentColor = if (sendEnabled) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (toolsExpanded && canAttach) {
            AttachmentToolPanel(
                onPickFiles = {
                    toolsExpanded = false
                    onPickFiles()
                },
                onTakePhoto = {
                    toolsExpanded = false
                    onTakePhoto()
                },
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun AttachmentToolPanel(
    onPickFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        HorizontalDivider(color = scheme.outline.copy(alpha = 0.16f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InputToolButton(
                label = "Upload",
                icon = {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = onPickFiles,
                modifier = Modifier.weight(1f),
            )
            InputToolButton(
                label = "Camera",
                icon = {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = onTakePhoto,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun InputToolButton(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.75f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(scheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentRow(
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (PendingAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            InputChip(
                selected = false,
                onClick = {},
                label = {
                    Text(
                        text = attachment.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = { onRemoveAttachment(attachment) },
                        modifier = Modifier.size(18.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove attachment",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                },
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}

private fun Context.pendingAttachmentFromUri(uri: Uri): PendingAttachment? {
    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    var displayName: String? = null
    var sizeBytes: Long? = null

    if (uri.scheme == "content") {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
            }
        }
    }

    val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val name = displayName?.takeIf { it.isNotBlank() } ?: fallbackName ?: "attachment-${System.currentTimeMillis()}"
    return PendingAttachment(
        uri = uri,
        displayName = name,
        mimeType = contentResolver.getType(uri),
        sizeBytes = sizeBytes,
    )
}

private fun Context.createCameraAttachment(): PendingAttachment? {
    val dir = File(cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("photo_", ".jpg", dir)
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    return PendingAttachment(
        uri = uri,
        displayName = file.name,
        mimeType = "image/jpeg",
        sizeBytes = null,
    )
}
