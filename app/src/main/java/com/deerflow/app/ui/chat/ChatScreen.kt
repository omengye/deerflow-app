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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.deerflow.app.domain.ConversationState
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val artifactHeaders by vm.artifactHeaders.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var attachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    var pendingCameraAttachment by remember { mutableStateOf<PendingAttachment?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val selected = uris.mapNotNull { context.pendingAttachmentFromUri(it) }
        if (selected.isNotEmpty()) attachments = attachments + selected
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val captured = pendingCameraAttachment
        if (success && captured != null) attachments = attachments + captured
        pendingCameraAttachment = null
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

                    val threads by vm.threads.collectAsStateWithLifecycle()
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
                            "DeerFlow",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
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
                    StatusBar(state)
                    InputBar(
                        running = state.running,
                        awaitingInterrupt = state.awaitingInterrupt,
                        attachments = attachments,
                        onSubmit = { text, files ->
                            if (state.awaitingInterrupt) {
                                vm.submit(text)
                            } else {
                                vm.submit(text, files)
                                attachments = emptyList()
                            }
                        },
                        onCancel = vm::cancel,
                        onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                        onTakePhoto = {
                            context.createCameraAttachment()?.let { pending ->
                                pendingCameraAttachment = pending
                                cameraLauncher.launch(pending.uri)
                            }
                        },
                        onRemoveAttachment = { attachment ->
                            attachments = attachments.filterNot { it.uri == attachment.uri }
                        },
                    )
                }
            },
        ) { padding ->
            Transcript(state, artifactHeaders, Modifier.padding(padding))
        }
    }
}

@Composable
private fun Transcript(
    state: ConversationState,
    artifactHeaders: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll when new blocks are appended, but avoid restarting animations for every streamed content chunk.
    LaunchedEffect(state.blocks.size) {
        if (state.blocks.isNotEmpty()) listState.animateScrollToItem(state.blocks.lastIndex)
    }

    if (state.blocks.isEmpty()) {
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
        items(state.blocks, key = { it.key }) { block ->
            BlockCard(block, artifactHeaders = artifactHeaders)
        }
    }
}

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
    attachments: List<PendingAttachment>,
    onSubmit: (String, List<PendingAttachment>) -> Unit,
    onCancel: () -> Unit,
    onPickFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveAttachment: (PendingAttachment) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var toolsExpanded by remember { mutableStateOf(false) }
    val placeholder = if (awaitingInterrupt) "Reply to interrupt..." else "Type a message..."
    val scheme = MaterialTheme.colorScheme
    val canAttach = !running && !awaitingInterrupt
    val context = LocalContext.current

    fun appendSpeechText(spokenText: String) {
        val trimmed = spokenText.trim()
        if (trimmed.isNotEmpty()) {
            text = if (text.isBlank()) trimmed else "${text.trimEnd()} $trimmed"
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
        if (hasPayload) {
            onSubmit(t, if (awaitingInterrupt) emptyList() else attachments)
            text = ""
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
                onValueChange = { text = it },
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

            val speechEnabled = !running
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
                val sendEnabled = if (awaitingInterrupt) text.trim().isNotEmpty() else text.trim().isNotEmpty() || attachments.isNotEmpty()
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
