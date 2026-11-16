package com.example.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.BlackholeScreen
import com.example.ui.screens.VideosScreen
import com.example.ui.viewmodel.BlackholeViewModel
import kotlinx.serialization.Serializable

@Serializable
object BlackholeRoute

@Serializable
object VideosRoute

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
                onOpenVideosDrawer = {
                    navController.navigate(VideosRoute) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable<VideosRoute>(
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(300),
                ) + fadeIn(tween(240))
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(260),
                ) + fadeOut(tween(220))
            },
        ) {
            VideosScreen(
                viewModel = blackholeViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
