package com.sonopulse.echoguide

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
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
 * Canvas drawing [overlays] over an image of [aspect] that is fitted (letter-boxed) into this
 * layout's bounds; with [aspect] ≤ 0 the image is assumed to fill the bounds. [coords] picks
 * the coordinate set (video / diagram) of each overlay.
 */
@Composable
internal fun CaliperCanvas(
    overlays: List<CaliperOverlay>,
    coords: (CaliperOverlay) -> List<Float>?,
    aspect: Float,
    modifier: Modifier = Modifier,
    zoom: Float = 1f
) {
    val drawable = overlays.filter { isNormalizedBox(coords(it)) }
    if (drawable.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val rect = fitRect(size, aspect)
        drawable.forEach { drawCaliperOverlay(it, coords(it)!!, rect, textMeasurer, zoom) }
    }
}

// ---------------------------------------------------------------------------------------------
// Drag-to-practice
// ---------------------------------------------------------------------------------------------

/** Key of a trainee-moved caliper in the practice map: "<view>#<step>#<overlay index>". */
internal fun practiceKey(prefix: String, index: Int) = "$prefix#$index"

private data class Grab(val overlay: Int, val end: Int)

/**
 * Interactive caliper layer on the frozen video frame: the two "+" ends of every LINE caliper
 * can be dragged to practise the measurement. While dragging, a 2.5x loupe above the finger
 * shows the frame under the end point. The reference position stays visible as a faint dashed
 * line once a caliper has been moved. A tap that does not grab an end calls [onTap].
 */
@Composable
internal fun CaliperPracticeLayer(
    overlays: List<CaliperOverlay>,
    practice: SnapshotStateMap<String, List<Float>>,
    keyPrefix: String,
    videoAspect: Float,
    captureFrame: () -> Bitmap?,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    var drag by remember { mutableStateOf<Pair<Grab, Offset>?>(null) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    fun current(i: Int): List<Float>? = practice[practiceKey(keyPrefix, i)] ?: overlays[i].video

    Canvas(
        modifier = modifier.pointerInput(overlays, keyPrefix, videoAspect) {
            val grabRadius = 26.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val rect = fitRect(Size(size.width.toFloat(), size.height.toFloat()), videoAspect)
                var best: Grab? = null
                var bestDistance = grabRadius
                overlays.forEachIndexed { i, o ->
                    val c = current(i)
                    if (o.type == CaliperOverlay.LINE && isNormalizedBox(c)) {
                        for (end in 0..1) {
                            val p = Offset(rect.left + c!![2 * end] * rect.width, rect.top + c[2 * end + 1] * rect.height)
                            val d = (p - down.position).getDistance()
                            if (d < bestDistance) {
                                bestDistance = d
                                best = Grab(i, end)
                            }
                        }
                    }
                }
                val grab = best
                if (grab == null) {
                    val up = waitForUpOrCancellation()
                    if (up != null && onTap != null) {
                        up.consume()
                        onTap()
                    }
                    return@awaitEachGesture
                }
                down.consume()
                frame = captureFrame()?.asImageBitmap()
                drag = grab to down.position
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val p = change.position
                    val c = (current(grab.overlay) ?: break).toMutableList()
                    c[2 * grab.end] = ((p.x - rect.left) / rect.width).coerceIn(0f, 1f)
                    c[2 * grab.end + 1] = ((p.y - rect.top) / rect.height).coerceIn(0f, 1f)
                    practice[practiceKey(keyPrefix, grab.overlay)] = c
                    drag = grab to p
                    change.consume()
                }
                drag = null
                frame = null
            }
        }
    ) {
        val rect = fitRect(size, videoAspect)
        overlays.forEachIndexed { i, o ->
            val reference = o.video
            val moved = practice[practiceKey(keyPrefix, i)]
            if (moved != null && isNormalizedBox(reference)) drawReferenceGhost(reference!!, rect)
            val c = current(i) ?: return@forEachIndexed
            drawCaliperOverlay(o, c, rect, textMeasurer)
            if (o.type == CaliperOverlay.LINE && isNormalizedBox(c)) {
                for (end in 0..1) {
                    val p = Offset(rect.left + c[2 * end] * rect.width, rect.top + c[2 * end + 1] * rect.height)
                    drawCircle(CaliperYellow.copy(alpha = 0.22f), radius = 11.dp.toPx(), center = p)
                    drawCircle(CaliperYellow.copy(alpha = 0.8f), radius = 11.dp.toPx(), center = p, style = Stroke(1.dp.toPx()))
                }
            }
        }
        drag?.let { (grab, finger) ->
            val c = current(grab.overlay) ?: return@let
            val handle = Offset(rect.left + c[2 * grab.end] * rect.width, rect.top + c[2 * grab.end + 1] * rect.height)
            drawLoupe(handle, finger, rect, frame, c)
        }
    }
}

/** Faint dashed reference line, shown once the trainee has moved the caliper. */
private fun DrawScope.drawReferenceGhost(coords: List<Float>, rect: Rect) {
    val a = Offset(rect.left + coords[0] * rect.width, rect.top + coords[1] * rect.height)
    val b = Offset(rect.left + coords[2] * rect.width, rect.top + coords[3] * rect.height)
    drawLine(
        Color.White.copy(alpha = 0.55f), a, b, strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
    )
}

/** Magnifier circle above the finger: the frame around [handle] at 2.5x with a crosshair. */
private fun DrawScope.drawLoupe(handle: Offset, finger: Offset, rect: Rect, frame: ImageBitmap?, coords: List<Float>) {
    val r = 40.dp.toPx()
    val gap = 30.dp.toPx()
    val magnification = 2.5f
    var center = Offset(finger.x, finger.y - r - gap)
    if (center.y - r < 0f) center = Offset(finger.x, finger.y + r + gap)
    center = Offset(center.x.coerceIn(r, size.width - r), center.y.coerceIn(r, (size.height - r).coerceAtLeast(r)))
    val circle = Path().apply { addOval(Rect(center, r)) }
    clipPath(circle) {
        drawRect(Color.Black, topLeft = center - Offset(r, r), size = Size(2 * r, 2 * r))
        if (frame != null && rect.width > 0f && rect.height > 0f) {
            val sx = frame.width / rect.width
            val sy = frame.height / rect.height
            val halfW = (r / magnification * sx).coerceAtMost(frame.width / 2f)
            val halfH = (r / magnification * sy).coerceAtMost(frame.height / 2f)
            val bx = ((handle.x - rect.left) * sx).coerceIn(halfW, frame.width - halfW)
            val by = ((handle.y - rect.top) * sy).coerceIn(halfH, frame.height - halfH)
            // Keep the magnified frame aligned with the handle even where the source was clamped.
            val shift = Offset(
                ((handle.x - rect.left) * sx - bx) / sx * magnification,
                ((handle.y - rect.top) * sy - by) / sy * magnification
            )
            drawImage(
                frame,
                srcOffset = IntOffset((bx - halfW).roundToInt(), (by - halfH).roundToInt()),
                srcSize = IntSize((2 * halfW).roundToInt(), (2 * halfH).roundToInt()),
                dstOffset = IntOffset((center.x - r - shift.x).roundToInt(), (center.y - r - shift.y).roundToInt()),
                dstSize = IntSize((2 * r).roundToInt(), (2 * r).roundToInt())
            )
        }
        // The caliper inside the loupe, magnified around the handle.
        fun m(x: Float, y: Float) = center + (Offset(rect.left + x * rect.width, rect.top + y * rect.height) - handle) * magnification
        drawLine(CaliperYellow, m(coords[0], coords[1]), m(coords[2], coords[3]), strokeWidth = 1.5.dp.toPx())
        val t = 7.dp.toPx()
        drawLine(Color.White.copy(alpha = 0.9f), center - Offset(t, 0f), center + Offset(t, 0f), strokeWidth = 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.9f), center - Offset(0f, t), center + Offset(0f, t), strokeWidth = 1.dp.toPx())
    }
    drawCircle(CaliperYellow, radius = r, center = center, style = Stroke(2.dp.toPx()))
}

/** Snaps every practised caliper of the step back to the reference position. */
@Composable
internal fun CaliperResetChip(onReset: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, CursorTeal.copy(alpha = 0.8f), RoundedCornerShape(11.dp))
            .clickable(onClick = onReset)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = CursorTeal, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(stringResource(R.string.caliper_reset), color = CursorTeal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------------------------
// Right-hand panel: probe orientation | schema | atlas
// ---------------------------------------------------------------------------------------------

/** What the right-hand stage panel shows. */
enum class DiagramMode(val labelRes: Int) {
    PROBE(R.string.diagram_probe),
    SCHEMA(R.string.diagram_schema),
    ATLAS(R.string.diagram_atlas)
}

/** Compact segmented pills ("Probe | Schema | Atlas") for the diagram panel. */
@Composable
internal fun DiagramModePills(
    selected: DiagramMode,
    available: List<DiagramMode>,
    onSelect: (DiagramMode) -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        available.forEach { mode ->
            val active = mode == selected
            Text(
                text = stringResource(mode.labelRes),
                color = if (active) EchoBackground else Color.White.copy(alpha = 0.85f),
                fontSize = if (large) 13.sp else 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) EchoTeal else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .semantics { this.selected = active; role = Role.Tab }
                    .padding(horizontal = if (large) 12.dp else 6.dp, vertical = if (large) 5.dp else 2.dp)
            )
        }
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
