package com.deerflow.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deerflow.app.ui.chat.ChatScreen
import com.deerflow.app.ui.chat.ChatViewModel
import com.deerflow.app.ui.proposal.ProposalScreen
import com.deerflow.app.ui.proposal.ProposalViewModel
import com.deerflow.app.ui.settings.SettingsScreen
import com.deerflow.app.ui.theme.DeerflowTheme
import kotlinx.coroutines.delay

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

private enum class Screen { CHAT, APPROVALS, SETTINGS }

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    val chatVm: ChatViewModel = viewModel()
    val proposalVm: ProposalViewModel = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                proposalVm.refresh()
                delay(PROPOSAL_REFRESH_INTERVAL_MS)
            }
        }
    }

    when (screen) {
        Screen.CHAT -> ChatScreen(
            vm = chatVm,
            proposalVm = proposalVm,
            onOpenApprovals = { screen = Screen.APPROVALS },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.APPROVALS -> ProposalScreen(vm = proposalVm, onBack = { screen = Screen.CHAT })
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.CHAT })
    }
}

private const val PROPOSAL_REFRESH_INTERVAL_MS = 30_000L
