package com.sonopulse.echoguide

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

/** Measurement caliper (yellow, like the scanner's caliper cursor). */
internal val CaliperYellow = Color(0xFFFFD60A)
/** Doppler cursor / M-mode line. */
internal val CursorTeal = Color(0xFF2EE6D6)
/** PW sample gate and colour-Doppler box. */
internal val GateRed = Color(0xFFFF4D6D)

// ---------------------------------------------------------------------------------------------
// Geometry
// ---------------------------------------------------------------------------------------------

/** Rectangle an image of [aspect] (width / height) occupies when fitted (letter-boxed) into [container]. */
internal fun fitRect(container: Size, aspect: Float): Rect {
    if (aspect <= 0f || container.width <= 0f || container.height <= 0f) return Rect(Offset.Zero, container)
    val containerAspect = container.width / container.height
    return if (containerAspect > aspect) {
        val w = container.height * aspect
        Rect(Offset((container.width - w) / 2f, 0f), Size(w, container.height))
    } else {
        val h = container.width / aspect
        Rect(Offset(0f, (container.height - h) / 2f), Size(container.width, h))
    }
}

/** True when [coords] is a valid normalised `[x1, y1, x2, y2]` box. */
internal fun isNormalizedBox(coords: List<Float>?): Boolean =
    coords != null && coords.size == 4 && coords.all { it in 0f..1f }

// ---------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------

/**
 * Draws [overlay] with normalised [coords] (`[x1, y1, x2, y2]`) inside [target]:
 *  * LINE         – yellow caliper `+——+` between two landmarks
 *  * DOPPLER_GATE – dashed teal cursor ending in a red PW / TDI sample gate `=||=`
 *  * CURSOR       – dashed teal M-mode / CW line
 *  * AREA         – red colour-Doppler box
 * [zoom] keeps strokes and text the same size on screen inside a scaled layer.
 */
internal fun DrawScope.drawCaliperOverlay(
    overlay: CaliperOverlay,
    coords: List<Float>,
    target: Rect,
    textMeasurer: TextMeasurer,
    zoom: Float = 1f
) {
    if (!isNormalizedBox(coords)) return
    fun at(x: Float, y: Float) = Offset(target.left + x * target.width, target.top + y * target.height)
    val a = at(coords[0], coords[1])
    val b = at(coords[2], coords[3])
    val stroke = 2.dp.toPx() / zoom
    val halo = Color.Black.copy(alpha = 0.55f)
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length = sqrt(dx * dx + dy * dy)
    // Unit vectors along and across the measurement.
    val ux = if (length > 0f) dx / length else 1f
    val uy = if (length > 0f) dy / length else 0f
    val nx = -uy
    val ny = ux

    when (overlay.type) {
        CaliperOverlay.AREA -> {
            val topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
            val size = Size(kotlin.math.abs(dx), kotlin.math.abs(dy))
            drawRect(GateRed.copy(alpha = 0.12f), topLeft, size)
            drawRect(halo, topLeft, size, style = Stroke(stroke + 2f / zoom))
            drawRect(GateRed, topLeft, size, style = Stroke(stroke))
        }

        CaliperOverlay.DOPPLER_GATE, CaliperOverlay.CURSOR -> {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx() / zoom, 4.dp.toPx() / zoom))
            drawLine(halo, a, b, strokeWidth = stroke + 2f / zoom, pathEffect = dash)
            drawLine(CursorTeal, a, b, strokeWidth = stroke, pathEffect = dash)
            if (overlay.type == CaliperOverlay.DOPPLER_GATE) {
                // Sample volume: two bars across the cursor, a gate length apart.
                val half = 3.dp.toPx() / zoom
                val bar = 7.dp.toPx() / zoom
                listOf(-half, half).forEach { s ->
                    val c = Offset(b.x + ux * s, b.y + uy * s)
                    val p = Offset(c.x - nx * bar, c.y - ny * bar)
                    val q = Offset(c.x + nx * bar, c.y + ny * bar)
                    drawLine(halo, p, q, strokeWidth = stroke + 2f / zoom, cap = StrokeCap.Round)
                    drawLine(GateRed, p, q, strokeWidth = stroke, cap = StrokeCap.Round)
                }
            } else {
                val tick = 4.dp.toPx() / zoom
                drawLine(CursorTeal, Offset(b.x - nx * tick, b.y - ny * tick), Offset(b.x + nx * tick, b.y + ny * tick), strokeWidth = stroke)
            }
        }

        else -> { // LINE
            drawLine(halo, a, b, strokeWidth = stroke + 2f / zoom, cap = StrokeCap.Round)
            drawLine(CaliperYellow, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
            val tick = 5.dp.toPx() / zoom
            listOf(a, b).forEach { p ->
                // "+" end mark: a bar across the line and a short bar along it.
                drawLine(CaliperYellow, Offset(p.x - nx * tick, p.y - ny * tick), Offset(p.x + nx * tick, p.y + ny * tick), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(CaliperYellow, Offset(p.x - ux * tick * 0.6f, p.y - uy * tick * 0.6f), Offset(p.x + ux * tick * 0.6f, p.y + uy * tick * 0.6f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
    drawOverlayLabel(overlay, a, b, target, textMeasurer, zoom)
}

private fun DrawScope.drawOverlayLabel(overlay: CaliperOverlay, a: Offset, b: Offset, target: Rect, textMeasurer: TextMeasurer, zoom: Float) {
    if (overlay.label.isBlank()) return
    val color = when (overlay.type) {
        CaliperOverlay.LINE -> CaliperYellow
        CaliperOverlay.AREA -> GateRed
        else -> CursorTeal
    }
    val layout = textMeasurer.measure(
        overlay.label,
        TextStyle(color = color, fontSize = (9f / zoom).sp, fontWeight = FontWeight.Bold)
    )
    val pad = 2.dp.toPx() / zoom
    val w = layout.size.width + 2 * pad
    val h = layout.size.height + 2 * pad
    // Anchor next to the end point (gate / second caliper mark), inside the image.
    val anchor = if (overlay.type == CaliperOverlay.AREA) Offset(maxOf(a.x, b.x), minOf(a.y, b.y)) else b
    var x = anchor.x + 6.dp.toPx() / zoom
    if (x + w > target.right) x = anchor.x - 6.dp.toPx() / zoom - w
    val y = (anchor.y - h / 2f).coerceIn(target.top, (target.bottom - h).coerceAtLeast(target.top))
    x = x.coerceIn(target.left, (target.right - w).coerceAtLeast(target.left))
    drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(x, y), Size(w, h), CornerRadius(3.dp.toPx() / zoom))
    drawText(layout, topLeft = Offset(x + pad, y + pad))
}

/**
 * Canvas drawing [overlay] over an image of [aspect] that is fitted (letter-boxed) into this
 * layout's bounds; with [aspect] ≤ 0 the image is assumed to fill the bounds.
 */
@Composable
internal fun CaliperCanvas(
    overlay: CaliperOverlay,
    coords: List<Float>?,
    aspect: Float,
    modifier: Modifier = Modifier,
    zoom: Float = 1f
) {
    if (!isNormalizedBox(coords)) return
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        drawCaliperOverlay(overlay, coords!!, fitRect(size, aspect), textMeasurer, zoom)
    }
}

// ---------------------------------------------------------------------------------------------
// Frozen-phase badge & caliper toggle
// ---------------------------------------------------------------------------------------------

/** "FROZEN · ED (R-wave)" style badge shown while the loop is frozen for a measurement step. */
@Composable
internal fun FrozenBadge(phase: String, modifier: Modifier = Modifier) {
    val (text, color) = when (phase) {
        StepPhase.ED -> stringResource(R.string.frozen_ed) to EchoTeal
        StepPhase.ES -> stringResource(R.string.frozen_es) to EchoOrange
        else -> stringResource(R.string.frozen_exp) to EchoSky
    }
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** Small corner chip that shows / hides the caliper overlay so trainees can test themselves. */
@Composable
internal fun CaliperToggle(enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (enabled) CaliperYellow else Color(0xFF94A3B8)
    val label = stringResource(if (enabled) R.string.caliper_on else R.string.caliper_off)
    val description = stringResource(R.string.caliper_toggle)
    Row(
        modifier = modifier
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, color.copy(alpha = 0.8f), RoundedCornerShape(11.dp))
            .clickable(onClick = onToggle)
            .semantics {
                role = Role.Switch
                contentDescription = description
                stateDescription = label
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Straighten, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** Persists the Caliper ON/OFF choice (SharedPreferences, offline). */
internal object CaliperPreferences {
    private const val PREFS = "echoscope_prefs"
    private const val KEY = "caliper_overlay"

    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}

/** Caliper ON/OFF state, restored from and saved to [CaliperPreferences]. */
@Composable
internal fun rememberCaliperEnabled(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val state: MutableState<Boolean> = remember { mutableStateOf(CaliperPreferences.load(context)) }
    return state.value to {
        state.value = !state.value
        CaliperPreferences.save(context, state.value)
    }
}
