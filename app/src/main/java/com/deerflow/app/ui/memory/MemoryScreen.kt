package com.deerflow.app.ui.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deerflow.app.data.memory.PersonalMemory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit, vm: MemoryViewModel = viewModel()) {
    val scheme = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PersonalMemory?>(null) }
    var forgetting by remember { mutableStateOf<PersonalMemory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("profile") }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("个人记忆") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    titleContentColor = scheme.onBackground,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { adding = true; newKey = ""; newContent = ""; newCategory = "profile" },
                        enabled = !state.busy && !state.pending && state.page != null,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加记忆")
                    }
                    if (state.pending) {
                        TextButton(onClick = vm::refresh, enabled = !state.busy) {
                            Text("重新核对")
                        }
                    } else {
                        IconButton(onClick = vm::refresh, enabled = !state.busy) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新记忆")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp + padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    color = scheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    Text(
                        text = "个人资料与偏好在所有会话和设备间共享。可在对话中说“请记住……”添加，或点击右上角“+”手动管理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                }
            }
            if (state.busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            state.message?.let { message ->
                item {
                    Surface(
                        color = scheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                }
            }
            val facts = state.page?.facts.orEmpty()
            if (state.page != null && facts.isEmpty() && !state.busy) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(scheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "暂无个人记忆",
                            style = MaterialTheme.typography.titleMedium,
                            color = scheme.onBackground,
                        )
                        Text(
                            text = "当你在对话中交代长期偏好或个人背景时，记忆会自动整理到这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            items(facts, key = { it.id }) { fact ->
                val isPreference = fact.category == "preference"
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = memoryLabel(fact),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            Surface(
                                color = if (isPreference) scheme.tertiaryContainer else scheme.primaryContainer,
                                contentColor = if (isPreference) scheme.onTertiaryContainer else scheme.onPrimaryContainer,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = if (isPreference) "个人偏好" else "个人资料",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                        Text(
                            text = fact.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                        )
                        val time = runCatching {
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.parse(fact.updatedAt))
                        }.getOrDefault(fact.updatedAt)
                        val sourceLabel = if (fact.source["kind"]?.toString() == "\"model\"") "从对话提取" else "用户确认"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "更新于 $time · $sourceLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Row {
                                TextButton(
                                    onClick = { editing = fact; draft = fact.content },
                                    enabled = !state.busy && !state.pending && fact.key.isNotBlank(),
                                ) { Text("修改") }
                                TextButton(
                                    onClick = { forgetting = fact },
                                    enabled = !state.busy && !state.pending && fact.key.isNotBlank(),
                                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
                                ) { Text("忘记") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = scheme.surfaceContainerLow,
            title = { Text("添加记忆") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it },
                        label = { Text("标识键 (如 profile.name)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newContent,
                        onValueChange = { if (it.length <= 512) newContent = it },
                        label = { Text("记忆内容 (最多512字)") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = newCategory == "profile",
                            onClick = { newCategory = "profile" },
                            label = { Text("个人资料 (profile)") },
                        )
                        FilterChip(
                            selected = newCategory == "preference",
                            onClick = { newCategory = "preference" },
                            label = { Text("个人偏好 (preference)") },
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.add(newKey, newContent, newCategory)
                        adding = false
                    },
                    enabled = newKey.isNotBlank() && newContent.isNotBlank() && newCategory.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { adding = false }) { Text("取消") }
            },
        )
    }

    editing?.let { fact ->
        AlertDialog(
            onDismissRequest = { editing = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = scheme.surfaceContainerLow,
            title = { Text("修改记忆") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 512) draft = it },
                    label = { Text("记忆内容") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.update(fact, draft); editing = null },
                    enabled = draft.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }

    forgetting?.let { fact ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = scheme.surfaceContainerLow,
            title = { Text("忘记这条记忆？") },
            text = { Text("之后的新请求将不再读取这条记忆。原始聊天记录会保留。") },
            confirmButton = {
                Button(
                    onClick = { vm.forget(fact); forgetting = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.error,
                        contentColor = scheme.onError,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("忘记") }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = null }) { Text("取消") }
            },
        )
    }
}

private fun memoryLabel(fact: PersonalMemory): String {
    val categoryLabel = if (fact.category == "preference") "个人偏好" else "个人资料"
    return when (fact.key) {
        "profile.last_reported_location" -> "最近告知的所在地"
        "profile.name" -> "姓名"
        "preference.language" -> "语言偏好"
        "" -> categoryLabel
        else -> fact.key
    }
}
