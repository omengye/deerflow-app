package com.deerflow.app.ui.settings

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.deerflow.app.ui.memory.MemoryScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    var memoryVisible by rememberSaveable { mutableStateOf(false) }
    var ossVisible by rememberSaveable { mutableStateOf(false) }
    if (ossVisible) { OssSettingsScreen(onBack = { ossVisible = false }); return }
    if (memoryVisible) { MemoryScreen(onBack = { memoryVisible = false }); return }
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
            TextButton(onClick = { memoryVisible = true }, enabled = enabled) { Text("个人记忆") }
            TextButton(onClick = { ossVisible = true }, enabled = enabled) { Text("OSS 附件存储") }
            Text("后台连接", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("后台持续同步会话", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (state.keepBackgroundConnection) {
                            "已开启：切后台后保持前台服务与长连接，静默接收当前及多端会话更新。可在通知栏随时暂停。"
                        } else {
                            "已关闭（默认）：任务执行时保持后台连接，任务完成后保留 60 秒宽限；空闲切后台 60 秒后暂停。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.keepBackgroundConnection,
                    onCheckedChange = vm::keepBackgroundConnectionChanged,
                    enabled = enabled,
                )
            }
            Text(
                "国产系统若限制后台联网或冻结进程，请在电池设置中将本应用设为“无限制”并锁定后台。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            })
                        } catch (_: Exception) {
                            Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("通知设置") }
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            })
                        } catch (_: Exception) {
                            Toast.makeText(context, "无法打开电池设置", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("电池优化设置") }
            }
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
