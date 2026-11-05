package com.aurastream.tv.ui.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aurastream.tv.AppContainer
import com.aurastream.tv.ui.activation.ActivationScreen
import com.aurastream.tv.ui.channels.LiveTvScreen
import com.aurastream.tv.ui.epg.EpgScreen
import com.aurastream.tv.ui.home.HomeScreen
import com.aurastream.tv.ui.player.PlayerScreen
import com.aurastream.tv.ui.settings.SettingsScreen
import com.aurastream.tv.ui.startup.StartupScreen
import com.aurastream.tv.ui.vod.VodDetailScreen
import com.aurastream.tv.ui.vod.VodScreen

object Routes {
    const val STARTUP = "startup"
    const val ACTIVATION = "activation"
    const val HOME = "home"
    const val LIVE = "live"
    const val VOD = "vod/{type}"
    const val DETAIL = "detail/{streamId}"
    const val PLAYER = "player/{streamId}?episodeId={episodeId}"
    const val EPG = "epg"
    const val SETTINGS = "settings"

    fun vod(type: String) = "vod/$type"
    fun detail(streamId: String) = "detail/${Uri.encode(streamId)}"
    fun player(streamId: String, episodeId: String? = null) =
        "player/${Uri.encode(streamId)}" + (episodeId?.let { "?episodeId=${Uri.encode(it)}" } ?: "")
}

@Composable
fun AuraNavHost(container: AppContainer, nav: NavHostController = rememberNavController()) {
    NavHost(
        navController = nav,
        startDestination = Routes.STARTUP,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        composable(Routes.STARTUP) {
            StartupScreen(
                onReady = { nav.navigate(Routes.HOME) { popUpTo(Routes.STARTUP) { inclusive = true } } },
                onNeedsActivation = { nav.navigate(Routes.ACTIVATION) { popUpTo(Routes.STARTUP) { inclusive = true } } },
            )
        }
        composable(Routes.ACTIVATION) {
            ActivationScreen(
                onActivated = { nav.navigate(Routes.HOME) { popUpTo(Routes.ACTIVATION) { inclusive = true } } },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenLive = { nav.navigate(Routes.LIVE) },
                onOpenVod = { type -> nav.navigate(Routes.vod(type)) },
                onOpenEpg = { nav.navigate(Routes.EPG) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenItem = { item ->
                    if (item.streamType == "live") nav.navigate(Routes.player(item.streamId)) else nav.navigate(Routes.detail(item.streamId))
                },
                onNeedsActivation = { nav.navigate(Routes.ACTIVATION) { popUpTo(Routes.HOME) { inclusive = true } } },
            )
        }
        composable(Routes.LIVE) {
            LiveTvScreen(
                onOpenFullscreen = { streamId -> nav.navigate(Routes.player(streamId)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.VOD, arguments = listOf(navArgument("type") { type = NavType.StringType })) {
            VodScreen(
                onOpenDetail = { streamId -> nav.navigate(Routes.detail(streamId)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("streamId") { type = NavType.StringType })) {
            VodDetailScreen(
                onPlay = { streamId, episodeId -> nav.navigate(Routes.player(streamId, episodeId)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(
                navArgument("streamId") { type = NavType.StringType },
                navArgument("episodeId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            PlayerScreen(onExit = { nav.popBackStack() })
        }
        composable(Routes.EPG) {
            EpgScreen(
                onPlay = { streamId -> nav.navigate(Routes.player(streamId)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
