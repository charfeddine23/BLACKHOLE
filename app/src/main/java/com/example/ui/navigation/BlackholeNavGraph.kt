package com.example.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.BlackholeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.viewmodel.BlackholeViewModel
import kotlinx.serialization.Serializable

@Serializable
object BlackholeRoute

@Serializable
object VaultRoute

@Serializable
object SettingsRoute

@Composable
fun BlackholeNavGraph(
    blackholeViewModel: BlackholeViewModel = viewModel(),
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = BlackholeRoute,
    ) {
        composable<BlackholeRoute>(
            enterTransition = { fadeIn(tween(240)) },
            exitTransition = { fadeOut(tween(220)) },
        ) {
            BlackholeScreen(
                viewModel = blackholeViewModel,
                onNavigateToVault = {
                    navController.navigate(VaultRoute) {
                        launchSingleTop = true
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(SettingsRoute) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<VaultRoute>(
            enterTransition = { fadeIn(tween(240)) },
            exitTransition = { fadeOut(tween(220)) },
        ) {
            VaultScreen(
                viewModel = blackholeViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute>(
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(200)) },
        ) {
            val noScreenshotEnabled by blackholeViewModel.noScreenshotEnabled.collectAsStateWithLifecycle()
            SettingsScreen(
                noScreenshotEnabled = noScreenshotEnabled,
                onToggleNoScreenshot = blackholeViewModel::setNoScreenshotEnabled,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
