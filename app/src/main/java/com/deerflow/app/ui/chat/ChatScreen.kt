package com.deerflow.app.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.deerflow.app.R
import com.deerflow.app.data.PendingAttachment
import com.deerflow.app.data.drafts.ComposerDraft
import com.deerflow.app.data.proposal.SkillProposal
import com.deerflow.app.data.proposal.isActive
import com.deerflow.app.domain.BlockKind
import com.deerflow.app.domain.ConversationState
import com.deerflow.app.domain.GroupedTranscriptEntry
import com.deerflow.app.domain.ToolGroup
import com.deerflow.app.domain.TranscriptGrouping
import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.MessageFile
import com.deerflow.app.domain.model.ThreadMeta
import com.deerflow.app.ui.proposal.ProposalCard
import com.deerflow.app.ui.proposal.ProposalViewModel
import com.deerflow.app.ui.theme.deerflowColors
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    proposalVm: ProposalViewModel,
    onOpenArtifacts: () -> Unit,
    onOpenEvolution: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val threads by vm.threads.collectAsStateWithLifecycle()
    val runningThreadIds by vm.runningThreadIds.collectAsStateWithLifecycle()
    val deletion by vm.deletion.collectAsStateWithLifecycle()
    val activeThread = threads.firstOrNull { it.id == state.threadId }
    val sessionTitle = activeThread?.title?.takeUnless { it == "New task" } ?: "新对话"
    val proposalState by proposalVm.state.collectAsStateWithLifecycle()
    val artifactHeaders by vm.artifactHeaders.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val canSend by vm.canSend.collectAsStateWithLifecycle()
    val permissions by vm.pendingPermissions.collectAsStateWithLifecycle()
    val questions by vm.pendingQuestions.collectAsStateWithLifecycle()
    val submission by vm.submissionStatus.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val composer by vm.composer.collectAsStateWithLifecycle()
    var reviewReset by rememberSaveable { mutableStateOf(0) }
    val connected by vm.isConnected.collectAsStateWithLifecycle()
    val profileKey by vm.profileKey.collectAsStateWithLifecycle()
    val secureCredentialTransport by vm.secureCredentialTransport.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.deerflowColors
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
    val allowInputFocus = remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val proposalLifecycleOwner = LocalLifecycleOwner.current

    fun dismissInputFocus() {
        allowInputFocus.value = false
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(state.threadId) {
        dismissInputFocus()
    }
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) dismissInputFocus()
    }
    DisposableEffect(proposalLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) dismissInputFocus()
        }
        proposalLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { proposalLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val unavailableAttachments = composer.unavailable[draftKey].orEmpty()
    val draftError = composer.errors[draftKey]
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraName by rememberSaveable { mutableStateOf("") }
    var pickerDraftKey by rememberSaveable { mutableStateOf<String?>(null) }
    var renameThreadMeta by remember { mutableStateOf<ThreadMeta?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteConfirmMeta by remember { mutableStateOf<ThreadMeta?>(null) }
    var drawerSearch by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(draftKey) { vm.loadDraft(draftKey) }

    val threadProposals = remember(proposalState.proposals, state.threadId) {
        proposalState.proposals
            .filter { it.trigger.threadId == state.threadId }
            .filter { it.isActive }
            .sortedBy { it.createdAt }
            .take(MAX_THREAD_PROPOSAL_CARDS)
    }
    val activeProposalCount = remember(proposalState.proposals) {
        proposalState.proposals.count { it.isActive }
    }

    val hasActiveProposals = threadProposals.isNotEmpty()
    LaunchedEffect(proposalLifecycleOwner, hasActiveProposals) {
        proposalLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                proposalVm.refresh()
                if (hasActiveProposals) delay(30_000)
            } while (hasActiveProposals)
        }
    }

    LaunchedEffect(state.running, state.threadId) {
        if (!state.running) proposalVm.refresh()
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
                drawerContainerColor = scheme.surfaceContainerLow,
                drawerContentColor = scheme.onSurface,
                modifier = Modifier.widthIn(max = 316.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.deerflow_logo),
                                contentDescription = "DeerFlow",
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Text(
                                text = "DeerFlow",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurface,
                            )
                        }
                        Surface(
                            color = if (connected) semantic.successContainer else scheme.surfaceContainerHigh,
                            contentColor = if (connected) semantic.onSuccessContainer else scheme.onSurfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (connected) semantic.success else scheme.error),
                                )
                                Text(
                                    text = if (connected) "已连接" else "未连接",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = {
                            vm.newThread()
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.primaryContainer,
                            contentColor = scheme.onPrimaryContainer,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("新建对话", fontWeight = FontWeight.SemiBold)
                    }

                    if (threads.size > 5) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = drawerSearch,
                            onValueChange = { drawerSearch = it },
                            placeholder = { Text("搜索会话…", style = MaterialTheme.typography.bodySmall) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                if (drawerSearch.isNotEmpty()) {
                                    IconButton(onClick = { drawerSearch = "" }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "清除搜索", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = scheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))

                    val todayBucket = remember(threads) { LocalDate.now().toEpochDay() }
                    val groupedThreads = remember(threads, drawerSearch, todayBucket) {
                        groupThreadsByRecency(threads, drawerSearch)
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (groupedThreads.isEmpty()) {
                            item(key = "empty_threads") {
                                Text(
                                    text = if (drawerSearch.isBlank()) "暂无历史会话" else "没有匹配的会话",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                                )
                            }
                        } else {
                            groupedThreads.forEach { section ->
                                item(key = "section:${section.label}") {
                                    Text(
                                        text = section.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                items(section.items, key = { "thread:${it.id}" }) { meta ->
                                    val isActive = meta.id == state.threadId
                                    val isRunning = meta.id in runningThreadIds
                                    var menuOpen by remember(meta.id) { mutableStateOf(false) }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isActive) scheme.primaryContainer.copy(alpha = 0.65f)
                                                else Color.Transparent,
                                            )
                                            .clickable {
                                                vm.selectThread(meta.id)
                                                coroutineScope.launch { drawerState.close() }
                                            }
                                            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (isRunning) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.padding(end = 8.dp).size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = scheme.primary,
                                            )
                                        } else if (meta.remoteDeleted) {
                                            Icon(
                                                imageVector = Icons.Default.CloudOff,
                                                contentDescription = "服务端已删除",
                                                tint = semantic.warning,
                                                modifier = Modifier.padding(end = 8.dp).size(15.dp),
                                            )
                                        }
                                        Text(
                                            text = meta.title.takeUnless { it == "New task" } ?: "新对话",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                            color = when {
                                                isActive -> scheme.onPrimaryContainer
                                                meta.remoteDeleted -> scheme.onSurfaceVariant
                                                else -> scheme.onSurface
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (!meta.id.startsWith("legacy:")) {
                                            Box {
                                                IconButton(
                                                    onClick = { menuOpen = true },
                                                    modifier = Modifier.size(36.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.MoreVert,
                                                        contentDescription = "会话操作",
                                                        tint = scheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                                DropdownMenu(
                                                    expanded = menuOpen,
                                                    onDismissRequest = { menuOpen = false },
                                                ) {
                                                    if (!meta.remoteDeleted) {
                                                        DropdownMenuItem(
                                                            text = { Text("重命名") },
                                                            leadingIcon = {
                                                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                                            },
                                                            onClick = {
                                                                menuOpen = false
                                                                renameThreadMeta = meta
                                                                renameText = meta.title
                                                            },
                                                        )
                                                    }
                                                    DropdownMenuItem(
                                                        text = { Text("删除会话", color = scheme.error) },
                                                        leadingIcon = {
                                                            Icon(Icons.Default.Delete, contentDescription = null, tint = scheme.error, modifier = Modifier.size(18.dp))
                                                        },
                                                        enabled = deletion?.inProgress != true,
                                                        onClick = {
                                                            menuOpen = false
                                                            deleteConfirmMeta = meta
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = scheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))

                    DrawerNavItem(
                        icon = Icons.Default.AutoAwesome,
                        label = "技能演进",
                        badgeText = activeProposalCount.takeIf { it > 0 }?.toString(),
                        showDot = false,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onOpenEvolution()
                        },
                    )
                    DrawerNavItem(
                        icon = Icons.Default.Psychology,
                        label = "个人记忆",
                        badgeText = null,
                        showDot = false,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onOpenMemory()
                        },
                    )
                    DrawerNavItem(
                        icon = Icons.Default.Settings,
                        label = "设置",
                        badgeText = null,
                        showDot = !connected,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onOpenSettings()
                        },
                    )
                }
            }
        },
    ) {
        val (statusDotColor, statusLabel) = remember(connected, connection, activeThread?.remoteDeleted, state.status, state.running) {
            resolveHeaderStatus(
                connected = connected,
                connection = connection,
                remoteDeleted = activeThread?.remoteDeleted == true,
                stateStatus = state.status,
                running = state.running,
                successColor = semantic.success,
                warningColor = semantic.warning,
                primaryColor = scheme.primary,
                errorColor = scheme.error,
            )
        }

        Scaffold(
            containerColor = scheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = sessionTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (activeThread?.remoteDeleted == true) {
                                    Icon(
                                        imageVector = Icons.Default.CloudOff,
                                        contentDescription = "服务端已删除",
                                        tint = semantic.warning,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(statusDotColor),
                                )
                                Text(
                                    text = statusLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = scheme.background,
                        titleContentColor = scheme.onBackground,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "打开历史抽屉", tint = scheme.onBackground)
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenArtifacts) {
                            if (taskFiles.files.isNotEmpty()) {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = scheme.primary, contentColor = scheme.onPrimary) {
                                            Text(taskFiles.files.size.toString())
                                        }
                                    },
                                ) {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = "会话产物")
                                }
                            } else {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = "会话产物")
                            }
                        }
                        IconButton(onClick = vm::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新同步会话和产物")
                        }
                        IconButton(onClick = vm::newThread) {
                            Icon(Icons.Outlined.AddComment, contentDescription = "新建对话")
                        }
                    },
                )
            },
            bottomBar = {
                val protocolStates = remember {
                    setOf("", "idle", "Idle", "connecting", "working", "waiting", "background", "failed")
                }
                val hasConnectionNotice = !connected ||
                    (connection.isNotBlank() && connection != "已连接" && submission.issue?.contains(connection) != true)
                val statusNotice = state.status.takeIf { it !in protocolStates && it != connection }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(scheme.background)
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                ) {
                    if (hasConnectionNotice || statusNotice != null || submission.issue != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = scheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, scheme.outlineVariant),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (hasConnectionNotice) {
                                    Text(connection, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                                    if (!connected) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = vm::refresh) { Text("重新同步") }
                                            TextButton(onClick = onOpenSettings) { Text("连接设置") }
                                        }
                                    }
                                }
                                if (statusNotice != null) {
                                    Text(statusNotice, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                                submission.issue?.let { pending ->
                                    Text(
                                        text = when {
                                            submission.needsReview -> "这次操作的结果尚未确认。请先核对会话记录，避免重复执行。"
                                            submission.canRestorePrompt -> "消息未发送成功，原文已保留。"
                                            else -> "操作未完成。"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.error,
                                    )
                                    Text(
                                        text = pending,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurface,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (submission.canRestorePrompt) {
                                            TextButton(onClick = { clipboard.setText(AnnotatedString(pending)) }) { Text("复制原文") }
                                            TextButton(
                                                enabled = submission.canResolve,
                                                onClick = { vm.restorePendingToDraft(draftKey) },
                                            ) { Text("恢复到输入框") }
                                        }
                                        TextButton(
                                            enabled = submission.canResolve,
                                            onClick = { vm.acknowledgeUnknown(); reviewReset++ },
                                        ) {
                                            Text(if (submission.needsReview) "已核对" else "关闭")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    PendingActions(
                        permissions = permissions,
                        requests = questions,
                        enabled = connected,
                        resetKey = reviewReset,
                        secureCredentialTransport = secureCredentialTransport,
                        onCredential = vm::respondCredential,
                        onPermission = vm::respondPermission,
                        onAnswers = vm::respondUserInput,
                    )

                    if (draftError != null || unavailableAttachments.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = scheme.errorContainer,
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                if (draftError != null) {
                                    Text(draftError, color = scheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                                }
                                if (unavailableAttachments.isNotEmpty()) {
                                    Text(
                                        text = "以下附件已不可访问，请移除或重新选择：" +
                                            attachments.filter { it.uri.toString() in unavailableAttachments }.joinToString { it.displayName },
                                        color = scheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Row {
                                    TextButton(onClick = { vm.retryDraft(draftKey) }) { Text("重试草稿") }
                                    TextButton(onClick = { vm.clearDraft(draftKey) }) { Text("清空草稿") }
                                }
                            }
                        }
                    }

                    InputBar(
                        running = state.running,
                        awaitingInterrupt = false,
                        canSend = canSend && draftReady && unavailableAttachments.isEmpty() && draftError == null &&
                            draftKey !in composer.checkingAttachments && draftKey !in composer.submitting,
                        editable = draftReady,
                        text = draft.text,
                        allowFocusState = allowInputFocus,
                        onRejectUnrequestedFocus = ::dismissInputFocus,
                        onTextChanged = { vm.updateDraftText(draftKey, it) },
                        attachments = attachments,
                        onOssChanged = { vm.setDraftOss(draftKey, it) },
                        onSubmit = { text, files ->
                            val accepted = vm.submit(text, files, draftKey)
                            if (accepted) dismissInputFocus()
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (allowInputFocus.value) dismissInputFocus()
                        }
                    },
            ) {
                Transcript(
                    state = state,
                    listState = transcriptListState,
                    itemCount = transcriptItemCount,
                    connected = connected,
                    connectionText = connection,
                    artifactHeaders = artifactHeaders,
                    resolveMessageFile = { vm.resolveMessageFile(it, state.threadId) },
                    resolveGeneratedImage = { reference, messageId -> vm.resolveGeneratedImage(reference, messageId, state.threadId, profileKey) },
                    imageScopeKey = draftKey,
                    imageReloadKey = connected,
                    proposals = threadProposals,
                    onViewProposal = { proposalId ->
                        proposalVm.select(proposalId)
                        onOpenEvolution()
                    },
                    onOpenSettings = onOpenSettings,
                    onRefresh = vm::refresh,
                    onPickSuggestion = { suggestion ->
                        if (draftReady) {
                            vm.updateDraftText(draftKey, suggestion)
                            allowInputFocus.value = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    deleteConfirmMeta?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmMeta = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = scheme.surfaceContainerLow,
            title = { Text("删除会话？") },
            text = {
                Text("将删除「${target.title.takeUnless { it == "New task" } ?: "新对话"}」的服务端会话及本地记录，此操作不可恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteConfirmMeta = null
                        vm.deleteThread(target.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmMeta = null }) { Text("取消") }
            },
        )
    }

    deletion?.takeIf { it.profileKey == profileKey }?.let { deleting ->
        AlertDialog(
            onDismissRequest = vm::dismissDeletion,
            shape = RoundedCornerShape(20.dp),
            containerColor = scheme.surfaceContainerLow,
            title = { Text(if (deleting.inProgress) "正在删除会话" else "未能删除会话") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(threads.firstOrNull { it.id == deleting.threadId }?.title ?: "所选会话")
                    if (deleting.inProgress) {
                        Text("正在等待服务器确认删除…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Text(deleting.error.orEmpty(), color = scheme.error, style = MaterialTheme.typography.bodySmall)
                        Text("本地会话记录和草稿已保留。请核对上方原因，重新同步后查看会话状态。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (!deleting.inProgress) {
                    TextButton(
                        onClick = {
                            vm.dismissDeletion()
                            vm.selectThread(deleting.threadId)
                            coroutineScope.launch { drawerState.close() }
                        },
                    ) { Text("查看会话") }
                }
            },
            dismissButton = {
                if (!deleting.inProgress) TextButton(onClick = vm::dismissDeletion) { Text("关闭") }
            },
        )
    }

    if (renameThreadMeta != null) {
        AlertDialog(
            onDismissRequest = { renameThreadMeta = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = scheme.surfaceContainerLow,
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("会话标题") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = renameThreadMeta
                        if (target != null && renameText.isNotBlank()) {
                            vm.renameThread(target.id, renameText.trim())
                        }
                        renameThreadMeta = null
                    },
                    shape = RoundedCornerShape(12.dp),
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameThreadMeta = null }) { Text("取消") }
            },
        )
    }
}

private data class ThreadSection(val label: String, val items: List<ThreadMeta>)

private fun groupThreadsByRecency(threads: List<ThreadMeta>, query: String): List<ThreadSection> {
    val trimmed = query.trim()
    val filtered = if (trimmed.isEmpty()) threads else threads.filter {
        it.title.contains(trimmed, ignoreCase = true)
    }
    if (filtered.isEmpty()) return emptyList()

    val legacy = filtered.filter { it.id.startsWith("legacy:") }
    val active = filtered.filterNot { it.id.startsWith("legacy:") }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val startOfYesterday = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val startOf7Days = today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
    val startOf30Days = today.minusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()

    val todayList = mutableListOf<ThreadMeta>()
    val yesterdayList = mutableListOf<ThreadMeta>()
    val weekList = mutableListOf<ThreadMeta>()
    val monthList = mutableListOf<ThreadMeta>()
    val olderList = mutableListOf<ThreadMeta>()

    active.forEach { meta ->
        val ts = meta.lastActive
        when {
            ts >= startOfToday -> todayList += meta
            ts >= startOfYesterday -> yesterdayList += meta
            ts >= startOf7Days -> weekList += meta
            ts >= startOf30Days -> monthList += meta
            else -> olderList += meta
        }
    }

    return buildList {
        if (todayList.isNotEmpty()) add(ThreadSection("今天", todayList))
        if (yesterdayList.isNotEmpty()) add(ThreadSection("昨天", yesterdayList))
        if (weekList.isNotEmpty()) add(ThreadSection("最近 7 天", weekList))
        if (monthList.isNotEmpty()) add(ThreadSection("最近 30 天", monthList))
        if (olderList.isNotEmpty()) add(ThreadSection("更早", olderList))
        if (legacy.isNotEmpty()) add(ThreadSection("旧历史（只读）", legacy))
    }
}

private fun resolveHeaderStatus(
    connected: Boolean,
    connection: String,
    remoteDeleted: Boolean,
    stateStatus: String,
    running: Boolean,
    successColor: Color,
    warningColor: Color,
    primaryColor: Color,
    errorColor: Color,
): Pair<Color, String> = when {
    !connected -> errorColor to connection.ifBlank { "未连接" }
    connection.isNotBlank() && connection != "已连接" -> warningColor to connection
    remoteDeleted -> warningColor to "服务端已删除 · 仅本地只读"
    stateStatus == "connecting" -> primaryColor to "连接中…"
    stateStatus == "working" -> primaryColor to "处理中…"
    stateStatus == "waiting" -> warningColor to "等待确认"
    stateStatus == "background" -> primaryColor to "后台运行中"
    stateStatus == "failed" -> errorColor to "运行中断"
    stateStatus in setOf("", "idle", "Idle") -> successColor to "已连接"
    else -> (if (running) primaryColor else warningColor) to stateStatus
}

@Composable
private fun DrawerNavItem(
    icon: ImageVector,
    label: String,
    badgeText: String?,
    showDot: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (badgeText != null) {
            Surface(
                color = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        } else if (showDot) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(scheme.error),
            )
        }
    }
}

private sealed class TranscriptItem {
    abstract val key: String

    data class BlockItem(val block: com.deerflow.app.domain.DisplayBlock) : TranscriptItem() {
        override val key: String get() = block.key
    }

    data class ToolGroupItem(val group: ToolGroup) : TranscriptItem() {
        override val key: String get() = group.key
    }

    data class ProposalItem(val proposal: SkillProposal) : TranscriptItem() {
        override val key: String get() = "proposal:${proposal.id}"
    }
}

@Composable
private fun Transcript(
    state: ConversationState,
    listState: LazyListState,
    itemCount: MutableState<Int>,
    connected: Boolean,
    connectionText: String,
    artifactHeaders: Map<String, String>,
    resolveMessageFile: suspend (MessageFile) -> AgentArtifact,
    resolveGeneratedImage: suspend (String, String?) -> AgentArtifact,
    imageScopeKey: String,
    imageReloadKey: Any?,
    proposals: List<SkillProposal>,
    onViewProposal: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onPickSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var previousItemCount by itemCount
    var expandedToolGroups by rememberSaveable(
        state.threadId,
        saver = listSaver(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        ),
    ) { mutableStateOf(emptySet<String>()) }

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

        val grouped = TranscriptGrouping.group(state.blocks)
        val result = mutableListOf<TranscriptItem>()
        proposalsByAnchor[0]?.sortedBy { it.createdAt }?.forEach { proposal ->
            result.add(TranscriptItem.ProposalItem(proposal))
        }
        grouped.forEach { entry ->
            val anchorRange = when (entry) {
                is GroupedTranscriptEntry.Single -> {
                    result.add(TranscriptItem.BlockItem(entry.block))
                    (entry.blockIndex + 1)..(entry.blockIndex + 1)
                }
                is GroupedTranscriptEntry.ToolCard -> {
                    result.add(TranscriptItem.ToolGroupItem(entry.group))
                    (entry.group.startBlockIndex + 1)..(entry.group.endBlockIndex + 1)
                }
            }
            for (anchorIndex in anchorRange) {
                proposalsByAnchor[anchorIndex]?.sortedBy { it.createdAt }?.forEach { proposal ->
                    result.add(TranscriptItem.ProposalItem(proposal))
                }
            }
        }
        result
    }

    val isAtBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 1
        }
    }

    val lastBlockLength = state.blocks.lastOrNull()?.content?.length ?: 0

    LaunchedEffect(itemsList.size, state.threadId) {
        val currentItemCount = itemsList.size
        val itemsWereAdded = currentItemCount > previousItemCount
        previousItemCount = currentItemCount
        if (itemsWereAdded) listState.animateScrollToItem(currentItemCount - 1)
    }

    LaunchedEffect(lastBlockLength, state.running) {
        if (state.running && isAtBottom && itemsList.isNotEmpty()) {
            listState.scrollToItem(itemsList.lastIndex)
        }
    }

    if (itemsList.isEmpty()) {
        val suggestions = remember {
            listOf(
                "梳理当前项目结构与核心模块",
                "总结一份会议纪要并提取待办事项",
                "帮我排查一段日志或报错原因",
            )
        }
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = R.drawable.deerflow_logo),
                contentDescription = "DeerFlow Logo",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "有什么可以帮你的？",
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (connected) "输入你的任务目标，Agent 将在服务端为你分析与执行。" else connectionText.ifBlank { "请先配置并连接 Waku 服务器" },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(22.dp))

            if (!connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("前往连接设置") }
                    OutlinedButton(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("重新同步") }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { prompt ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onPickSuggestion(prompt) },
                            shape = RoundedCornerShape(14.dp),
                            color = scheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, scheme.outlineVariant),
                        ) {
                            Text(
                                text = prompt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
        ) {
            items(itemsList, key = { "$imageScopeKey:${it.key}" }) { item ->
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
                            resolveGeneratedImage = { reference -> resolveGeneratedImage(reference, item.block.messageId) },
                            imageReloadKey = imageReloadKey,
                        )
                    }
                    is TranscriptItem.ToolGroupItem -> {
                        val groupKey = item.group.key
                        ToolGroupCard(
                            group = item.group,
                            expanded = groupKey in expandedToolGroups,
                            onToggle = {
                                expandedToolGroups = if (groupKey in expandedToolGroups) {
                                    expandedToolGroups - groupKey
                                } else {
                                    expandedToolGroups + groupKey
                                }
                            },
                        )
                    }
                    is TranscriptItem.ProposalItem -> ProposalCard(
                        proposal = item.proposal,
                        compact = true,
                        onView = { onViewProposal(item.proposal.id) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = itemsList.isNotEmpty() && !isAtBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        if (itemsList.isNotEmpty()) listState.animateScrollToItem(itemsList.lastIndex)
                    }
                },
                shape = CircleShape,
                containerColor = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到底部")
            }
        }
    }
}

private const val MAX_THREAD_PROPOSAL_CARDS = 3

private val STREAMING_KINDS = setOf(
    BlockKind.ASSISTANT,
    BlockKind.TOOL,
    BlockKind.THINKING,
    BlockKind.REASONING,
)

@Composable
private fun InputBar(
    running: Boolean,
    awaitingInterrupt: Boolean,
    canSend: Boolean,
    editable: Boolean,
    text: String,
    allowFocusState: MutableState<Boolean>,
    onRejectUnrequestedFocus: () -> Unit,
    onTextChanged: (String) -> Unit,
    attachments: List<PendingAttachment>,
    onOssChanged: (Boolean) -> Unit,
    onSubmit: (String, List<PendingAttachment>) -> Boolean,
    onCancel: () -> Unit,
    onPickFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveAttachment: (PendingAttachment) -> Unit,
) {
    val placeholder = if (awaitingInterrupt) "回复中断提示…" else "给 DeerFlow 发消息…"
    val scheme = MaterialTheme.colorScheme
    val canAttach = editable && !running && !awaitingInterrupt
    var isFocused by remember { mutableStateOf(false) }

    fun send() {
        val t = text.trim()
        val hasPayload = if (awaitingInterrupt) t.isNotEmpty() else t.isNotEmpty() || attachments.isNotEmpty()
        if (hasPayload && canSend && !running) {
            onSubmit(t, if (awaitingInterrupt) emptyList() else attachments)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (isFocused) scheme.primary.copy(alpha = 0.7f) else scheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (attachments.isNotEmpty() && !awaitingInterrupt) {
                AttachmentRow(
                    attachments = attachments,
                    onRemoveAttachment = onRemoveAttachment,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("上传至", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    FilterChip(
                        selected = attachments.none { it.uploadToOss },
                        onClick = { onOssChanged(false) },
                        enabled = editable && !running,
                        label = { Text("服务器") },
                    )
                    FilterChip(
                        selected = attachments.all { it.uploadToOss },
                        onClick = { onOssChanged(true) },
                        enabled = editable && !running,
                        label = { Text("OSS") },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp, max = 144.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChanged,
                    enabled = editable,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties { canFocus = allowFocusState.value }
                        .onFocusChanged { focusState ->
                            isFocused = focusState.isFocused
                            if (focusState.isFocused && !allowFocusState.value) {
                                onRejectUnrequestedFocus()
                            }
                        }
                        .pointerInput(editable) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (editable) allowFocusState.value = true
                            }
                        },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!awaitingInterrupt) {
                        ComposerActionPill(
                            icon = Icons.Default.AttachFile,
                            label = "文件",
                            enabled = canAttach,
                            onClick = onPickFiles,
                        )
                        ComposerActionPill(
                            icon = Icons.Default.PhotoCamera,
                            label = "拍照",
                            enabled = canAttach,
                            onClick = onTakePhoto,
                        )
                    }
                }

                if (running) {
                    IconButton(
                        onClick = onCancel,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = scheme.errorContainer,
                            contentColor = scheme.onErrorContainer,
                        ),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "停止生成",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    val sendEnabled = canSend && (text.trim().isNotEmpty() || attachments.isNotEmpty())
                    IconButton(
                        onClick = { send() },
                        enabled = sendEnabled,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (sendEnabled) scheme.primary else scheme.surfaceContainerHigh,
                            contentColor = if (sendEnabled) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.45f),
                            disabledContainerColor = scheme.surfaceContainerHigh,
                            disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.45f),
                        ),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "发送消息",
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerActionPill(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainer,
        contentColor = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

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
            val isImage = attachment.mimeType?.startsWith("image/") == true
            InputChip(
                selected = false,
                onClick = {},
                leadingIcon = {
                    Icon(
                        imageVector = if (isImage) Icons.Default.Image else Icons.Default.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                },
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
                            contentDescription = "移除附件",
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
