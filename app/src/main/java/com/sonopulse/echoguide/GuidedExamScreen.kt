package com.sonopulse.echoguide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlin.math.sqrt

/** Measurement-caliper colour drawn over the anatomy diagram. */
private val CaliperColor = Color(0xFFFF4D6D)

/** Diagram coordinate space shared by all bundled SVGs. */
private const val SVG_WIDTH = 400f
private const val SVG_HEIGHT = 300f

/** A flattened position in the exam: which view (1-based number) and which step inside it. */
private data class GuidePosition(
    val window: AcousticWindow,
    val view: EchoView,
    val viewNumber: Int,
    val stepIndex: Int
)

/**
 * Guided exam: walks through every view of the protocol in order, one step at a time.
 * Next / Previous move through all steps, the protocol list jumps straight to a view,
 * NOTE opens practical tips, and measurement steps draw a caliper over the diagram.
 */
@Composable
fun GuidedExamScreen(language: AppLanguage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState<ProtocolState>(ProtocolState.Loading) {
        value = EchoRepository.load(context)
    }

    when (val s = state) {
        ProtocolState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }

        is ProtocolState.Error -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.protocol_error, s.message), color = MaterialTheme.colorScheme.error)
        }

        is ProtocolState.Ready -> GuidedExamContent(s.protocol, language, modifier)
    }
}

@Composable
private fun GuidedExamContent(protocol: EchoProtocol, language: AppLanguage, modifier: Modifier) {
    val positions = remember(protocol) {
        var viewNumber = 0
        protocol.windows.flatMap { window ->
            window.views.flatMap { view ->
                viewNumber++
                val number = viewNumber
                view.steps.indices.map { GuidePosition(window, view, number, it) }
            }
        }
    }

    if (positions.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.guided_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var index by rememberSaveable { mutableIntStateOf(0) }
    var showNote by remember { mutableStateOf(false) }
    var showProtocolList by remember { mutableStateOf(false) }

    val pos = positions[index.coerceIn(0, positions.lastIndex)]
    val view = pos.view
    val step = view.steps[pos.stepIndex]
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxSize()) {
        // Header: current view, step counter and protocol list
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewNumberBadge(pos.viewNumber, colors.secondary)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = view.title.get(language),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.guided_step_of,
                        pos.window.title.get(language),
                        pos.stepIndex + 1,
                        view.steps.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
            TextButton(onClick = { showProtocolList = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.guided_protocol_list))
            }
        }
        LinearProgressIndicator(
            progress = { (index + 1).toFloat() / positions.size },
            modifier = Modifier.fillMaxWidth(),
            color = colors.primary,
            trackColor = colors.surface,
            drawStopIndicator = {}
        )

        // Scrollable media: diagram with caliper overlay, cine loop and notch orientation
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiagramWithCaliper(
                svgAsset = view.svgAsset,
                caliper = step.caliper,
                contentDescription = view.title.get(language)
            )
            EchoCinePlayer(videoAssetPath = view.videoAsset, simulated = protocol.loopsSimulated)
            Text(
                text = stringResource(R.string.notch_label, view.notchClock) +
                    " · " + view.notchDescription.get(language),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.secondary
            )
        }

        // Bottom sheet: current instruction and navigation
        Surface(color = colors.surface, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = step.title.get(language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        modifier = Modifier.weight(1f)
                    )
                    if (step.note != null) {
                        OutlinedButton(
                            onClick = { showNote = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.guided_note),
                                color = colors.secondary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = step.instruction.get(language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface
                )
                step.normal?.let { normal ->
                    Surface(color = colors.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = stringResource(R.string.guided_normal, normal.get(language)),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { if (index > 0) index-- },
                        enabled = index > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.guided_previous)) }
                    Text(
                        text = "${index + 1} / ${positions.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                    val isLast = index >= positions.lastIndex
                    Button(
                        onClick = { index = if (isLast) 0 else index + 1 },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(if (isLast) R.string.guided_restart else R.string.guided_next),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    val note = step.note
    if (showNote && note != null) {
        AlertDialog(
            onDismissRequest = { showNote = false },
            confirmButton = {
                TextButton(onClick = { showNote = false }) { Text(stringResource(R.string.guided_done)) }
            },
            title = {
                Text(
                    text = stringResource(R.string.guided_note_title),
                    color = colors.secondary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = { Text(note.get(language), color = colors.onSurface) },
            containerColor = colors.surface
        )
    }

    if (showProtocolList) {
        AlertDialog(
            onDismissRequest = { showProtocolList = false },
            confirmButton = {
                TextButton(onClick = { showProtocolList = false }) { Text(stringResource(R.string.guided_close)) }
            },
            title = {
                Text(
                    text = stringResource(R.string.guided_sequence_title),
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    positions.filter { it.stepIndex == 0 }.forEach { first ->
                        val active = first.view.id == view.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    index = positions.indexOf(first)
                                    showProtocolList = false
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ViewNumberBadge(first.viewNumber, if (active) colors.primary else colors.secondary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = first.view.title.get(language),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = first.window.title.get(language) + " · " +
                                        pluralStringResource(
                                            R.plurals.guided_steps_count,
                                            first.view.steps.size,
                                            first.view.steps.size
                                        ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            containerColor = colors.surface
        )
    }
}

@Composable
private fun ViewNumberBadge(number: Int, color: Color) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number.toString(),
            color = MaterialTheme.colorScheme.background,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Anatomy diagram (4:3, same aspect as the 400x300 SVG viewBox) with an optional caliper line.
 * Neither layer is mirrored in RTL: the anatomy keeps its standard echo orientation.
 */
@Composable
private fun DiagramWithCaliper(svgAsset: String, caliper: List<Float>?, contentDescription: String) {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(SVG_WIDTH / SVG_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data("file:///android_asset/$svgAsset")
                .decoderFactory(SvgDecoder.Factory())
                .build(),
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
        if (caliper != null && caliper.size == 4) {
            Canvas(modifier = Modifier.fillMaxSize()) { drawCaliper(caliper) }
        }
    }
}

private fun DrawScope.drawCaliper(caliper: List<Float>) {
    val sx = size.width / SVG_WIDTH
    val sy = size.height / SVG_HEIGHT
    val a = Offset(caliper[0] * sx, caliper[1] * sy)
    val b = Offset(caliper[2] * sx, caliper[3] * sy)
    val stroke = 2.5.dp.toPx()
    drawLine(CaliperColor, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length = sqrt(dx * dx + dy * dy)
    if (length > 0f) {
        // Perpendicular end ticks, like an echo machine caliper
        val tick = 6.dp.toPx()
        val px = -dy / length * tick
        val py = dx / length * tick
        listOf(a, b).forEach { p ->
            drawLine(
                CaliperColor,
                Offset(p.x - px, p.y - py),
                Offset(p.x + px, p.y + py),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}
