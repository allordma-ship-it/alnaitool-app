package com.sonopulse.echoguide

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Duration of one frame at ~30 fps, used for frame-by-frame stepping. */
const val FRAME_STEP_MS = 33L

private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 1.0f)

/**
 * Builds the single ExoPlayer used for every cine loop.
 *
 * Small buffers (500 / 2000 / 250 / 500 ms) keep memory low for short local loops, and the
 * player is reused for all views so only one hardware decoder is ever allocated.
 */
@OptIn(UnstableApi::class)
private fun buildEchoPlayer(context: Context): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 500,
            /* maxBufferMs = */ 2000,
            /* bufferForPlaybackMs = */ 250,
            /* bufferForPlaybackAfterRebufferMs = */ 500
        )
        .build()
    return ExoPlayer.Builder(context.applicationContext)
        .setLoadControl(loadControl)
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
        }
}

/**
 * Creates the screen's single ExoPlayer and ties it to the composition and lifecycle:
 * it is released when the caller leaves the composition and paused while the app is in the
 * background. Share the returned instance between [EchoCinePlayer] and [EchoEcgStrip] so the
 * cine loop and the ECG cursor move in lockstep.
 */
@Composable
fun rememberEchoPlayer(): ExoPlayer {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember { buildEchoPlayer(context) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Pause in background, resume when visible again.
    DisposableEffect(lifecycleOwner, player) {
        var resumeOnForeground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resumeOnForeground = player.playWhenReady
                    player.pause()
                }
                Lifecycle.Event.ON_RESUME -> if (resumeOnForeground) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return player
}

/**
 * Reusable cine-loop player.
 *
 * Switching [videoAssetPath] does NOT create a new player: the same instance receives a new
 * media item via `setMediaItem(..., resetPosition = true)`, which avoids decoder leaks and the
 * freezes caused by tearing down and re-creating codecs on every view change.
 *
 * @param videoAssetPath path relative to `assets/`, e.g. `loops/apical_4c.mp4`.
 * @param player shared instance from [rememberEchoPlayer].
 */
@OptIn(UnstableApi::class)
@Composable
fun EchoCinePlayer(
    videoAssetPath: String,
    modifier: Modifier = Modifier,
    source: VideoSource = VideoSource(),
    player: ExoPlayer = rememberEchoPlayer()
) {
    val context = LocalContext.current

    var isPlaying by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var playbackFailed by remember { mutableStateOf(false) }

    var assetAvailable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(videoAssetPath) {
        assetAvailable = withContext(Dispatchers.IO) { EchoRepository.assetExists(context, videoAssetPath) }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Swap media on the existing instance whenever the selected view changes.
    LaunchedEffect(videoAssetPath, assetAvailable) {
        playbackFailed = false
        when (assetAvailable) {
            true -> {
                player.setMediaItem(MediaItem.fromUri("asset:///$videoAssetPath"), true)
                player.setPlaybackSpeed(speed)
                player.prepare()
                player.play()
            }
            false -> {
                player.stop()
                player.clearMediaItems()
            }
            null -> Unit
        }
    }

    val showFallback = assetAvailable == false || playbackFailed

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (showFallback) {
                MissingLoopFallback(videoAssetPath)
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            useController = false
                            setShutterBackgroundColor(AndroidColor.BLACK)
                            setKeepContentOnPlayerReset(false)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = player
                        }
                    },
                    update = { view -> if (view.player !== player) view.player = player },
                    onRelease = { view -> view.player = null }
                )
            }
        }

        if (!showFallback) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = if (source.isSimulated) {
                    stringResource(R.string.player_simulated_note)
                } else {
                    stringResource(
                        R.string.player_real_credit,
                        source.author.orEmpty().ifBlank { "Wikimedia Commons" },
                        source.license.orEmpty()
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Spacer(Modifier.size(10.dp))

        // Media transport controls keep their left-to-right order in RTL languages.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedIconButton(
                        enabled = !showFallback,
                        onClick = {
                            player.pause()
                            player.seekTo((player.currentPosition - FRAME_STEP_MS).coerceAtLeast(0L))
                        }
                    ) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.player_step_back))
                    }
                    Spacer(Modifier.width(6.dp))
                    FilledIconButton(
                        enabled = !showFallback,
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.player_pause else R.string.player_play
                            )
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedIconButton(
                        enabled = !showFallback,
                        onClick = {
                            player.pause()
                            val duration = player.duration
                            val target = player.currentPosition + FRAME_STEP_MS
                            player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
                        }
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_step_forward))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PLAYBACK_SPEEDS.forEach { option ->
                        FilterChip(
                            selected = speed == option,
                            enabled = !showFallback,
                            onClick = {
                                speed = option
                                player.setPlaybackSpeed(option)
                            },
                            label = {
                                Text(
                                    text = if (option == 1.0f) "1.0x" else "${option}x",
                                    fontSize = 12.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingLoopFallback(videoAssetPath: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.VideocamOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = stringResource(R.string.player_missing_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.player_missing_body, videoAssetPath),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
