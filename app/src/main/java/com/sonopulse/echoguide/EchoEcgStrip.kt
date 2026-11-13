package com.sonopulse.echoguide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.exp
import kotlin.math.floor

/** Medical-monitor green for the ECG trace. */
private val EcgGreen = Color(0xFF00E676)
private val EcgTeal = Color(0xFF00D2B8)
private val EcgOrange = Color(0xFFFF8A3D)
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

/** Start of the beat (ms) that contains [positionMs], aligned on the R-wave. */
internal fun beatStartMs(positionMs: Long, timing: EcgTiming): Long {
    val beat = timing.beatMs.coerceAtLeast(1)
    val index = floor((positionMs - timing.edMs).toDouble() / beat).toLong().coerceAtLeast(0)
    return timing.edMs + index * beat
}

/** Target position (ms) for a jump to end-diastole or end-systole of the current beat. */
internal fun phaseJumpTarget(positionMs: Long, durationMs: Long, timing: EcgTiming, endSystole: Boolean): Long {
    val position = if (durationMs > 0) positionMs.coerceIn(0, durationMs - 1) else positionMs.coerceAtLeast(0)
    val start = beatStartMs(position, timing)
    var target = if (endSystole) start + (timing.esMs - timing.edMs) else start
    if (durationMs > 0 && target >= durationMs) target -= timing.beatMs
    return target.coerceIn(0, if (durationMs > 0) durationMs - 1 else Long.MAX_VALUE)
}

/**
 * ECG strip docked under [EchoCinePlayer]. The cursor follows the shared [player] every frame,
 * so it moves with playback, speed changes and ±33 ms frame stepping. ED (R-wave) and ES
 * (T-wave end) are marked on every beat, and the two pills pause the loop on those frames.
 */
@Composable
fun EchoEcgStrip(
    player: ExoPlayer,
    timing: EcgTiming,
    timingNote: String?,
    modifier: Modifier = Modifier
) {
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(player) {
        while (true) {
            withFrameMillis { }
            positionMs = player.currentPosition
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
        }
    }

    val beat = timing.beatMs.coerceAtLeast(1)
    val windowMs = if (durationMs > 0) durationMs else beat * DEFAULT_BEATS
    val inBeat = ((positionMs - timing.edMs) % beat + beat) % beat
    val systole = inBeat < (timing.esMs - timing.edMs)

    val edLabel = stringResource(R.string.ecg_ed_label)
    val esLabel = stringResource(R.string.ecg_es_label)
    val leadLabel = stringResource(R.string.ecg_lead)
    val phaseLabel = stringResource(if (systole) R.string.ecg_systole else R.string.ecg_diastole)
    val textMeasurer = rememberTextMeasurer()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Time always runs left → right, also in Arabic.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(EcgPaper)
                    .border(1.dp, EcgGridMajor, RoundedCornerShape(12.dp))
            ) {
                drawEcgGrid()

                val w = size.width
                val h = size.height
                val baseline = h * 0.62f
                val amplitude = h * 0.42f
                val tEnd = (timing.esMs - timing.edMs).toDouble() / beat
                fun xOf(ms: Long) = (ms.toFloat() / windowMs) * w

                // ED / ES markers on every beat
                var k = 0L
                while (true) {
                    val ed = timing.edMs + k * beat
                    if (ed > windowMs) break
                    val es = ed + (timing.esMs - timing.edMs)
                    drawPhaseMarker(xOf(ed), EcgTeal, if (k == 0L) edLabel else "ED", textMeasurer)
                    if (es <= windowMs) {
                        drawPhaseMarker(xOf(es), EcgOrange, if (k == 0L) esLabel else "ES", textMeasurer)
                    }
                    k++
                }

                // Waveform
                val path = Path()
                val step = 2f
                var x = 0f
                while (x <= w) {
                    val ms = (x / w) * windowMs
                    val phase = (((ms - timing.edMs) % beat) + beat) % beat / beat
                    val y = baseline - (ecgAmplitude(phase.toDouble(), tEnd) * amplitude).toFloat()
                    if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                    x += step
                }
                drawPath(
                    path,
                    EcgGreen,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Synchronized cursor
                val cx = xOf(positionMs.coerceIn(0, windowMs))
                val cPhase = inBeat.toDouble() / beat
                val cy = baseline - (ecgAmplitude(cPhase, tEnd) * amplitude).toFloat()
                drawLine(EcgCursor.copy(alpha = 0.85f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5.dp.toPx())
                drawCircle(EcgGreen.copy(alpha = 0.35f), radius = 7.dp.toPx(), center = Offset(cx, cy))
                drawCircle(EcgCursor, radius = 3.dp.toPx(), center = Offset(cx, cy))

                // Lead + live phase readout
                drawText(
                    textMeasurer, leadLabel, Offset(8.dp.toPx(), h - 18.dp.toPx()),
                    TextStyle(color = EcgGreen.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                )
                val phaseLayout = textMeasurer.measure(
                    phaseLabel,
                    TextStyle(color = if (systole) EcgOrange else EcgTeal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                )
                drawText(phaseLayout, topLeft = Offset(w - phaseLayout.size.width - 8.dp.toPx(), h - 18.dp.toPx()))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PhaseJumpPill(
                text = stringResource(R.string.ecg_jump_ed),
                color = EcgTeal,
                enabled = durationMs > 0,
                modifier = Modifier.weight(1f)
            ) {
                player.pause()
                player.seekTo(phaseJumpTarget(player.currentPosition, durationMs, timing, endSystole = false))
            }
            PhaseJumpPill(
                text = stringResource(R.string.ecg_jump_es),
                color = EcgOrange,
                enabled = durationMs > 0,
                modifier = Modifier.weight(1f)
            ) {
                player.pause()
                player.seekTo(phaseJumpTarget(player.currentPosition, durationMs, timing, endSystole = true))
            }
        }

        if (!timingNote.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EcgTeal.copy(alpha = 0.08f))
                    .border(1.dp, EcgTeal.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = EcgTeal, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timingNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun PhaseJumpPill(
    text: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.14f),
            labelColor = color
        ),
        border = AssistChipDefaults.assistChipBorder(enabled = enabled, borderColor = color.copy(alpha = 0.6f))
    )
}

private fun DrawScope.drawEcgGrid() {
    val minor = 5.dp.toPx()
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

private fun DrawScope.drawPhaseMarker(
    x: Float,
    color: Color,
    label: String,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    drawLine(
        color.copy(alpha = 0.8f),
        Offset(x, 14.dp.toPx()),
        Offset(x, size.height),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
    )
    val layout = textMeasurer.measure(label, TextStyle(color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold))
    val left = (x + 3.dp.toPx()).coerceAtMost(size.width - layout.size.width - 2.dp.toPx())
    drawText(layout, topLeft = Offset(left, 2.dp.toPx()))
}
