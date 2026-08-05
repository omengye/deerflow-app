package com.deerflow.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.deerflow.app.ui.chat.ChatScreen
import com.deerflow.app.ui.chat.ChatViewModel
import com.deerflow.app.ui.proposal.ProposalScreen
import com.deerflow.app.ui.proposal.ProposalViewModel
import com.deerflow.app.ui.settings.SettingsScreen
import com.deerflow.app.ui.theme.DeerflowTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

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

@Serializable
private object ChatRoute

@Serializable
private object ApprovalsRoute

@Serializable
private object SettingsRoute

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
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

    NavHost(
        navController = navController,
        startDestination = ChatRoute,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(300)
            ) + fadeIn(animationSpec = tween(300))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(300)
            ) + fadeOut(animationSpec = tween(300))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(300)
            ) + fadeIn(animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(300)
            ) + fadeOut(animationSpec = tween(300))
        }
    ) {
        composable<ChatRoute> {
            ChatScreen(
                vm = chatVm,
                proposalVm = proposalVm,
                onOpenApprovals = { navController.navigate(ApprovalsRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<ApprovalsRoute> {
            ProposalScreen(
                vm = proposalVm,
                onBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private const val PROPOSAL_REFRESH_INTERVAL_MS = 30_000L
