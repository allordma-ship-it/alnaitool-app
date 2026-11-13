package com.sonopulse.echoguide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.exp

/** Medical-monitor green for the ECG trace. */
internal val EcgGreen = Color(0xFF00E676)
internal val EcgTeal = Color(0xFF00D2B8)
internal val EcgOrange = Color(0xFFFF8A3D)
private val EcgPaper = Color(0xFF050B0E)
private val EcgGridMinor = Color(0xFF0E2A1E)
private val EcgGridMajor = Color(0xFF16402D)
private val EcgCursor = Color(0xFFF8FAFC)

/** Beats shown when the clip duration is not known yet. */
private const val DEFAULT_BEATS = 2

/**
 * Synthetic lead-II waveform for one beat, phase in [0, 1) with the R-wave peak at 0.
 * [tEnd] is the end of the T-wave (end-systole) as a fraction of the beat.
 */
internal fun ecgAmplitude(phase: Double, tEnd: Double): Double {
    fun g(center: Double, width: Double) = exp(-((phase - center) / width).let { it * it })
    val tCenter = tEnd - 0.09
    var v = 0.0
    v += 0.12 * g(0.88, 0.025)                    // P wave
    v -= 0.10 * g(-0.02, 0.008) + 0.10 * g(0.98, 0.008) // Q
    v += 1.00 * g(0.0, 0.010) + 1.00 * g(1.0, 0.010)    // R (wraps at the beat boundary)
    v -= 0.22 * g(0.025, 0.010)                   // S
    v += 0.26 * g(tCenter, 0.045)                 // T wave
    return v
}

/** One cardiac cycle inside the clip: R-wave time, length and end-systole time (all ms). */
internal data class Beat(val startMs: Long, val lengthMs: Long, val esMs: Long)

/**
 * Beats covering [windowMs]: from explicit R-waves when the timing has them (real or joined
 * clips), otherwise a regular rhythm of `beatMs` starting at `edMs`.
 */
internal fun beatsFor(timing: EcgTiming, windowMs: Long): List<Beat> {
    val regular = timing.beatMs.coerceAtLeast(1)
    val esFraction = (timing.esMs - timing.edMs).toDouble() / regular
    val starts = if (timing.rWavesMs.isNotEmpty()) {
        timing.rWavesMs.sorted()
    } else {
        generateSequence(timing.edMs) { it + regular }.takeWhile { it < windowMs.coerceAtLeast(1) }.toList()
    }
    return starts.mapIndexed { i, start ->
        val length = (starts.getOrNull(i + 1) ?: (start + (if (i > 0) start - starts[i - 1] else regular))) - start
        Beat(start, length, start + Math.round(length * esFraction))
    }
}

/** The beat containing [positionMs]; positions before the first R-wave belong to a virtual previous beat. */
internal fun beatAt(positionMs: Long, beats: List<Beat>): Beat {
    val first = beats.first()
    if (positionMs < first.startMs) {
        val start = first.startMs - first.lengthMs
        return Beat(start, first.lengthMs, start + (first.esMs - first.startMs))
    }
    return beats.last { it.startMs <= positionMs }
}

/** Target position (ms) for a jump to end-diastole or end-systole of the current beat. */
internal fun phaseJumpTarget(positionMs: Long, durationMs: Long, timing: EcgTiming, endSystole: Boolean): Long {
    val window = if (durationMs > 0) durationMs else Long.MAX_VALUE / 4
    val position = if (durationMs > 0) positionMs.coerceIn(0, durationMs - 1) else positionMs.coerceAtLeast(0)
    val beats = beatsFor(timing, if (durationMs > 0) durationMs else position + timing.beatMs)
    val beat = beatAt(position, beats)
    var target = if (endSystole) beat.esMs else beat.startMs
    if (target >= window) {
        val previous = beats.lastOrNull { (if (endSystole) it.esMs else it.startMs) < window }
        target = previous?.let { if (endSystole) it.esMs else it.startMs } ?: 0L
    }
    return target.coerceIn(0, if (durationMs > 0) durationMs - 1 else Long.MAX_VALUE)
}

/** Player position and duration for the ECG cursor. */
private class PlaybackClock {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)

    fun update(player: Player) {
        positionMs = player.currentPosition
        durationMs = player.duration.takeIf { it > 0 } ?: 0L
        playing = player.isPlaying
    }
}

/**
 * Follows the player every frame while it plays; while paused it updates only on player events
 * (seek, ±33 ms frame step, ED/ES jump, new clip), so no frames are drawn needlessly.
 */
@Composable
private fun rememberPlaybackClock(player: ExoPlayer): PlaybackClock {
    val clock = remember(player) { PlaybackClock() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = clock.update(player)
        }
        player.addListener(listener)
        clock.update(player)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, clock.playing) {
        while (clock.playing) {
            withFrameMillis { }
            clock.update(player)
        }
    }
    return clock
}

/**
 * Compact synchronized ECG & transport bar (54 dp): a live lead-II trace (42 dp) with teal ED
 * (R-wave) and orange ES (T-wave end) markers on every beat, followed by Play/Pause, a 0.5×
 * toggle and ED / ES jump buttons that pause the loop on the exact frame.
 */
@Composable
fun EcgTransportBar(
    player: ExoPlayer,
    loop: LoopState,
    timing: EcgTiming,
    modifier: Modifier = Modifier
) {
    val clock = rememberPlaybackClock(player)
    val enabled = loop.ready && clock.durationMs > 0
    // Time always runs left → right, also in Arabic.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(EcgPaper)
                .border(1.dp, EcgGridMajor, RoundedCornerShape(12.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            EcgTrace(
                positionMs = clock.positionMs,
                durationMs = clock.durationMs,
                timing = timing,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
            )
            TransportButton(enabled = loop.ready, onClick = { loop.togglePlay() }) {
                Icon(
                    imageVector = if (loop.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (loop.isPlaying) R.string.player_pause else R.string.player_play),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            TransportPill(
                text = "0.5x",
                color = Color(0xFFCBD5E1),
                selected = loop.speed == 0.5f,
                enabled = loop.ready
            ) { loop.setPlaybackSpeed(if (loop.speed == 0.5f) 1f else 0.5f) }
            TransportPill(text = "ED", color = EcgTeal, selected = false, enabled = enabled) {
                player.pause()
                player.seekTo(phaseJumpTarget(player.currentPosition, clock.durationMs, timing, endSystole = false))
            }
            TransportPill(text = "ES", color = EcgOrange, selected = false, enabled = enabled) {
                player.pause()
                player.seekTo(phaseJumpTarget(player.currentPosition, clock.durationMs, timing, endSystole = true))
            }
        }
    }
}

@Composable
private fun TransportButton(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) EcgTeal.copy(alpha = 0.25f) else Color(0x22FFFFFF))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun TransportPill(text: String, color: Color, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) color.copy(alpha = 0.35f * alpha) else color.copy(alpha = 0.12f * alpha))
            .border(1.dp, color.copy(alpha = 0.7f * alpha), RoundedCornerShape(15.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color.copy(alpha = alpha), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Lead-II trace across the whole clip with ED/ES markers and a cursor at [positionMs]. */
@Composable
private fun EcgTrace(positionMs: Long, durationMs: Long, timing: EcgTiming, modifier: Modifier = Modifier) {
    val windowMs = if (durationMs > 0) durationMs else timing.beatMs.coerceAtLeast(1) * DEFAULT_BEATS
    val beats = remember(timing, windowMs) { beatsFor(timing, windowMs) }
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        drawEcgGrid()
        val w = size.width
        val h = size.height
        val baseline = h * 0.66f
        val amplitude = h * 0.48f
        fun xOf(ms: Long) = (ms.toFloat() / windowMs) * w
        fun yOf(ms: Float): Float {
            val b = beatAt(ms.toLong(), beats)
            val phase = ((ms - b.startMs) / b.lengthMs).toDouble()
            val tEnd = (b.esMs - b.startMs).toDouble() / b.lengthMs
            return baseline - (ecgAmplitude(phase, tEnd) * amplitude).toFloat()
        }
        beats.forEach { b ->
            if (b.startMs <= windowMs) drawPhaseMarker(xOf(b.startMs), EcgTeal, "ED", textMeasurer)
            if (b.esMs <= windowMs) drawPhaseMarker(xOf(b.esMs), EcgOrange, "ES", textMeasurer)
        }
        val path = Path()
        var x = 0f
        while (x <= w) {
            val y = yOf((x / w) * windowMs)
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 2f
        }
        drawPath(path, EcgGreen, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val clamped = positionMs.coerceIn(0, windowMs)
        val cx = xOf(clamped)
        val cy = yOf(clamped.toFloat())
        drawLine(EcgCursor.copy(alpha = 0.85f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.2.dp.toPx())
        drawCircle(EcgGreen.copy(alpha = 0.35f), radius = 5.dp.toPx(), center = Offset(cx, cy))
        drawCircle(EcgCursor, radius = 2.4.dp.toPx(), center = Offset(cx, cy))
    }
}

private fun DrawScope.drawEcgGrid() {
    val minor = 4.dp.toPx()
    var x = 0f
    var i = 0
    while (x <= size.width) {
        drawLine(if (i % 5 == 0) EcgGridMajor else EcgGridMinor, Offset(x, 0f), Offset(x, size.height), 1f)
        x += minor; i++
    }
    var y = 0f
    i = 0
    while (y <= size.height) {
        drawLine(if (i % 5 == 0) EcgGridMajor else EcgGridMinor, Offset(0f, y), Offset(size.width, y), 1f)
        y += minor; i++
    }
}

private fun DrawScope.drawPhaseMarker(x: Float, color: Color, label: String, textMeasurer: TextMeasurer) {
    drawLine(
        color.copy(alpha = 0.8f),
        Offset(x, 10.dp.toPx()),
        Offset(x, size.height),
        strokeWidth = 1.2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))
    )
    val layout = textMeasurer.measure(label, TextStyle(color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold))
    val left = (x + 2.dp.toPx()).coerceAtMost(size.width - layout.size.width - 1.dp.toPx())
    drawText(layout, topLeft = Offset(left, 0f))
}
