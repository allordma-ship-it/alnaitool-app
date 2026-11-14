package com.sonopulse.echoguide

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Duration of one frame at ~30 fps, used for frame-by-frame stepping. */
const val FRAME_STEP_MS = 33L

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
 * Creates the screen's single ExoPlayer and ties it to the composition and lifecycle: released
 * when the caller leaves the composition, paused while the app is in the background.
 */
@Composable
fun rememberEchoPlayer(): ExoPlayer {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember { buildEchoPlayer(context) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }
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

/** Observable playback state of the current cine loop. */
@Stable
class LoopState internal constructor(private val player: ExoPlayer) {
    /** null while checking the APK assets, false when the MP4 is not bundled. */
    var available by mutableStateOf<Boolean?>(null)
        internal set
    var failed by mutableStateOf(false)
        internal set
    var isPlaying by mutableStateOf(false)
        internal set
    var speed by mutableFloatStateOf(1f)
        private set

    /** Display width / height of the decoded video, 0 until the first frame size is known. */
    var videoAspect by mutableFloatStateOf(0f)
        internal set

    /**
     * [StepPhase] the loop is currently frozen on (ED, ES or EXP), or null while it plays or after
     * the user moved away from that frame.
     */
    var frozenPhase by mutableStateOf<String?>(null)
        private set

    val showFallback: Boolean get() = available == false || failed
    val ready: Boolean get() = available == true && !failed

    /** True while the current media item is prepared and seekable. */
    internal var mediaReady = false

    private var pending: PhaseRequest? = null

    private class PhaseRequest(val phase: String, val timing: EcgTiming, val freezeAtMs: Long?)

    /** False when the step waiting for this media item wants a frozen frame instead of playback. */
    internal val autoPlay: Boolean get() = pending.let { it == null || it.phase == StepPhase.LIVE }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            frozenPhase = null
            player.play()
        }
    }

    fun setPlaybackSpeed(value: Float) {
        speed = value
        player.setPlaybackSpeed(value)
    }

    fun stepFrame(forward: Boolean) {
        player.pause()
        frozenPhase = null
        val target = player.currentPosition + if (forward) FRAME_STEP_MS else -FRAME_STEP_MS
        val duration = player.duration
        player.seekTo(target.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE))
    }

    /** Pauses on [positionMs] and shows the frozen-phase badge for [phase]. */
    fun freezeAt(phase: String, positionMs: Long) {
        player.pause()
        val duration = player.duration
        player.seekTo(positionMs.coerceIn(0L, if (duration > 0) duration - 1 else Long.MAX_VALUE))
        frozenPhase = phase
    }

    /**
     * Puts the loop into the state a guided step needs: ED / ES freeze on the first R-wave /
     * T-wave end of the loop, EXP on [freezeAtMs], LIVE keeps playing. Applied as soon as the
     * media item is ready, so it also works right after switching views.
     */
    fun requestPhase(phase: String, timing: EcgTiming, freezeAtMs: Long? = null) {
        pending = PhaseRequest(phase, timing, freezeAtMs)
        if (mediaReady) applyPending()
    }

    internal fun applyPending() {
        val request = pending ?: return
        pending = null
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        when (request.phase) {
            StepPhase.ED, StepPhase.ES -> {
                val first = beatsFor(request.timing, if (duration > 0) duration else request.timing.beatMs).first()
                freezeAt(request.phase, if (request.phase == StepPhase.ED) first.startMs else first.esMs)
            }
            StepPhase.EXP -> freezeAt(request.phase, request.freezeAtMs ?: 0L)
            else -> {
                frozenPhase = null
                player.play()
            }
        }
    }
}

/**
 * Loads [assetPath] (relative to `assets/`) into the shared [player].
 *
 * Switching the asset does NOT create a new player: the same instance receives a new media item
 * via `setMediaItem(..., resetPosition = true)`, which avoids decoder leaks and freezes.
 */
@Composable
fun rememberLoopState(player: ExoPlayer, assetPath: String): LoopState {
    val context = LocalContext.current
    val state = remember(player) { LoopState(player) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.isPlaying = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    state.mediaReady = true
                    state.applyPending()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    state.videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                state.failed = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(assetPath) {
        state.failed = false
        state.mediaReady = false
        val exists = withContext(Dispatchers.IO) { EchoRepository.assetExists(context, assetPath) }
        // ExoPlayer must only be touched on its application (main) thread.
        withContext(Dispatchers.Main) {
            state.available = exists
            if (exists) {
                player.setMediaItem(MediaItem.fromUri("asset:///$assetPath"), true)
                player.setPlaybackSpeed(state.speed)
                player.playWhenReady = state.autoPlay
                player.prepare()
            } else {
                player.stop()
                player.clearMediaItems()
            }
        }
    }
    return state
}

/**
 * Video surface for the shared player. Only one surface can show the video at a time, so pass
 * [attached] = false while another surface (e.g. the full-screen overlay) owns the player.
 */
@OptIn(UnstableApi::class)
@Composable
fun LoopSurface(player: ExoPlayer, attached: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // AndroidView assigns the layout params, so inflating without a root is intended here.
            @SuppressLint("InflateParams")
            val view = LayoutInflater.from(ctx).inflate(R.layout.echo_player_texture, null) as PlayerView
            view.apply {
                setKeepContentOnPlayerReset(false)
            }
        },
        update = { view ->
            val target = if (attached) player else null
            if (view.player !== target) view.player = target
        },
        onRelease = { view -> view.player = null }
    )
}

/** Shown instead of the video when the MP4 is missing or cannot be decoded. */
@Composable
fun LoopFallback(assetPath: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(if (compact) 8.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.VideocamOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(if (compact) 24.dp else 40.dp)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.player_missing_title),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (!compact) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.player_missing_body, assetPath),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Black video box containing either the live surface or the fallback card. */
@Composable
fun LoopBox(
    player: ExoPlayer,
    loop: LoopState,
    assetPath: String,
    attached: Boolean,
    modifier: Modifier = Modifier,
    compactFallback: Boolean = true
) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (loop.showFallback) {
            LoopFallback(assetPath, compact = compactFallback)
        } else {
            LoopSurface(player, attached, Modifier.fillMaxSize())
        }
    }
}
