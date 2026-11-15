package com.sonopulse.echoguide

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Schematic Doppler / M-mode trace that shows HOW a valve measurement is taken: the envelope
 * shape, where Vmax / E / A sit, the VTI tracing, the PHT and DT slopes, AcT, TAPSE excursion,
 * the PISA radius and the vena contracta. It is a drawn teaching diagram (labelled as such), not
 * a recording; real recordings are shown next to it where they exist.
 */
@Composable
fun SpectralGuide(type: String, modifier: Modifier = Modifier) {
    val text = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        drawRect(EchoPalette.EcgPaper)
        when (type) {
            "AV_CW" -> systolicDown(text, peak = 4.2f, scale = 5f, width = 0.30f, peakAt = 0.38f, vmax = "Vmax", trace = "VTI · mean PG")
            "LVOT_PW" -> systolicDown(text, peak = 1.0f, scale = 2f, width = 0.28f, peakAt = 0.30f, vmax = "V LVOT", trace = "LVOT VTI",
                hollow = true)
            "TR_CW" -> systolicDown(text, peak = 2.6f, scale = 4f, width = 0.34f, peakAt = 0.50f, vmax = "TR Vmax",
                trace = "sPAP = 4·Vmax² + RAP", traceDashed = false)
            "PV_PW" -> pulmonary(text)
            "AR_PHT" -> arPht(text)
            "MV_PW" -> mitralInflow(text, e = 0.8f, a = 0.5f, scale = 1.5f, stenotic = false)
            "MS_CW" -> mitralInflow(text, e = 2.0f, a = 1.8f, scale = 3f, stenotic = true)
            "TAPSE" -> tapse(text)
            "PISA" -> pisa(text)
            "VC" -> venaContracta(text)
            "CONTINUITY" -> continuity(text)
        }
        label(text, "Schema", Offset(size.width - 6f, 4f), EchoPalette.TextMuted, 9f, alignEnd = true)
    }
}

// ---------------------------------------------------------------------------------------------

private val Trace = EchoPalette.Text
private val Measure = EchoPalette.Caliper
private val Axis = EchoPalette.TextMuted

private fun DrawScope.label(text: TextMeasurer, s: String, at: Offset, color: Color, sp: Float = 11f, alignEnd: Boolean = false) {
    val layout = text.measure(s, TextStyle(color = color, fontSize = sp.sp, fontWeight = FontWeight.Bold))
    val x = if (alignEnd) at.x - layout.size.width else at.x
    drawText(layout, topLeft = Offset(x.coerceIn(0f, max(0f, size.width - layout.size.width)), at.y))
}

private fun DrawScope.baseline(y: Float, scale: Float, down: Boolean, text: TextMeasurer) {
    drawLine(Axis, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
    // velocity scale on the right (m/s)
    val sign = if (down) 1 else -1
    val usable = if (down) size.height - y - 8f else y - 8f
    for (i in 1..scale.toInt()) {
        val yy = y + sign * usable * i / scale
        drawLine(Axis.copy(alpha = 0.5f), Offset(size.width - 10f, yy), Offset(size.width, yy), strokeWidth = 1f)
        label(text, if (down) "−$i" else "$i", Offset(size.width - 12f, yy - 7f), Axis, 8f, alignEnd = true)
    }
}

/** Filled spectral envelope from (x0..x1) with velocity profile v(u), u in 0..1. */
private fun DrawScope.envelope(x0: Float, x1: Float, y0: Float, pxPerUnit: Float, down: Boolean, hollow: Boolean, v: (Float) -> Float): Path {
    val sign = if (down) 1 else -1
    val outline = Path()
    outline.moveTo(x0, y0)
    val n = 40
    for (k in 0..n) {
        val u = k / n.toFloat()
        outline.lineTo(x0 + (x1 - x0) * u, y0 + sign * v(u) * pxPerUnit)
    }
    outline.lineTo(x1, y0)
    if (hollow) {
        drawPath(outline, Trace.copy(alpha = 0.25f))
        drawPath(outline, Trace.copy(alpha = 0.85f), style = Stroke(2f))
    } else {
        drawPath(outline, Trace.copy(alpha = 0.55f))
    }
    return outline
}

private fun DrawScope.systolicDown(
    text: TextMeasurer, peak: Float, scale: Float, width: Float, peakAt: Float, vmax: String, trace: String,
    hollow: Boolean = false, traceDashed: Boolean = true
) {
    val y0 = size.height * 0.16f
    baseline(y0, scale, down = true, text)
    val px = (size.height - y0 - 8f) / scale
    fun prof(u: Float): Float {
        val shape = if (u < peakAt) sin((u / peakAt) * PI / 2).toFloat() else sin(((1 - u) / (1 - peakAt)) * PI / 2).toFloat()
        return peak * shape.pow(0.8f)
    }
    listOf(0.10f, 0.58f).forEachIndexed { i, start ->
        val x0 = size.width * start
        val x1 = x0 + size.width * width
        val path = envelope(x0, x1, y0, px, down = true, hollow = hollow, v = ::prof)
        if (i == 0) {
            drawPath(path, Measure, style = Stroke(2.5f, pathEffect = if (traceDashed) PathEffect.dashPathEffect(floatArrayOf(8f, 5f)) else null))
            val pk = Offset(x0 + (x1 - x0) * peakAt, y0 + peak * px)
            drawCircle(Measure, 6f, pk)
            label(text, "$vmax ${"%.1f".format(peak)} m/s", Offset(pk.x + 8f, pk.y - 6f), Measure)
            label(text, trace, Offset(x0, y0 - 18f), Measure, 10f)
        }
    }
}

private fun DrawScope.pulmonary(text: TextMeasurer) {
    val y0 = size.height * 0.16f
    baseline(y0, 1.5f, down = true, text)
    val px = (size.height - y0 - 8f) / 1.5f
    val peakAt = 0.42f
    fun prof(u: Float) = 0.8f * (if (u < peakAt) sin((u / peakAt) * PI / 2) else sin(((1 - u) / (1 - peakAt)) * PI / 2)).toFloat()
    listOf(0.08f, 0.56f).forEachIndexed { i, start ->
        val x0 = size.width * start
        val x1 = x0 + size.width * 0.32f
        envelope(x0, x1, y0, px, down = true, hollow = true, v = ::prof)
        if (i == 0) {
            val xp = x0 + (x1 - x0) * peakAt
            val yp = y0 + 0.8f * px
            drawCircle(Measure, 6f, Offset(xp, yp))
            label(text, "Vmax", Offset(xp + 8f, yp - 4f), Measure)
            // AcT: onset → peak
            val yb = yp + 14f
            drawLine(Measure, Offset(x0, yb), Offset(xp, yb), strokeWidth = 2.5f)
            drawLine(Measure, Offset(x0, yb - 6f), Offset(x0, yb + 6f), strokeWidth = 2.5f)
            drawLine(Measure, Offset(xp, yb - 6f), Offset(xp, yb + 6f), strokeWidth = 2.5f)
            label(text, "AcT", Offset(x0, yb + 6f), Measure)
        }
    }
}

private fun DrawScope.arPht(text: TextMeasurer) {
    val y0 = size.height * 0.84f
    baseline(y0, 5f, down = false, text)
    val px = (y0 - 8f) / 5f
    listOf(0.06f, 0.54f).forEachIndexed { i, start ->
        val x0 = size.width * start
        val x1 = x0 + size.width * 0.36f
        // Rapid rise to 4 m/s, then linear decay to 2.6 m/s at end-diastole.
        envelope(x0, x1, y0, px, down = false, hollow = false) { u -> if (u < 0.08f) 4f * u / 0.08f else 4f - 1.4f * (u - 0.08f) / 0.92f }
        if (i == 0) {
            val a = Offset(x0 + (x1 - x0) * 0.08f, y0 - 4f * px)
            val b = Offset(x1, y0 - 2.6f * px)
            drawLine(Measure, a, b, strokeWidth = 3f, cap = StrokeCap.Round)
            drawCircle(Measure, 5f, a)
            label(text, "PHT (slope)", Offset(a.x + 6f, a.y - 18f), Measure)
        }
    }
}

private fun DrawScope.mitralInflow(text: TextMeasurer, e: Float, a: Float, scale: Float, stenotic: Boolean) {
    val y0 = size.height * 0.84f
    baseline(y0, scale, down = false, text)
    val px = (y0 - 8f) / scale
    listOf(0.04f, 0.53f).forEachIndexed { i, start ->
        val x0 = size.width * start
        val w = size.width * 0.42f
        // E wave
        val eEnd = if (stenotic) 0.62f else 0.40f
        fun eProf(u: Float): Float = if (u < 0.12f) e * u / 0.12f else
            if (stenotic) e - (e - 1.3f) * (u - 0.12f) / (eEnd - 0.12f) else e * (1 - (u - 0.12f) / (eEnd - 0.12f)).coerceAtLeast(0f)
        envelope(x0, x0 + w * eEnd, y0, px, down = false, hollow = true) { u -> eProf(u * eEnd).coerceAtLeast(0f) }
        // A wave
        val aStart = x0 + w * (if (stenotic) 0.62f else 0.62f)
        envelope(aStart, x0 + w, y0, px, down = false, hollow = true) { u -> a * sin(u * PI).toFloat() }
        if (i == 0) {
            val ep = Offset(x0 + w * 0.12f, y0 - e * px)
            drawCircle(Measure, 6f, ep)
            label(text, "E", Offset(ep.x - 4f, ep.y - 20f), Measure, 13f)
            val ap = Offset(aStart + (x0 + w - aStart) / 2, y0 - a * px)
            drawCircle(Measure, 6f, ap)
            label(text, "A", Offset(ap.x - 4f, ap.y - 20f), Measure, 13f)
            val end = if (stenotic) Offset(x0 + w * eEnd, y0 - 1.3f * px) else Offset(x0 + w * eEnd, y0)
            drawLine(Measure, ep, end, strokeWidth = 3f, cap = StrokeCap.Round)
            label(text, if (stenotic) "PHT" else "DT", Offset((ep.x + end.x) / 2 + 6f, (ep.y + end.y) / 2 - 4f), Measure)
            if (stenotic) {
                val p = Path().apply {
                    moveTo(x0, y0)
                    for (k in 0..30) { val u = k / 30f * eEnd; lineTo(x0 + w * u, y0 - eProf(u) * px) }
                    lineTo(x0 + w * eEnd, y0)
                }
                drawPath(p, Measure, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))))
                label(text, "mean PG (trace)", Offset(x0, 4f), Measure, 10f)
            }
        }
    }
}

private fun DrawScope.tapse(text: TextMeasurer) {
    // M-mode: depth down, time right; the bright annulus line moves toward the apex in systole.
    for (k in 0 until 26) {
        val y = size.height * k / 26f
        drawLine(Axis.copy(alpha = 0.10f + 0.10f * ((k * 7) % 3)), Offset(0f, y), Offset(size.width, y), strokeWidth = 3f)
    }
    val base = size.height * 0.70f
    val amp = size.height * 0.34f
    val p = Path()
    var trough = Offset.Zero
    var peak = Offset.Zero
    for (k in 0..120) {
        val x = size.width * k / 120f
        val phase = (k / 60f) % 1f
        val lift = if (phase < 0.35f) sin(phase / 0.35f * PI / 2).toFloat() else (1 - (phase - 0.35f) / 0.65f).coerceAtLeast(0f).pow(1.5f)
        val y = base - amp * lift
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        if (k == 60) trough = Offset(x, base)
        if (k == 81) peak = Offset(x, y)
    }
    drawPath(p, Trace, style = Stroke(6f))
    val x = peak.x
    drawLine(Measure, Offset(x, trough.y), Offset(x, peak.y), strokeWidth = 3f)
    drawLine(Measure, Offset(trough.x, trough.y), Offset(x + 12f, trough.y), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
    listOf(trough.y, peak.y).forEach { drawLine(Measure, Offset(x - 8f, it), Offset(x + 8f, it), strokeWidth = 3f) }
    label(text, "TAPSE (mm)", Offset(x + 12f, (trough.y + peak.y) / 2 - 8f), Measure)
}

private fun DrawScope.pisa(text: TextMeasurer) {
    val cx = size.width * 0.45f
    val vy = size.height * 0.55f
    drawLine(EchoPalette.Text, Offset(cx - size.width * 0.35f, vy), Offset(cx - 8f, vy), strokeWidth = 5f, cap = StrokeCap.Round)
    drawLine(EchoPalette.Text, Offset(cx + 8f, vy), Offset(cx + size.width * 0.35f, vy), strokeWidth = 5f, cap = StrokeCap.Round)
    // flow convergence shells on the LV side (top), aliasing border = PISA
    listOf(0.30f, 0.22f).forEachIndexed { i, rf ->
        val r = size.height * rf
        drawArc(if (i == 0) EchoPalette.Crimson.copy(alpha = 0.45f) else EchoPalette.MedicalBlue.copy(alpha = 0.7f),
            180f, 180f, useCenter = true, topLeft = Offset(cx - r, vy - r), size = Size(2 * r, 2 * r))
    }
    val r = size.height * 0.22f
    drawArc(Measure, 180f, 180f, useCenter = false, topLeft = Offset(cx - r, vy - r), size = Size(2 * r, 2 * r), style = Stroke(2f))
    drawLine(Measure, Offset(cx, vy), Offset(cx, vy - r), strokeWidth = 3f)
    label(text, "r", Offset(cx + 6f, vy - r / 2 - 8f), Measure, 13f)
    // jet into the LA (bottom)
    val jet = Path().apply { moveTo(cx - 6f, vy); lineTo(cx - size.width * 0.12f, size.height); lineTo(cx + size.width * 0.12f, size.height); lineTo(cx + 6f, vy); close() }
    drawPath(jet, EchoPalette.MedicalBlue.copy(alpha = 0.6f))
    label(text, "EROA = 2πr² · Va / Vmax", Offset(6f, 4f), Measure, 11f)
    label(text, "LV", Offset(6f, vy - 30f), Axis)
    label(text, "LA", Offset(6f, vy + 10f), Axis)
}

private fun DrawScope.venaContracta(text: TextMeasurer) {
    val vy = size.height * 0.32f
    val cx = size.width * 0.5f
    val neck = size.width * 0.05f
    drawLine(EchoPalette.Text, Offset(cx - size.width * 0.4f, vy), Offset(cx - neck - 4f, vy), strokeWidth = 5f, cap = StrokeCap.Round)
    drawLine(EchoPalette.Text, Offset(cx + neck + 4f, vy), Offset(cx + size.width * 0.4f, vy), strokeWidth = 5f, cap = StrokeCap.Round)
    val conv = Path().apply {
        moveTo(cx - size.width * 0.18f, 0f); lineTo(cx - neck, vy); lineTo(cx + neck, vy); lineTo(cx + size.width * 0.18f, 0f); close()
    }
    drawPath(conv, EchoPalette.Crimson.copy(alpha = 0.35f))
    val jet = Path().apply {
        moveTo(cx - neck, vy); cubicTo(cx - neck, vy + 20f, cx - size.width * 0.2f, size.height * 0.75f, cx - size.width * 0.16f, size.height)
        lineTo(cx + size.width * 0.16f, size.height); cubicTo(cx + size.width * 0.2f, size.height * 0.75f, cx + neck, vy + 20f, cx + neck, vy); close()
    }
    drawPath(jet, EchoPalette.MedicalBlue.copy(alpha = 0.65f))
    val y = vy + 10f
    drawLine(Measure, Offset(cx - neck, y), Offset(cx + neck, y), strokeWidth = 3f)
    listOf(cx - neck, cx + neck).forEach { drawLine(Measure, Offset(it, y - 7f), Offset(it, y + 7f), strokeWidth = 3f) }
    label(text, "VC = narrowest neck", Offset(cx + neck + 10f, y - 8f), Measure)
}

private fun DrawScope.continuity(text: TextMeasurer) {
    val top = size.height * 0.25f
    val bottom = size.height * 0.75f
    val xValve = size.width * 0.62f
    // LVOT tube → aortic valve → aorta
    drawLine(EchoPalette.Text, Offset(size.width * 0.05f, top), Offset(xValve, top), strokeWidth = 4f)
    drawLine(EchoPalette.Text, Offset(size.width * 0.05f, bottom), Offset(xValve, bottom), strokeWidth = 4f)
    drawLine(EchoPalette.Text, Offset(xValve, top), Offset(xValve + 20f, size.height * 0.45f), strokeWidth = 4f)
    drawLine(EchoPalette.Text, Offset(xValve, bottom), Offset(xValve + 20f, size.height * 0.55f), strokeWidth = 4f)
    drawLine(EchoPalette.Text, Offset(xValve + 20f, size.height * 0.45f), Offset(size.width * 0.95f, size.height * 0.3f), strokeWidth = 3f)
    drawLine(EchoPalette.Text, Offset(xValve + 20f, size.height * 0.55f), Offset(size.width * 0.95f, size.height * 0.7f), strokeWidth = 3f)
    val xd = xValve - size.width * 0.08f
    drawLine(Measure, Offset(xd, top), Offset(xd, bottom), strokeWidth = 3f)
    listOf(top, bottom).forEach { drawLine(Measure, Offset(xd - 8f, it), Offset(xd + 8f, it), strokeWidth = 3f) }
    label(text, "d", Offset(xd - 18f, size.height * 0.45f), Measure, 13f)
    label(text, "VTI LVOT →", Offset(size.width * 0.10f, size.height * 0.45f), EchoPalette.Text)
    label(text, "VTI AV", Offset(xValve + 26f, size.height * 0.44f), EchoPalette.Text)
    label(text, "AVA = π(d/2)² · VTI LVOT / VTI AV", Offset(6f, 4f), Measure, 11f)
}

/** Colour scale of a colour Doppler loop: red (toward the probe) on top, blue below, ± Nyquist. */
@Composable
fun NyquistBar(nyquistCmS: Int, modifier: Modifier = Modifier) {
    val text = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val barW = 8f
        val x = size.width - barW
        val top = 14f
        val bottom = size.height - 14f
        val mid = (top + bottom) / 2
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(EchoPalette.Caliper, EchoPalette.Crimson, Color.Transparent), startY = top, endY = mid),
            topLeft = Offset(x, top), size = Size(barW, mid - top))
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color.Transparent, EchoPalette.MedicalBlue, EchoPalette.Sky), startY = mid, endY = bottom),
            topLeft = Offset(x, mid), size = Size(barW, bottom - mid))
        label(text, "+$nyquistCmS", Offset(size.width, 0f), EchoPalette.Text, 9f, alignEnd = true)
        label(text, "−$nyquistCmS", Offset(size.width, bottom), EchoPalette.Text, 9f, alignEnd = true)
        label(text, "cm/s", Offset(x - 2f, mid - 6f), EchoPalette.TextMuted, 8f, alignEnd = true)
    }
}
