package com.deerflow.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.deerflow.app.data.waku.ServiceCredentialForm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

@Composable
internal fun CredentialEnrollmentDialog(
    form: ServiceCredentialForm,
    onBackground: () -> Unit,
    onCancel: () -> Unit,
    onSealed: (JsonObject) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var username by remember(form.publicKey) { mutableStateOf("") }
    var secret by remember(form.publicKey) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var active by remember(form.publicKey) { mutableStateOf(true) }
    fun cancel() {
        if (active) {
            active = false
            username = ""
            secret = ""
            onCancel()
        }
    }
    val owner = LocalLifecycleOwner.current
    val onHide by rememberUpdatedState(onBackground)
    val jobs = rememberCoroutineScope()
    DisposableEffect(owner, form.publicKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                active = false
                username = ""
                secret = ""
                onHide()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            active = false
            username = ""
            secret = ""
        }
    }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = ::cancel,
        shape = RoundedCornerShape(20.dp),
        containerColor = scheme.surfaceContainerLow,
        title = { Text("保存服务凭据", style = MaterialTheme.typography.titleLarge) },
        text = {
            // TextField internals also participate in saved state; exclude the whole input subtree.
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        color = scheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, scheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CredentialMetaRow("服务", form.service)
                            CredentialMetaRow("调用方", form.consumer)
                            CredentialMetaRow("账号别名", form.account)
                        }
                    }
                    if (form.requiresUsername) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it; error = "" },
                            enabled = !busy,
                            label = { Text("用户名") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (form.requiresKey) {
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it; error = "" },
                            enabled = !busy,
                            label = { Text("密钥或密码") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = "凭据将加密保存至服务端加密库，此账号下次使用时只需确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    if (error.isNotEmpty()) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && form.hasRequiredInput(username, secret),
                shape = RoundedCornerShape(12.dp),
                onClick = {
                    if (busy) return@Button
                    busy = true
                    val user = username
                    val key = secret
                    jobs.launch {
                        try {
                            val sealed = withContext(Dispatchers.Default) { form.seal(user, key) }
                            if (!active || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                            username = ""
                            secret = ""
                            onSealed(sealed)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            error = "未能加密凭据。请检查输入，每项最多 64 KiB。"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("保存并允许本次") }
        },
        dismissButton = {
            TextButton(onClick = ::cancel) { Text("取消") }
        },
    )
}

@Composable
private fun CredentialMetaRow(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            modifier = Modifier.weight(0.7f),
        )
    }
}
