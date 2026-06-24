package com.deerflow.app.ui.settings

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
    val saved by vm.settings.collectAsStateWithLifecycle()

    var endpoint by remember { mutableStateOf(saved.endpoint) }
    var headers by remember { mutableStateOf(saved.headersJson) }
    var initialState by remember { mutableStateOf(saved.initialStateJson) }

    // Seed fields once the stored values arrive.
    LaunchedEffect(saved) {
        endpoint = saved.endpoint
        headers = saved.headersJson
        initialState = saved.initialStateJson
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("AG-UI Base URL / Endpoint URL") },
                supportingText = { Text("e.g. http://10.0.2.2:8000  (emulator -> host)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = headers,
                onValueChange = { headers = it },
                label = { Text("Headers (JSON object, optional)") },
                placeholder = { Text("{\"Authorization\":\"Bearer ...\"}") },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = initialState,
                onValueChange = { initialState = it },
                label = { Text("Initial state (JSON object, optional)") },
                placeholder = { Text("{\"user_id\":\"123\"}") },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Button(
                onClick = {
                    vm.save(endpoint, headers, initialState)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                Text("Save")
            }
            Text(
                "Changes apply to the next run.",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
