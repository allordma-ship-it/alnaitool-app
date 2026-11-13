package com.sonopulse.echoguide

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
private val MODE_KEYS = listOf("2D", "M-MODE", "COLOR", "PW", "CW", "TDI", "ZOOM")
private val CAPTURE_KEYS = listOf("FREEZE", "CALIPER", "STORE")

/** Visual state of a console control for the current view's sequence. */
private enum class ControlState { IDLE, UPCOMING, CURRENT, DONE }

/**
 * Interactive ultrasound console (GE Vivid / Philips EPIQ style). The view's `machineControls`
 * are highlighted in order: the current step glows orange, upcoming steps teal with their step
 * numbers. Tapping any key or knob gives haptic feedback and opens a guide card with the
 * control's function and its target setting for this view; "Next step" walks the sequence.
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
    }
    fun advance() {
        if (steps.isEmpty()) return
        currentStep = if (currentStep < steps.lastIndex) currentStep + 1 else 0
        selected = steps[currentStep].control
        hostView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header: progress through the sequence
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.console_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (steps.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.console_step_of, currentStep + 1, steps.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = ConsoleOrange,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (steps.isNotEmpty()) {
            Text(
                text = "${steps[currentStep].control} · ${steps[currentStep].instruction.get(language)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The console itself keeps the hardware layout in every language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.verticalGradient(listOf(PanelTop, PanelBottom)))
                    .border(1.dp, KeyEdge, RoundedCornerShape(18.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    KNOBS.forEach { knob ->
                        RotaryKnob(
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
                MODE_KEYS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { key ->
                            ConsoleKey(key, stateOf(key), stepsFor(key).map { it + 1 }, { press(key) }, Modifier.weight(1f))
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Trackball(
                        state = stateOf("TRACKBALL"),
                        stepNumbers = stepsFor("TRACKBALL").map { it + 1 },
                        onTap = { press("TRACKBALL") },
                        modifier = Modifier.weight(1.2f)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(2f)) {
                        CAPTURE_KEYS.forEach { key ->
                            ConsoleKey(key, stateOf(key), stepsFor(key).map { it + 1 }, { press(key) }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        // Console Key Guide Card
        AnimatedVisibility(visible = selected != null) {
            val control = selected ?: return@AnimatedVisibility
            KeyGuideCard(
                control = control,
                function = controls[control]?.get(language).orEmpty(),
                viewSteps = stepsFor(control).map { it + 1 to steps[it].instruction.get(language) },
                isLast = currentStep >= steps.lastIndex,
                hasSteps = steps.isNotEmpty(),
                onNext = { advance() },
                onClose = { selected = null }
            )
        }

        // Full sequence for this view
        if (steps.isNotEmpty()) {
            Text(
                text = stringResource(R.string.console_sequence),
                style = MaterialTheme.typography.labelLarge,
                color = ConsoleTeal
            )
            steps.forEachIndexed { index, step ->
                val active = index == currentStep
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) ConsoleOrange.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable {
                            currentStep = index
                            press(step.control)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    StepBadge(index + 1, if (active) ConsoleOrange else if (index < currentStep) ConsoleTeal.copy(alpha = 0.5f) else ConsoleTeal)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(step.control, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, color = if (active) ConsoleOrange else ConsoleTeal)
                        Text(step.instruction.get(language), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.console_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            .size(20.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(number.toString(), color = PanelBottom, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StepBadges(numbers: List<Int>, state: ControlState, modifier: Modifier = Modifier) {
    if (numbers.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        numbers.take(3).forEach { StepBadge(it, stateColor(state)) }
    }
}

@Composable
private fun ConsoleKey(
    label: String,
    state: ControlState,
    stepNumbers: List<Int>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glow = stateColor(state)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(top = 6.dp, end = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (state == ControlState.CURRENT) Brush.verticalGradient(listOf(ConsoleOrange.copy(alpha = 0.55f), ConsoleOrange.copy(alpha = 0.25f)))
                    else Brush.verticalGradient(listOf(KeyFace, Color(0xFF161E2A)))
                )
                .border(if (state == ControlState.IDLE) 1.dp else 2.dp, glow, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (state == ControlState.CURRENT) Color.White else if (state == ControlState.IDLE) KeyLabel else glow,
                fontSize = 11.sp,
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
                    .size(64.dp)
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
                // value arc (−135° … +135°)
                val sweepStart = 135f
                drawArc(KeyEdge, sweepStart, 270f, useCenter = false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(c.x - r + 3.dp.toPx(), c.y - r + 3.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size((r - 3.dp.toPx()) * 2, (r - 3.dp.toPx()) * 2))
                drawArc(if (state == ControlState.IDLE) ConsoleTeal.copy(alpha = 0.6f) else ring, sweepStart, 270f * value,
                    useCenter = false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(c.x - r + 3.dp.toPx(), c.y - r + 3.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size((r - 3.dp.toPx()) * 2, (r - 3.dp.toPx()) * 2))
                // knob body
                val body = r - 10.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(Color(0xFF3A4658), Color(0xFF151C27)), center = c - Offset(body / 3, body / 3), radius = body * 1.6f), body, c)
                drawCircle(if (state == ControlState.IDLE) KeyEdge else ring, body, c, style = Stroke(if (state == ControlState.CURRENT) 3.dp.toPx() else 1.5.dp.toPx()))
                // grip ridges and pointer
                for (i in 0 until 12) {
                    val a = Math.toRadians(i * 30.0)
                    drawLine(Color(0xFF0E141D), Offset(c.x + (body - 4.dp.toPx()) * cos(a).toFloat(), c.y + (body - 4.dp.toPx()) * sin(a).toFloat()),
                        Offset(c.x + body * cos(a).toFloat(), c.y + body * sin(a).toFloat()), 1.5.dp.toPx())
                }
                val angle = Math.toRadians((135.0 + 270.0 * value))
                drawLine(Color.White, c, Offset(c.x + (body - 6.dp.toPx()) * cos(angle).toFloat(), c.y + (body - 6.dp.toPx()) * sin(angle).toFloat()),
                    2.5.dp.toPx(), cap = StrokeCap.Round)
            }
            StepBadges(stepNumbers, state, Modifier.align(Alignment.TopEnd).offset(x = 6.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (state == ControlState.IDLE) KeyLabel else ring, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text("${(value * 100).toInt()}%", color = KeyLabel.copy(alpha = 0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Trackball(
    state: ControlState,
    stepNumbers: List<Int>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ring = stateColor(state)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Canvas(
                modifier = Modifier
                    .size(96.dp)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .clickable(onClick = onTap)
            ) {
                val r = size.minDimension / 2f
                drawCircle(Color(0xFF0B1018), r, center)
                drawCircle(if (state == ControlState.IDLE) KeyEdge else ring, r - 2.dp.toPx(), center, style = Stroke(if (state == ControlState.CURRENT) 3.dp.toPx() else 2.dp.toPx()))
                val ball = r - 10.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(Color(0xFF8FA3BF), Color(0xFF3B4A60), Color(0xFF1A2230)),
                    center = center - Offset(ball / 3, ball / 3), radius = ball * 1.4f), ball, center)
                drawCircle(Color.White.copy(alpha = 0.35f), ball / 5, center - Offset(ball / 2.6f, ball / 2.6f))
            }
            StepBadges(stepNumbers, state, Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(4.dp))
        Text("TRACKBALL", color = if (state == ControlState.IDLE) KeyLabel else ring, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun KeyGuideCard(
    control: String,
    function: String,
    viewSteps: List<Pair<Int, String>>,
    isLast: Boolean,
    hasSteps: Boolean,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val accent = if (viewSteps.isEmpty()) KeyLabel else ConsoleOrange
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.5.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(control, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            viewSteps.forEach { (n, _) -> StepBadge(n, ConsoleOrange, Modifier.padding(start = 4.dp)) }
        }
        Text(stringResource(R.string.console_function), style = MaterialTheme.typography.labelMedium, color = ConsoleTeal)
        Text(function, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.console_for_view), style = MaterialTheme.typography.labelMedium, color = ConsoleTeal)
        if (viewSteps.isEmpty()) {
            Text(stringResource(R.string.console_not_used), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            viewSteps.forEach { (n, text) ->
                Text("$n. $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.console_close))
            }
            if (hasSteps) {
                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ConsoleTeal, contentColor = PanelBottom)
                ) {
                    Text(stringResource(if (isLast) R.string.console_restart else R.string.console_next), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
