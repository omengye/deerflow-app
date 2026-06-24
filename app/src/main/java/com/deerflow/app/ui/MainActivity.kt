package com.deerflow.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deerflow.app.ui.chat.ChatScreen
import com.deerflow.app.ui.chat.ChatViewModel
import com.deerflow.app.ui.settings.SettingsScreen
import com.deerflow.app.ui.theme.DeerflowTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Foreground-service notifications require runtime permission on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            DeerflowTheme {
                AppRoot()
            }
        }
    }
}

private enum class Screen { CHAT, SETTINGS }

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    val chatVm: ChatViewModel = viewModel()

    when (screen) {
        Screen.CHAT -> ChatScreen(vm = chatVm, onOpenSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.CHAT })
    }
}
