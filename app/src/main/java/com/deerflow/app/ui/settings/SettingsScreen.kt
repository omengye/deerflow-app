package com.deerflow.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deerflow.app.ui.theme.deerflowColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMemory: () -> Unit = {},
    onOpenOss: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.deerflowColors
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val draft = state.draft
    val enabled = state.loaded && !state.saving
    var showToken by remember { mutableStateOf(false) }

    BackHandler(enabled = state.saving) { }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    titleContentColor = scheme.onBackground,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (draft.needsReconfiguration) {
                Surface(
                    color = scheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = "连接方式已升级。请填写 Waku 服务地址和 Token 并保存。旧 AG-UI 地址不会自动迁移，旧对话保留为只读历史。",
                        color = scheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }

            // Section 1: Server connection
            SettingsGroupCard(title = "服务器连接") {
                OutlinedTextField(
                    value = draft.endpoint,
                    onValueChange = vm::endpointChanged,
                    enabled = enabled,
                    label = { Text("Waku 服务地址") },
                    supportingText = { Text("例如 ws://10.0.2.2:18787/v1；远程连接使用 wss://") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.token,
                    onValueChange = vm::tokenChanged,
                    enabled = enabled,
                    label = { Text("连接 Token") },
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showToken) "隐藏 Token" else "显示 Token",
                            )
                        }
                    },
                    supportingText = { Text("由服务器管理员提供，保存在设备加密存储中") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.workspace,
                    onValueChange = vm::workspaceChanged,
                    enabled = enabled,
                    label = { Text("服务器工作目录") },
                    supportingText = { Text("例如 /workspaces；这是服务器上的目录") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.modelName.orEmpty(),
                    onValueChange = vm::selectModel,
                    enabled = enabled,
                    label = { Text("服务端模型 ID（可选）") },
                    supportingText = { Text("留空跟随后端默认。填写服务器已配置的模型 ID，保存后在新会话生效。") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.loaded) {
                    draft.validationError?.let {
                        Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.saveError?.let {
                    Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = vm::save,
                    enabled = state.canSave,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text(if (state.saving) "保存中…" else "保存连接")
                }
                Text(
                    text = "更换服务器后将重新同步会话。网络断开时可查看缓存历史，任务继续由服务器执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            // Section 2: Data & Extensions
            SettingsGroupCard(title = "数据与扩展", contentPadding = false) {
                SettingsNavRow(
                    icon = Icons.Default.Psychology,
                    title = "个人记忆",
                    subtitle = "管理跨会话共享的个人资料与偏好",
                    enabled = enabled,
                    onClick = onOpenMemory,
                )
                HorizontalDivider(
                    color = scheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SettingsNavRow(
                    icon = Icons.Outlined.CloudUpload,
                    title = "OSS 附件存储",
                    subtitle = "配置 S3 直传凭据与客户端标识",
                    enabled = enabled,
                    onClick = onOpenOss,
                )
            }

            // Section 3: Background & System permissions
            SettingsGroupCard(title = "后台连接与系统权限") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "后台持续同步会话",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (state.keepBackgroundConnection) {
                                "已开启：切后台后保持前台服务与长连接，静默接收当前及多端会话更新。可在通知栏随时暂停。"
                            } else {
                                "已关闭（默认）：任务执行时保持后台连接，任务完成后保留 60 秒宽限；空闲切后台 60 秒后暂停。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.keepBackgroundConnection,
                        onCheckedChange = vm::keepBackgroundConnectionChanged,
                        enabled = enabled,
                    )
                }
                Text(
                    text = "国产系统若限制后台联网或冻结进程，请在电池设置中将本应用设为“无限制”并锁定后台。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        },
                                    )
                                } catch (_: Exception) {
                                    Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("通知设置")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        },
                                    )
                                } catch (_: Exception) {
                                    Toast.makeText(context, "无法打开电池设置", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("电池优化")
                    }
                }
            }

            // Section 4: Tool Permissions
            SettingsGroupCard(title = "工具权限") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = semantic.successContainer,
                        contentColor = semantic.onSuccessContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text("Full Access 自动授权", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(
                    text = "普通工具自动授权。已有会话在发送下一条消息前应用；服务器要求的特殊审批与凭据录入仍会弹出确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    contentPadding: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = scheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Column(
                modifier = if (contentPadding) Modifier.padding(14.dp) else Modifier,
                verticalArrangement = if (contentPadding) Arrangement.spacedBy(12.dp) else Arrangement.Top,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.primaryContainer.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
        )
    }
}
