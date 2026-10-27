package com.deerflow.app.ui.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PersonalMemory?>(null) }
    var forgetting by remember { mutableStateOf<PersonalMemory?>(null) }
    var draft by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { vm.refresh() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("个人记忆") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, actions = {
            TextButton(onClick = vm::refresh, enabled = !state.busy) {
                Text(if (state.pending) "重新核对" else "刷新")
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("这些个人资料在所有会话和设备间共享。可以在聊天中说“请记住……”添加记忆。",
                style = MaterialTheme.typography.bodyMedium) }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.message?.let { message -> item { Text(message) } }
            if (state.page != null && state.page?.facts.isNullOrEmpty()) item { Text("暂时没有保存的个人记忆。") }
            items(state.page?.facts.orEmpty(), key = { it.id }) { fact ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(memoryLabel(fact), style = MaterialTheme.typography.titleSmall)
                        Text(fact.content)
                        val time = runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            .withZone(ZoneId.systemDefault()).format(Instant.parse(fact.updatedAt)) }.getOrDefault(fact.updatedAt)
                        Text("更新于 " + time + " · " + if (fact.source["kind"]?.toString() == "\"model\"") "从对话提取" else "用户确认",
                            style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { editing = fact; draft = fact.content },
                                enabled = !state.busy && !state.pending && fact.key.isNotBlank()) { Text("修改") }
                            TextButton(onClick = { forgetting = fact },
                                enabled = !state.busy && !state.pending && fact.key.isNotBlank()) { Text("忘记") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    editing?.let { fact ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("修改记忆") },
            text = { OutlinedTextField(value = draft, onValueChange = { if (it.length <= 512) draft = it },
                label = { Text("记忆内容") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.update(fact, draft); editing = null },
                enabled = draft.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    forgetting?.let { fact ->
        AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("忘记这条记忆？") },
            text = { Text("之后的新请求将不再读取这条记忆。原始聊天记录会保留。") },
            confirmButton = { TextButton(onClick = { vm.forget(fact); forgetting = null }) { Text("忘记") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("取消") } })
    }
}

private fun memoryLabel(fact: PersonalMemory): String = when (fact.key) {
    "profile.last_reported_location" -> "最近告知的所在地"
    "profile.name" -> "姓名"
    "preference.language" -> "语言偏好"
    else -> if (fact.category == "preference") "个人偏好" else "个人资料"
}
