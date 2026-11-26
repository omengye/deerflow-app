package com.deerflow.app.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.deerflow.app.ui.chat.ArtifactsScreen
import com.deerflow.app.ui.chat.ChatScreen
import com.deerflow.app.ui.chat.ChatViewModel
import com.deerflow.app.ui.memory.MemoryScreen
import com.deerflow.app.ui.proposal.ProposalScreen
import com.deerflow.app.ui.proposal.ProposalViewModel
import com.deerflow.app.ui.settings.OssSettingsScreen
import com.deerflow.app.ui.settings.SettingsScreen
import com.deerflow.app.ui.theme.DeerflowTheme
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DeerflowTheme {
                AppRoot()
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val preferences = getSharedPreferences("notification_onboarding", MODE_PRIVATE)
            if (!preferences.getBoolean("requested", false)) {
                preferences.edit().putBoolean("requested", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Serializable
private object ChatRoute

@Serializable
private object ArtifactsRoute

@Serializable
private object EvolutionRoute

@Serializable
private object MemoryRoute

@Serializable
private object SettingsRoute

@Serializable
private object OssSettingsRoute

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val chatVm: ChatViewModel = viewModel()
    val proposalVm: ProposalViewModel = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, chatVm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> chatVm.setForeground(true)
                Lifecycle.Event.ON_STOP -> chatVm.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        chatVm.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            chatVm.setForeground(false)
        }
    }

    NavHost(
        navController = navController,
        startDestination = ChatRoute,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(260),
            ) + fadeIn(animationSpec = tween(260))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(260),
            ) + fadeOut(animationSpec = tween(260))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(260),
            ) + fadeIn(animationSpec = tween(260))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(260),
            ) + fadeOut(animationSpec = tween(260))
        },
    ) {
        composable<ChatRoute> {
            ChatScreen(
                vm = chatVm,
                proposalVm = proposalVm,
                onOpenArtifacts = {
                    chatVm.refreshArtifacts()
                    navController.navigate(ArtifactsRoute)
                },
                onOpenEvolution = {
                    proposalVm.clearSelection()
                    navController.navigate(EvolutionRoute)
                },
                onOpenMemory = { navController.navigate(MemoryRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        composable<ArtifactsRoute> {
            val taskFiles by chatVm.taskFiles.collectAsStateWithLifecycle()
            val chatState by chatVm.state.collectAsStateWithLifecycle()
            ArtifactsScreen(
                state = taskFiles,
                onBack = { navController.popBackStack() },
                onRefresh = { chatVm.refreshArtifacts() },
                resolve = { chatVm.resolveArtifact(it, chatState.threadId) },
            )
        }
        composable<EvolutionRoute> {
            ProposalScreen(
                vm = proposalVm,
                onBack = { navController.popBackStack() },
            )
        }
        composable<MemoryRoute> {
            MemoryScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenMemory = { navController.navigate(MemoryRoute) },
                onOpenOss = { navController.navigate(OssSettingsRoute) },
            )
        }
        composable<OssSettingsRoute> {
            OssSettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
