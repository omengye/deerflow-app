package com.deerflow.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deerflow.app.data.oss.S3Settings
import com.deerflow.app.data.settings.SettingsStore
import com.deerflow.app.ui.theme.deerflowColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OssSettingsState(
    val draft: S3Settings = S3Settings(),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class OssSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val mutableState = MutableStateFlow(OssSettingsState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            mutableState.value = try {
                OssSettingsState(draft = store.currentOss(), loaded = true)
            } catch (_: Exception) {
                OssSettingsState(loaded = true, error = "无法读取 OSS 配置，请重新导入或填写")
            }
        }
    }

    fun edit(change: (S3Settings) -> S3Settings) {
        mutableState.value = mutableState.value.copy(draft = change(mutableState.value.draft), error = null, notice = null)
    }

    fun savedHandled() {
        mutableState.value = mutableState.value.copy(saved = false)
    }

    fun importProfile(uri: Uri) = viewModelScope.launch {
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        try {
            val settings = withContext(Dispatchers.IO) {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val chars = CharArray(32 * 1024 + 1)
                    var size = 0
                    while (size < chars.size) {
                        val read = reader.read(chars, size, chars.size - size)
                        if (read < 0) break
                        size += read
                    }
                    require(size <= 32 * 1024)
                    String(chars, 0, size)
                } ?: error("empty")
                S3Settings.importProfile(text)
            }
            mutableState.value = mutableState.value.copy(draft = settings, notice = "配置已导入，点击保存后生效")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "配置文件无法导入，请检查 JSON 格式和必填项")
        } finally {
            mutableState.value = mutableState.value.copy(saving = false)
        }
    }

    fun save() = viewModelScope.launch {
        val draft = mutableState.value.draft
        draft.validationError?.let { mutableState.value = mutableState.value.copy(error = it); return@launch }
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        try {
            store.saveOss(draft)
            mutableState.value = mutableState.value.copy(saved = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "OSS 配置保存失败，请重试")
        } finally {
            mutableState.value = mutableState.value.copy(saving = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OssSettingsScreen(onBack: () -> Unit, vm: OssSettingsViewModel = viewModel()) {
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.deerflowColors
    val state by vm.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val enabled = state.loaded && !state.saving
    var showAccessKeyId by remember { mutableStateOf(false) }
    var showSecretKey by remember { mutableStateOf(false) }
    var showSessionToken by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importProfile(it) }
    }

    BackHandler { if (!state.saving) onBack() }
    LaunchedEffect(state.saved) { if (state.saved) { vm.savedHandled(); onBack() } }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("OSS 附件存储") },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = scheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, scheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "选择附件后可在输入区切换上传到 OSS。默认上传到会话服务器；生成的产物保持服务端存储。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null)
                        Spacer(Modifier.padding(start = 6.dp))
                        Text("导入 OSS 配置文件")
                    }
                }
            }

            Surface(
                color = scheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, scheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "存储桶与访问凭据",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                    )
                    OutlinedTextField(
                        value = draft.endpoint,
                        onValueChange = { value -> vm.edit { it.copy(endpoint = value.trim()) } },
                        label = { Text("S3 服务地址") },
                        singleLine = true,
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.bucket,
                        onValueChange = { value -> vm.edit { it.copy(bucket = value.trim()) } },
                        label = { Text("桶名") },
                        singleLine = true,
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.region,
                        onValueChange = { value -> vm.edit { it.copy(region = value.trim()) } },
                        label = { Text("区域") },
                        singleLine = true,
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.accessKeyId,
                        onValueChange = { value -> vm.edit { it.copy(accessKeyId = value.trim()) } },
                        label = { Text("Access Key ID") },
                        visualTransformation = if (showAccessKeyId) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showAccessKeyId = !showAccessKeyId }) {
                                Icon(
                                    imageVector = if (showAccessKeyId) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showAccessKeyId) "隐藏 Access Key ID" else "显示 Access Key ID",
                                )
                            }
                        },
                        singleLine = true,
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.accessKeySecret,
                        onValueChange = { value -> vm.edit { it.copy(accessKeySecret = value.trim()) } },
                        label = { Text("Secret Key") },
                        visualTransformation = if (showSecretKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showSecretKey = !showSecretKey }) {
                                Icon(
                                    imageVector = if (showSecretKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showSecretKey) "隐藏 Secret Key" else "显示 Secret Key",
                                )
                            }
                        },
                        singleLine = true,
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.sessionToken,
                        onValueChange = { value -> vm.edit { it.copy(sessionToken = value.trim()) } },
                        label = { Text("Session Token（可选）") },
                        visualTransformation = if (showSessionToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showSessionToken = !showSessionToken }) {
                                Icon(
                                    imageVector = if (showSessionToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showSessionToken) "隐藏 Session Token" else "显示 Session Token",
                                )
                            }
                        },
                        singleLine = true,
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.userAgent,
                        onValueChange = { value -> vm.edit { it.copy(userAgent = value) } },
                        label = { Text("客户端标识") },
                        supportingText = { Text("当前存储服务使用 S3Drive") },
                        singleLine = true,
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            state.notice?.let { notice ->
                Surface(
                    color = semantic.successContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = notice,
                        color = semantic.onSuccessContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
            state.error?.let { error ->
                Surface(
                    color = scheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = error,
                        color = scheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }

            Button(
                onClick = { vm.save() },
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(if (state.saving) "处理中…" else "保存 OSS 配置")
            }

            Text(
                text = "账号密钥使用 Android Keystore 加密保存于设备本地。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
