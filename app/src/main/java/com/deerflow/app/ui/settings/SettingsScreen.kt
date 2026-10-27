package com.deerflow.app.ui.settings

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val draft = state.draft
    val enabled = state.loaded && !state.saving
    BackHandler(enabled = state.saving) { }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("连接设置") }, navigationIcon = {
            IconButton(onClick = onBack, enabled = !state.saving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (draft.needsReconfiguration) Text(
                "连接方式已升级。请填写 Waku 服务地址和 Token 并保存。旧 AG-UI 地址不会自动迁移，旧对话保留为只读历史。",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(value = draft.endpoint, onValueChange = vm::endpointChanged,
                enabled = enabled, label = { Text("Waku 服务地址") },
                supportingText = { Text("例如 ws://10.0.2.2:18787/v1；远程连接使用 wss://") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.token, onValueChange = vm::tokenChanged,
                enabled = enabled, label = { Text("连接 Token") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("由服务器管理员提供，保存在设备加密存储中") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.workspace, onValueChange = vm::workspaceChanged,
                enabled = enabled, label = { Text("服务器工作目录") },
                supportingText = { Text("例如 /workspaces；这是服务器上的目录") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = draft.modelName.orEmpty(), onValueChange = vm::selectModel,
                enabled = enabled, label = { Text("服务端模型 ID（可选）") },
                supportingText = { Text("留空跟随后端默认。填写服务器已配置的模型 ID，保存后在新会话生效。") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("后台连接", style = MaterialTheme.typography.titleSmall)
            Text("任务执行时持续接收进度与待处理提醒；空闲时切到后台 60 秒后暂停。可在通知栏暂停后台接收。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("通知设置") }
            Text("工具权限：Full Access", style = MaterialTheme.typography.titleSmall)
            Text("普通工具自动授权。已有会话在发送下一条消息前应用；服务器要求的特殊审批仍会显示。",
                style = MaterialTheme.typography.bodySmall)
            if (state.loaded) draft.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.saving) "保存中…" else "保存连接")
            }
            Text("更换服务器后将重新同步会话。网络断开时可查看缓存历史，任务继续由服务器执行。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}