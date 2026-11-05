package com.aurastream.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.aurastream.tv.ui.appContainer

/** Screens without a video surface stop the shared player so no audio keeps running in the background. */
@Composable
fun StopPlaybackEffect() {
    val player = appContainer().player
    LaunchedEffect(Unit) { player.stop() }
}
