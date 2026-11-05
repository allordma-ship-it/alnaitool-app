package com.aurastream.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurastream.tv.R
import com.aurastream.tv.ui.auraViewModel
import com.aurastream.tv.ui.components.AuraButton
import com.aurastream.tv.ui.components.CenteredMessage
import com.aurastream.tv.ui.components.LoadingIndicator
import kotlinx.coroutines.delay

enum class OsdPanel { NONE, AUDIO, SUBTITLES, CHANNELS }

@Composable
fun PlayerScreen(onExit: () -> Unit) {
    val vm = auraViewModel { c, saved -> PlayerViewModel(saved, c.channelRepository, c.settings, c.player, c.appScope) }
    val screen by vm.state.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    val exit = {
        vm.persistResumePoint()
        onExit()
    }

    var osdVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(OsdPanel.NONE) }
    var interaction by remember { mutableIntStateOf(0) } // bump to restart the auto-hide timer
    val rootFocus = remember { FocusRequester() }

    fun showOsd() {
        osdVisible = true
        interaction++
    }

    // Auto-hide OSD after inactivity (kept visible while a panel is open or on errors).
    LaunchedEffect(osdVisible, interaction, panel, playback.error) {
        if (osdVisible && panel == OsdPanel.NONE && playback.error == null) {
            delay(OSD_TIMEOUT_MS)
            osdVisible = false
        }
    }
    LaunchedEffect(osdVisible) { if (!osdVisible) runCatching { rootFocus.requestFocus() } }

    BackHandler {
        when {
            panel != OsdPanel.NONE -> panel = OsdPanel.NONE
            osdVisible && playback.error == null -> osdVisible = false
            else -> exit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                when (ev.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { vm.player.togglePlayPause(); true }
                    Key.ChannelUp, Key.PageUp -> { vm.zap(-1); showOsd(); true }
                    Key.ChannelDown, Key.PageDown -> { vm.zap(1); showOsd(); true }
                    Key.MediaFastForward -> { vm.player.seekBy(30_000); showOsd(); true }
                    Key.MediaRewind -> { vm.player.seekBy(-30_000); showOsd(); true }
                    else -> if (osdVisible) {
                        false // let the OSD handle focus navigation
                    } else {
                        when (ev.key) {
                            Key.DirectionUp -> { if (screen.isLive) vm.zap(-1) else showOsd(); true }
                            Key.DirectionDown -> { if (screen.isLive) vm.zap(1) else showOsd(); true }
                            Key.DirectionLeft -> { if (!screen.isLive) vm.player.seekBy(-10_000); showOsd(); true }
                            Key.DirectionRight -> { if (!screen.isLive) vm.player.seekBy(10_000); showOsd(); true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> { showOsd(); true }
                            else -> false
                        }
                    }
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (osdVisible) osdVisible = false else showOsd()
            },
    ) {
        if (screen.notFound) {
            CenteredMessage(title = stringResource(R.string.player_not_found)) {
                AuraButton(stringResource(R.string.back), onClick = exit, primary = true)
            }
            return@Box
        }

        PlayerSurface(vm.player, Modifier.fillMaxSize())

        if (playback.isBuffering && playback.error == null) LoadingIndicator()

        playback.error?.let { error ->
            PlayerErrorOverlay(error, onRetry = { vm.player.retry() }, onExit = exit)
        }

        AnimatedVisibility(visible = osdVisible && playback.error == null, enter = fadeIn(), exit = fadeOut()) {
            PlayerOsd(
                screen = screen,
                playback = playback,
                vm = vm,
                onOpenPanel = { panel = it; interaction++ },
                onInteraction = { interaction++ },
            )
        }

        AnimatedVisibility(
            visible = panel != OsdPanel.NONE,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            OsdSidePanel(
                panel = panel,
                screen = screen,
                playback = playback,
                vm = vm,
                onClose = { panel = OsdPanel.NONE },
            )
        }
    }

    LaunchedEffect(Unit) {
        runCatching { rootFocus.requestFocus() }
    }
}

private const val OSD_TIMEOUT_MS = 5_000L
