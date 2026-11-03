package com.deerflow.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deerflow.app.data.oss.S3Settings
import com.deerflow.app.data.settings.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OssSettingsState(val draft: S3Settings = S3Settings(), val loaded: Boolean = false,
    val saving: Boolean = false, val saved: Boolean = false, val error: String? = null, val notice: String? = null)

class OssSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val mutableState = MutableStateFlow(OssSettingsState())
    val state = mutableState.asStateFlow()
    init { viewModelScope.launch {
        mutableState.value = try { OssSettingsState(draft = store.currentOss(), loaded = true) }
        catch (_: Exception) { OssSettingsState(loaded = true, error = "无法读取 OSS 配置，请重新导入或填写") }
    } }
    fun edit(change: (S3Settings) -> S3Settings) {
        mutableState.value = mutableState.value.copy(draft = change(mutableState.value.draft), error = null, notice = null)
    }
    fun savedHandled() { mutableState.value = mutableState.value.copy(saved = false) }
    fun importProfile(uri: Uri) = viewModelScope.launch {
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        try {
            val settings = withContext(Dispatchers.IO) {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val chars = CharArray(32 * 1024 + 1)
                    var size = 0
                    while (size < chars.size) { val read = reader.read(chars, size, chars.size - size); if (read < 0) break; size += read }
                    require(size <= 32 * 1024)
                    String(chars, 0, size)
                } ?: error("empty")
                S3Settings.importProfile(text)
            }
            mutableState.value = mutableState.value.copy(draft = settings, notice = "配置已导入，点击保存后生效")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "配置文件无法导入，请检查 JSON 格式和必填项") }
        finally { mutableState.value = mutableState.value.copy(saving = false) }
    }
    fun save() = viewModelScope.launch {
        val draft = mutableState.value.draft
        draft.validationError?.let { mutableState.value = mutableState.value.copy(error = it); return@launch }
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        try {
            store.saveOss(draft)
            mutableState.value = mutableState.value.copy(saved = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "OSS 配置保存失败，请重试") }
        finally { mutableState.value = mutableState.value.copy(saving = false) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OssSettingsScreen(onBack: () -> Unit, vm: OssSettingsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val enabled = state.loaded && !state.saving
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importProfile(it) } }
    BackHandler { if (!state.saving) onBack() }
    LaunchedEffect(state.saved) { if (state.saved) { vm.savedHandled(); onBack() } }
    Scaffold(topBar = { TopAppBar(title = { Text("OSS 附件存储") }, navigationIcon = {
        IconButton(onClick = onBack, enabled = !state.saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选择附件后可切换上传到 OSS。默认上传到会话服务器；生成的产物保存在服务器。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = enabled) { Text("导入 OSS 配置文件") }
            OutlinedTextField(draft.endpoint, { value -> vm.edit { it.copy(endpoint = value.trim()) } },
                label = { Text("S3 服务地址") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.bucket, { value -> vm.edit { it.copy(bucket = value.trim()) } },
                label = { Text("桶名") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.region, { value -> vm.edit { it.copy(region = value.trim()) } },
                label = { Text("区域") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.accessKeyId, { value -> vm.edit { it.copy(accessKeyId = value.trim()) } },
                label = { Text("Access Key ID") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.accessKeySecret, { value -> vm.edit { it.copy(accessKeySecret = value.trim()) } },
                label = { Text("Secret Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.sessionToken, { value -> vm.edit { it.copy(sessionToken = value.trim()) } },
                label = { Text("Session Token（可选）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.userAgent, { value -> vm.edit { it.copy(userAgent = value) } },
                label = { Text("客户端标识") }, supportingText = { Text("当前存储服务使用 S3Drive") }, singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            Text("账号密钥使用设备密钥库加密保存。", style = MaterialTheme.typography.bodySmall)
            state.notice?.let { Text(it) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.save() }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "处理中…" else "保存 OSS 配置") }
        }
    }
}
