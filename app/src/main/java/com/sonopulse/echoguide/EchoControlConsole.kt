package com.sonopulse.echoguide

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val ConsoleTeal = Color(0xFF00D2B8)
private val ConsoleOrange = Color(0xFFFF8A3D)
private val PanelTop = Color(0xFF1A2230)
private val PanelBottom = Color(0xFF0C1119)
private val KeyFace = Color(0xFF222C3B)
private val KeyEdge = Color(0xFF334155)
private val KeyLabel = Color(0xFFCBD5E1)

private val KNOBS = listOf("GAIN", "DEPTH", "FOCUS", "SCALE")
private val KEY_ROWS = listOf(
    listOf("2D", "M-MODE", "COLOR", "PW"),
    listOf("CW", "TDI", "ZOOM", "TRACKBALL"),
    listOf("FREEZE", "CALIPER", "STORE")
)

/** Visual state of a console control for the current view's sequence. */
private enum class ControlState { IDLE, UPCOMING, CURRENT, DONE }

/**
 * Interactive ultrasound console (GE Vivid / Philips EPIQ style), sized to fill the cockpit's
 * lower deck without scrolling. The view's `machineControls` light up in order: the current step
 * glows orange, upcoming steps teal with their step badges. Tapping a key or knob gives haptic
 * feedback and shows a guidance banner (function + this view's target); "Next step" walks on.
 * Knobs also turn with a sideways drag.
 */
@Composable
fun EchoControlConsole(
    view: EchoView,
    controls: Map<String, LocalizedText>,
    language: AppLanguage,
    modifier: Modifier = Modifier
) {
    val steps = view.machineControls
    var currentStep by rememberSaveable(view.id) { mutableIntStateOf(0) }
    var selected by rememberSaveable(view.id) { mutableStateOf<String?>(null) }
    val knobValues = remember(view.id) { mutableStateMapOf<String, Float>() }
    val hostView = LocalView.current

    fun stepsFor(control: String) = steps.indices.filter { steps[it].control == control }
    fun stateOf(control: String): ControlState {
        val indices = stepsFor(control)
        return when {
            indices.isEmpty() -> ControlState.IDLE
            steps.getOrNull(currentStep)?.control == control -> ControlState.CURRENT
            indices.any { it > currentStep } -> ControlState.UPCOMING
            else -> ControlState.DONE
        }
    }
    fun press(control: String) {
        hostView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        selected = control
        stepsFor(control).firstOrNull { it >= currentStep }?.let { currentStep = it }
    }
    fun advance() {
        if (steps.isEmpty()) return
        currentStep = if (currentStep < steps.lastIndex) currentStep + 1 else 0
        selected = steps[currentStep].control
        hostView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    val shown = selected ?: steps.getOrNull(currentStep)?.control

    // The console keeps its hardware layout in every language; on short decks knobs and keys shrink
    // so everything still fits without scrolling.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val compact = maxHeight < 250.dp
            val knobSize = if (compact) 34.dp else 44.dp
            val keyHeight = if (compact) 27.dp else 34.dp
            val gap = if (compact) 4.dp else 6.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(PanelTop, PanelBottom)))
                    .border(1.dp, KeyEdge, RoundedCornerShape(14.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    KNOBS.forEach { knob ->
                        RotaryKnob(
                            knobSize = knobSize,
                            label = knob,
                            value = knobValues[knob] ?: 0.5f,
                            state = stateOf(knob),
                            stepNumbers = stepsFor(knob).map { it + 1 },
                            onValueChange = { v ->
                                val old = knobValues[knob] ?: 0.5f
                                if ((old * 20).toInt() != (v * 20).toInt()) {
                                    hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                knobValues[knob] = v
                            },
                            onTap = { press(knob) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                KEY_ROWS.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { key ->
                            ConsoleKey(key, stateOf(key), stepsFor(key).map { it + 1 }, { press(key) }, Modifier.weight(1f), keyHeight)
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                GuidanceBanner(
                    control = shown,
                    stepIndex = currentStep,
                    stepCount = steps.size,
                    function = shown?.let { controls[it]?.get(language) }.orEmpty(),
                    viewInstructions = shown?.let { c -> stepsFor(c).map { it + 1 to steps[it].instruction.get(language) } }.orEmpty(),
                    isLast = currentStep >= steps.lastIndex,
                    onNext = { advance() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

private fun stateColor(state: ControlState): Color = when (state) {
    ControlState.CURRENT -> ConsoleOrange
    ControlState.UPCOMING -> ConsoleTeal
    ControlState.DONE -> ConsoleTeal.copy(alpha = 0.45f)
    ControlState.IDLE -> KeyEdge
}

@Composable
private fun StepBadge(number: Int, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(15.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(number.toString(), color = PanelBottom, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StepBadges(numbers: List<Int>, state: ControlState, modifier: Modifier = Modifier) {
    if (numbers.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        numbers.take(3).forEach { StepBadge(it, stateColor(state)) }
    }
}

@Composable
private fun ConsoleKey(
    label: String,
    state: ControlState,
    stepNumbers: List<Int>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp
) {
    val glow = stateColor(state)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(top = 4.dp, end = 3.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (state == ControlState.CURRENT) {
                        Brush.verticalGradient(listOf(ConsoleOrange.copy(alpha = 0.6f), ConsoleOrange.copy(alpha = 0.25f)))
                    } else {
                        Brush.verticalGradient(listOf(KeyFace, Color(0xFF161E2A)))
                    }
                )
                .border(if (state == ControlState.IDLE) 1.dp else 1.5.dp, glow, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = when (state) {
                    ControlState.CURRENT -> Color.White
                    ControlState.IDLE -> KeyLabel
                    else -> glow
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        StepBadges(stepNumbers, state, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun RotaryKnob(
    knobSize: Dp,
    label: String,
    value: Float,
    state: ControlState,
    stepNumbers: List<Int>,
    onValueChange: (Float) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ring = stateColor(state)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnTap by rememberUpdatedState(onTap)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Canvas(
                modifier = Modifier
                    .size(knobSize)
                    .pointerInput(label) { detectTapGestures(onTap = { currentOnTap() }) }
                    .pointerInput(label) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            currentOnValueChange((currentValue + dragAmount / 300f).coerceIn(0f, 1f))
                        }
                    }
            ) {
                val r = size.minDimension / 2f
                val c = center
                val inset = 2.5.dp.toPx()
                val arcSize = Size((r - inset) * 2, (r - inset) * 2)
                val arcTopLeft = Offset(c.x - r + inset, c.y - r + inset)
                drawArc(KeyEdge, 135f, 270f, useCenter = false, topLeft = arcTopLeft, size = arcSize,
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawArc(if (state == ControlState.IDLE) ConsoleTeal.copy(alpha = 0.6f) else ring, 135f, 270f * value,
                    useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val body = r - 7.dp.toPx()
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF3A4658), Color(0xFF151C27)), center = c - Offset(body / 3, body / 3), radius = body * 1.6f),
                    body, c
                )
                drawCircle(if (state == ControlState.IDLE) KeyEdge else ring, body, c,
                    style = Stroke(if (state == ControlState.CURRENT) 2.5.dp.toPx() else 1.2.dp.toPx()))
                for (i in 0 until 12) {
                    val a = Math.toRadians(i * 30.0)
                    drawLine(
                        Color(0xFF0E141D),
                        Offset(c.x + (body - 3.dp.toPx()) * cos(a).toFloat(), c.y + (body - 3.dp.toPx()) * sin(a).toFloat()),
                        Offset(c.x + body * cos(a).toFloat(), c.y + body * sin(a).toFloat()),
                        1.2.dp.toPx()
                    )
                }
                val angle = Math.toRadians(135.0 + 270.0 * value)
                drawLine(
                    Color.White, c,
                    Offset(c.x + (body - 4.dp.toPx()) * cos(angle).toFloat(), c.y + (body - 4.dp.toPx()) * sin(angle).toFloat()),
                    2.dp.toPx(), cap = StrokeCap.Round
                )
            }
            StepBadges(stepNumbers, state, Modifier.align(Alignment.TopEnd).offset(x = 5.dp))
        }
        Text(label, color = if (state == ControlState.IDLE) KeyLabel else ring, fontSize = 9.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/** Guidance for the tapped (or current) control: step, this view's target and the key's function. */
@Composable
private fun GuidanceBanner(
    control: String?,
    stepIndex: Int,
    stepCount: Int,
    function: String,
    viewInstructions: List<Pair<Int, String>>,
    isLast: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (viewInstructions.isEmpty()) KeyLabel else ConsoleOrange
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0A0F16))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = control ?: "—",
                color = accent,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            if (stepCount > 0) {
                Text(
                    text = stringResource(R.string.console_step_of, stepIndex + 1, stepCount),
                    color = KeyLabel.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(ConsoleTeal)
                        .clickable(onClick = onNext)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = stringResource(if (isLast) R.string.console_restart else R.string.console_next),
                        color = PanelBottom,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        if (viewInstructions.isEmpty()) {
            Text(stringResource(R.string.console_not_used), color = KeyLabel.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            viewInstructions.take(2).forEach { (n, text) ->
                Text("$n. $text", color = Color.White, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(function, color = KeyLabel.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
