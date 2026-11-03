package com.deerflow.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deerflow.app.domain.model.AgentArtifact
import com.deerflow.app.domain.model.TaskFilesState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(state: TaskFilesState, onBack: () -> Unit, onRefresh: () -> Unit,
    resolve: suspend (AgentArtifact) -> AgentArtifact) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("会话产物（${state.files.size}）") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回会话") }
        }, actions = { IconButton(onClick = onRefresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, "刷新产物") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("当前会话生成的文件。点击预览、打开或保存。", style = MaterialTheme.typography.bodySmall) }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (state.files.isEmpty() && !state.loading) item { Text("当前会话暂无产物文件") }
            items(state.files, key = { it.path }) { file -> ArtifactCard(file, resolve = { resolve(file) }) }
        }
    }
}
