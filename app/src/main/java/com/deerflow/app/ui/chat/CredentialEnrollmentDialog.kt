package com.deerflow.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
internal fun CredentialEnrollmentDialog(form: ServiceCredentialForm, onBackground: () -> Unit,
    onCancel: () -> Unit, onSealed: (JsonObject) -> Unit) {
    var username by remember(form.publicKey) { mutableStateOf("") }
    var secret by remember(form.publicKey) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var active by remember(form.publicKey) { mutableStateOf(true) }
    fun cancel() {
        if (active) { active = false; username = ""; secret = ""; onCancel() }
    }
    val owner = LocalLifecycleOwner.current
    val onHide by rememberUpdatedState(onBackground)
    val jobs = rememberCoroutineScope()
    DisposableEffect(owner, form.publicKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { active = false; username = ""; secret = ""; onHide() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); active = false; username = ""; secret = "" }
    }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = ::cancel,
        title = { Text("保存服务凭据") },
        text = {
            // TextField internals also participate in saved state; exclude the whole input subtree.
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务  " + form.service)
                    Text("调用方  " + form.consumer)
                    Text("账号别名  " + form.account)
                    if (form.requiresUsername) {
                        OutlinedTextField(username, { username = it; error = "" }, enabled = !busy, label = { Text("用户名") },
                            singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                    }
                    if (form.requiresKey) {
                        OutlinedTextField(secret, { secret = it; error = "" }, enabled = !busy, label = { Text("密钥或密码") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
                    }
                    Text("凭据保存在服务端加密库。此账号下次使用时只需确认。")
                    if (error.isNotEmpty()) Text(error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && form.hasRequiredInput(username, secret), onClick = {
                if (busy) return@TextButton
                busy = true
                val user = username
                val key = secret
                jobs.launch {
                    try {
                        val sealed = withContext(Dispatchers.Default) { form.seal(user, key) }
                        if (!active || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                        username = ""; secret = ""
                        onSealed(sealed)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { error = "未能加密凭据。请检查输入，每项最多 64 KiB。" }
                    finally { busy = false }
                }
            }) { Text("保存并允许本次") }
        },
        dismissButton = { TextButton(onClick = ::cancel) { Text("取消") } },
    )
}
