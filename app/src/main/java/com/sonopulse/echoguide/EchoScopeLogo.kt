package com.sonopulse.echoguide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val LogoNavy = Color(0xFF16222F)
private val LogoNavyGlow = Color(0xFF24405A)
private val LogoCyan = Color(0xFF29B6F6)
private val LogoEmerald = Color(0xFF00E676)
private val LogoHeart = Color(0xFFF8FAFC)
private val LogoHeartShade = Color(0xFFCFE3F3)
private val LogoProbe = Color(0xFFD5DBE3)
private val LogoProbeShade = Color(0xFF9AA6B4)
private val LogoKeyline = Color(0xFF1D2E40)

/**
 * Vector rendition of the EchoScope launcher icon for the Top App Bar: navy squircle, cyan →
 * emerald ECG behind a white anatomical heart, transducer on the upper right with its cable to the
 * top-right corner, and acoustic waves leaving the transducer face towards the lower left.
 * Drawn on a 100 × 100 grid and scaled, so it is crisp at every size and density.
 */
@Composable
fun EchoScopeLogo(size: Dp = 36.dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Canvas(
        modifier = modifier
            .size(size)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
    ) {
        val s = this.size.minDimension / 100f
        scale(s, s, pivot = Offset.Zero) { drawLogo() }
    }
}

private fun DrawScope.drawLogo() {
    // squircle background with a soft central glow
    drawRoundRect(LogoNavy, size = Size(100f, 100f), cornerRadius = CornerRadius(24f, 24f))
    drawRoundRect(
        Brush.radialGradient(listOf(LogoNavyGlow, LogoNavy), center = Offset(50f, 50f), radius = 52f),
        size = Size(100f, 100f), cornerRadius = CornerRadius(24f, 24f)
    )

    // ECG behind the heart: cyan on the left, emerald on the right, with glow
    val ecg = Path().apply {
        moveTo(5f, 60f); lineTo(12f, 60f); lineTo(15f, 54f); lineTo(18f, 60f); lineTo(24f, 60f)
        lineTo(28f, 32f); lineTo(33f, 80f); lineTo(37f, 60f); lineTo(46f, 60f)
        moveTo(68f, 60f); lineTo(74f, 60f); lineTo(78f, 47f); lineTo(82f, 68f); lineTo(85f, 60f)
        lineTo(88f, 55f); lineTo(91f, 60f); lineTo(96f, 60f)
    }
    val ecgBrush = Brush.horizontalGradient(listOf(LogoCyan, LogoCyan, LogoEmerald, LogoEmerald), startX = 0f, endX = 100f)
    drawPath(ecg, ecgBrush, alpha = 0.28f, style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(ecg, ecgBrush, style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // anatomical heart: ventricles, SVC, aortic arch with three branches, pulmonary trunk
    val heartBrush = Brush.linearGradient(listOf(LogoHeart, LogoHeartShade), start = Offset(30f, 30f), end = Offset(76f, 88f))
    val keyline = Stroke(9.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val vessels = listOf(
        Path().apply { moveTo(39f, 44f); lineTo(39f, 27f) } to 7f,                                        // SVC
        Path().apply { moveTo(49f, 42f); cubicTo(48f, 30f, 53f, 22f, 61f, 22f); cubicTo(67f, 22f, 70f, 27f, 70f, 33f) } to 7.5f, // aorta
        Path().apply { moveTo(59f, 44f); cubicTo(62f, 38f, 67f, 36f, 73f, 37f) } to 6.5f                 // pulmonary trunk
    )
    val branches = listOf(Offset(54f, 24f) to Offset(52f, 15f), Offset(59f, 22f) to Offset(59f, 13f), Offset(64f, 23f) to Offset(66f, 14.5f))
    val body = Path().apply {
        moveTo(38f, 41f)
        cubicTo(29f, 43f, 25f, 53f, 27f, 63f)
        cubicTo(29f, 75f, 41f, 86f, 54f, 88f)
        cubicTo(65f, 89f, 74f, 81f, 75f, 70f)
        cubicTo(76f, 59f, 72f, 48f, 64f, 43f)
        cubicTo(57f, 39f, 46f, 39f, 38f, 41f)
        close()
    }
    // dark keylines first, then the white shapes (gives the icon's outlined look)
    vessels.forEach { (p, w) -> drawPath(p, LogoKeyline, style = Stroke(w + 2.5f, cap = StrokeCap.Round)) }
    branches.forEach { (a, b) -> drawLine(LogoKeyline, a, b, strokeWidth = 5.5f, cap = StrokeCap.Round) }
    drawPath(body, LogoKeyline, style = keyline)
    vessels.forEach { (p, w) -> drawPath(p, heartBrush, style = Stroke(w, cap = StrokeCap.Round)) }
    branches.forEach { (a, b) -> drawLine(LogoHeart, a, b, strokeWidth = 3.2f, cap = StrokeCap.Round) }
    drawPath(body, heartBrush)

    // acoustic waves: from the transducer face (64, 53) forward into the heart, towards the lower left
    val face = Offset(63f, 54f)
    val waveBrush = Brush.linearGradient(listOf(LogoCyan, LogoEmerald), start = Offset(40f, 50f), end = Offset(60f, 90f))
    listOf(8f, 12.5f, 17f).forEachIndexed { i, r ->
        drawArc(waveBrush, startAngle = 100f, sweepAngle = 60f, useCenter = false,
            topLeft = Offset(face.x - r, face.y - r), size = Size(2 * r, 2 * r), alpha = 1f - i * 0.12f,
            style = Stroke(2.2f, cap = StrokeCap.Round))
    }
    listOf(34f, 39f).forEachIndexed { i, r ->
        drawArc(waveBrush, startAngle = 118f, sweepAngle = 34f, useCenter = false,
            topLeft = Offset(face.x - r, face.y - r), size = Size(2 * r, 2 * r), alpha = 0.95f - i * 0.15f,
            style = Stroke(2.4f, cap = StrokeCap.Round))
    }

    // transducer: drawn pointing down, rotated 45° so its face points to the lower left
    rotate(45f, pivot = face) {
        val probe = Path().apply {
            moveTo(face.x - 8.5f, face.y - 1f)
            cubicTo(face.x - 8.5f, face.y - 8f, face.x - 5f, face.y - 11f, face.x - 4.5f, face.y - 17f)
            lineTo(face.x - 3.5f, face.y - 23f)
            lineTo(face.x + 3.5f, face.y - 23f)
            lineTo(face.x + 4.5f, face.y - 17f)
            cubicTo(face.x + 5f, face.y - 11f, face.x + 8.5f, face.y - 8f, face.x + 8.5f, face.y - 1f)
            cubicTo(face.x + 8.5f, face.y + 1.5f, face.x - 8.5f, face.y + 1.5f, face.x - 8.5f, face.y - 1f)
            close()
        }
        drawPath(probe, LogoKeyline, style = Stroke(3.2f, join = StrokeJoin.Round))
        drawPath(probe, Brush.horizontalGradient(listOf(LogoProbe, Color.White, LogoProbeShade), startX = face.x - 8.5f, endX = face.x + 8.5f))
    }
    // cable from the transducer handle to the top-right corner
    val cable = Path().apply { moveTo(77.5f, 39.5f); cubicTo(84f, 33f, 90f, 22f, 97f, 9f) }
    drawPath(cable, LogoKeyline, style = Stroke(4.6f, cap = StrokeCap.Round))
    drawPath(cable, LogoProbe, style = Stroke(2.6f, cap = StrokeCap.Round))
}
