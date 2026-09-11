package com.deerflow.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val draft = state.draft
    BackHandler(enabled = state.saving) { /* Finish the pending DataStore write before leaving. */ }
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()

    var modelsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
        ) {
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Server Connection",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = draft.endpoint,
                        onValueChange = vm::endpointChanged,
                        enabled = state.loaded && !state.saving,
                        label = { Text("AG-UI Base URL / Endpoint URL") },
                        supportingText = { Text("e.g. http://10.0.2.2:8000 (emulator -> host)") },
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.token,
                        onValueChange = vm::tokenChanged,
                        enabled = state.loaded && !state.saving,
                        label = { Text("Token (optional)") },
                        placeholder = { Text("paste your token here") },
                        supportingText = { Text("Sent as Authorization: Bearer <token>") },
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    Text("使用模型", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    val selected = state.models.firstOrNull { it.name == draft.modelName }
                    ExposedDropdownMenuBox(
                        expanded = modelsExpanded && state.loaded && !state.saving,
                        onExpandedChange = { if (state.loaded && !state.saving) modelsExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selected?.label ?: draft.modelName ?: "跟随后端默认",
                            onValueChange = {},
                            readOnly = true,
                            enabled = state.loaded && !state.saving,
                            label = { Text("模型") },
                            isError = state.unavailableModel,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelsExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = state.loaded && !state.saving).fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = modelsExpanded && state.loaded && !state.saving, onDismissRequest = { modelsExpanded = false }) {
                            DropdownMenuItem(text = { Text("跟随后端默认") }, onClick = {
                                vm.selectModel(null)
                                modelsExpanded = false
                            })
                            state.models.forEach { model ->
                                DropdownMenuItem(text = {
                                    Column {
                                        Text(model.label)
                                        if (model.label != model.name) Text(model.name, style = MaterialTheme.typography.bodySmall)
                                    }
                                }, onClick = {
                                    vm.selectModel(model.name)
                                    modelsExpanded = false
                                })
                            }
                        }
                    }
                    if (state.loadingModels) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (state.unavailableModel) Text("已保存的模型在此服务器上不可用，请重新选择", color = MaterialTheme.colorScheme.error)
                    state.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.modelsLoaded && state.models.isEmpty()) Text("服务器没有返回可选模型，请检查后端配置")
                    TextButton(onClick = { vm.refreshModels() }, enabled = state.loaded && !state.saving && !state.loadingModels) {
                        Text("刷新模型列表")
                    }
                    Text("保存后，下次发送消息时生效。正在运行或重连的任务继续使用原模型。", style = MaterialTheme.typography.bodySmall)
                }
            }

            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Agent Session State",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = draft.initialStateJson,
                        onValueChange = vm::initialStateChanged,
                        enabled = state.loaded && !state.saving,
                        label = { Text("Initial state (JSON object, optional)") },
                        placeholder = { Text("{\"user_id\":\"123\"}") },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Button(
                onClick = vm::save,
                enabled = state.canSave,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (state.saving) "Saving..." else "Save Changes", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Note: Changes apply to the next session run.",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
