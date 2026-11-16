package com.sonopulse.echoguide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.text.drawText

/** Narrow table cells: hyphenate long German words ("Regurgitations-volumen") instead of cutting them. */
private val CellText = TextStyle(fontSize = 10.5.sp, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)

private val GradeMild = EchoPalette.Emerald
private val GradeModerate = EchoPalette.Amber
private val GradeSevere = EchoPalette.Crimson

/**
 * "Herzklappen" tab: structured assessment of the aortic, mitral, tricuspid and pulmonary valve –
 * 2D / colour / spectral Doppler checklists, normal values, stenosis and regurgitation grading,
 * links into the view cockpits and real example recordings.
 */
@Composable
fun ValveAssessmentScreen(language: AppLanguage, onOpenView: (String) -> Unit) {
    when (val state = rememberProtocolState()) {
        ProtocolState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = EchoPrimary)
        }
        is ProtocolState.Error -> Text(stringResource(R.string.protocol_error, state.message), color = MaterialTheme.colorScheme.error)
        is ProtocolState.Ready -> {
            var valveId by rememberSaveable { mutableStateOf(state.protocol.valves.firstOrNull()?.id.orEmpty()) }
            ValveAssessmentContent(state.protocol, language, valveId, { valveId = it }, onOpenView, Modifier.fillMaxSize())
        }
    }
}

/** The assessment itself; also shown full screen from a view's cockpit, pre-set to that view's valve. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ValveAssessmentContent(
    protocol: EchoProtocol,
    language: AppLanguage,
    valveId: String,
    onSelectValve: (String) -> Unit,
    onOpenView: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val valve = protocol.valves.firstOrNull { it.id == valveId } ?: protocol.valves.firstOrNull() ?: return
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var exampleOpen by remember { mutableStateOf<Int?>(null) }
    var pathologyOpen by remember { mutableStateOf<String?>(null) }
    val views = protocol.allViews.associateBy { it.id }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            protocol.valves.forEach { v ->
                val active = v.id == valve.id
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) EchoPrimary.copy(alpha = 0.16f) else EchoSurface)
                        .border(if (active) 1.5.dp else 1.dp, if (active) EchoPrimary else EchoOutline, RoundedCornerShape(12.dp))
                        .clickable { onSelectValve(v.id) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(v.short, color = if (active) EchoPrimary else EchoText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(v.title.get(language), color = EchoMuted, style = CellText.copy(fontSize = 9.5.sp), maxLines = 2,
                        textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(valve.title.get(language), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = EchoText)

        ValveCard(stringResource(R.string.valve_section_views)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                valve.views.forEach { id ->
                    views[id]?.let { v -> ViewLinkChip(v.title.get(language)) { onOpenView(id) } }
                }
            }
        }

        if (valve.measurements.isNotEmpty()) {
            MeasurementGuide(valve, protocol, language, onOpenView)
        }

        listOf(
            R.string.valve_section_morphology to valve.morphology,
            R.string.valve_section_color to valve.color,
            R.string.valve_section_spectral to valve.spectral
        ).forEachIndexed { section, (titleRes, items) ->
            ValveCard(stringResource(titleRes)) {
                items.forEachIndexed { i, item ->
                    val key = "${valve.id}#$section#$i"
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(
                            checked = checked[key] == true,
                            onCheckedChange = { checked[key] = it },
                            colors = CheckboxDefaults.colors(checkedColor = EchoPrimary, uncheckedColor = EchoMuted),
                            modifier = Modifier.size(36.dp)
                        )
                        Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                            Text(item.text.get(language), color = EchoText, style = MaterialTheme.typography.bodySmall)
                            item.view?.let { id ->
                                views[id]?.let { v ->
                                    Text(
                                        "→ ${v.title.get(language)}",
                                        color = EchoSky,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.clickable { onOpenView(id) }.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        ValveCard(stringResource(R.string.valve_section_normals)) {
            valve.normals.forEach { normal ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(normal.parameter.get(language), color = EchoMuted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    Text(normal.value.get(language), color = EchoNormal, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
        }

        valve.grading.forEach { table -> GradingCard(table, language) }

        if (valve.examples.isNotEmpty()) {
            ValveCard(stringResource(R.string.valve_section_examples)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    valve.examples.forEachIndexed { i, image ->
                        Column(modifier = Modifier.width(132.dp).clickable { exampleOpen = i }) {
                            AsyncImage(
                                model = "file:///android_asset/${image.asset}",
                                contentDescription = image.caption.get(language),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.4f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black)
                            )
                            Text(image.caption.get(language), color = EchoText, fontSize = 10.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        val valveCases = protocol.pathologyForValve(valve.id)
        if (valveCases.isNotEmpty()) {
            ValveCard(stringResource(R.string.pathology_title)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    valveCases.forEach { c ->
                        Text(
                            "▶ " + c.title.get(language),
                            color = EchoSky,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, EchoCritical.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                .clickable { pathologyOpen = c.id }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        protocol.caseLibrary?.let { CaseLibrarySection(it, language) }
        Text(stringResource(R.string.valve_disclaimer), color = EchoMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
    }

    pathologyOpen?.let { first ->
        val cases = protocol.pathologyForValve(valve.id)
        val ordered = cases.filter { it.id == first } + cases.filter { it.id != first }
        FullScreenDialog(onDismiss = { pathologyOpen = null }) {
            PathologyGallery(protocol, ordered, language, Modifier.weight(1f))
        }
    }
    exampleOpen?.let { start ->
        // Start the viewer on the tapped image.
        val ordered = valve.examples.drop(start) + valve.examples.take(start)
        ExampleDialog(ordered, valve.title.get(language), language) { exampleOpen = null }
    }
}

@Composable
private fun ValveCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EchoSurface)
            .border(1.dp, EchoOutline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, color = EchoPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun ViewLinkChip(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, EchoSky.copy(alpha = 0.6f)), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = EchoSky, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = EchoSky, modifier = Modifier.size(16.dp))
    }
}

/** Grading table: parameter | mild | moderate | severe, colour-coded columns. */
@Composable
private fun GradingCard(table: GradingTable, language: AppLanguage) {
    ValveCard(table.title.get(language)) {
        // Header: the three grades; each parameter then gets its own full-width line, so long
        // German / Arabic names never have to fit a narrow column.
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            GradeHeader(stringResource(R.string.valve_grade_mild), GradeMild, Modifier.weight(1f))
            GradeHeader(stringResource(R.string.valve_grade_moderate), GradeModerate, Modifier.weight(1f))
            GradeHeader(stringResource(R.string.valve_grade_severe), GradeSevere, Modifier.weight(1f))
        }
        table.rows.forEach { r ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(EchoBackground.copy(alpha = 0.6f))
                    .padding(vertical = 5.dp, horizontal = 6.dp)
            ) {
                Text(r.parameter.get(language), color = EchoText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Top) {
                    GradeCell(r.mild.get(language), GradeMild, Modifier.weight(1f))
                    GradeCell(r.moderate.get(language), GradeModerate, Modifier.weight(1f))
                    GradeCell(r.severe.get(language), GradeSevere, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(3.dp))
        }
        table.note?.let { Text(it.get(language), color = EchoMuted, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun GradeHeader(text: String, color: Color, modifier: Modifier) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = modifier)
}

@Composable
private fun GradeCell(text: String, color: Color, modifier: Modifier) {
    Text(text, color = color, style = CellText.copy(fontSize = 11.5.sp), textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 2.dp))
}


/**
 * Interactive measurement guide: choose a measurement, see the real loop (2D or colour Doppler)
 * with the cursor / gate / caliper where it belongs, and a schematic trace showing how to
 * measure it (Vmax, VTI, PHT, E/A, DT, AcT, TAPSE, PISA, VC), with steps, formula and normals.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeasurementGuide(valve: HeartValve, protocol: EchoProtocol, language: AppLanguage, onOpenView: (String) -> Unit) {
    var selectedId by rememberSaveable(valve.id) { mutableStateOf(valve.measurements.first().id) }
    val meas = valve.measurements.firstOrNull { it.id == selectedId } ?: valve.measurements.first()
    var exampleOpen by remember { mutableStateOf(false) }
    val player = rememberEchoPlayer()
    val loop = rememberLoopState(player, meas.loop)
    val viewTitle = protocol.allViews.firstOrNull { it.id == meas.view }?.title?.get(language).orEmpty()

    ValveCard(stringResource(R.string.valve_section_measure)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            valve.measurements.forEach { m ->
                val active = m.id == meas.id
                Text(
                    m.title.get(language),
                    color = if (active) EchoOnAccent else EchoText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) EchoPrimary else EchoSurfaceHigh)
                        .clickable { selectedId = m.id }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 1) Where: the cursor / gate on the real loop
        Text(stringResource(R.string.valve_where, viewTitle), color = EchoSky, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
        ) {
            LoopBox(player, loop, meas.loop, attached = true, modifier = Modifier.fillMaxSize())
            meas.overlay?.let { o ->
                if (loop.videoAspect > 0f) CaliperCanvas(listOf(o), { it.video }, loop.videoAspect, Modifier.fillMaxSize())
            }
            meas.nyquistCmS?.let { NyquistBar(it, Modifier.align(Alignment.CenterStart).padding(4.dp).width(44.dp).height(120.dp)) }
            Text(
                stringResource(if (meas.nyquistCmS != null) R.string.valve_loop_color else R.string.valve_loop_2d),
                color = EchoOnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background((if (meas.nyquistCmS != null) EchoCritical else EchoPrimary).copy(alpha = 0.85f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        // 2) How: the measurement on the trace
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.valve_how), color = EchoSky, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        val trace = meas.trace
        if (trace != null) {
            RealTrace(trace, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)))
        } else {
            // No real recording available for this measurement: drawn teaching trace (labelled "Schema").
            SpectralGuide(meas.spectral, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)))
        }
        meas.steps.forEachIndexed { i, step ->
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text("${i + 1}.", color = EchoPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                Text(step.get(language), color = EchoText, style = MaterialTheme.typography.bodySmall)
            }
        }
        meas.formula?.let {
            Text(it.get(language), color = EchoCaution, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp))
        }
        meas.normal?.let {
            Text(it.get(language), color = EchoNormal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            ViewLinkChip(viewTitle) { onOpenView(meas.view) }
            if (meas.example != null) ViewLinkChip(stringResource(R.string.step_example)) { exampleOpen = true }
        }
    }
    if (exampleOpen && meas.example != null) {
        val img = GalleryImage(meas.example, 1.4f, meas.title, credit = "Echocardiographer.org – Atif Qasim, MD MSCE")
        ExampleDialog(listOf(img), meas.title.get(language), language) { exampleOpen = false }
    }
}


/** Real spectral / M-mode recording with the measurement points (Vmax, VTI, E, A, DT, TAPSE) marked on it. */
@Composable
private fun RealTrace(trace: SpectralTrace, modifier: Modifier = Modifier) {
    val text = androidx.compose.ui.text.rememberTextMeasurer()
    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(trace.aspect).background(Color.Black)) {
            AsyncImage(
                model = "file:///android_asset/${trace.asset}",
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                trace.marks.forEach { m ->
                    val p = androidx.compose.ui.geometry.Offset(m.x * size.width, m.y * size.height)
                    val ring = 7.dp.toPx()
                    if (m.x2 != null && m.y2 != null) {
                        val q = androidx.compose.ui.geometry.Offset(m.x2 * size.width, m.y2 * size.height)
                        drawLine(Color.Black, p, q, strokeWidth = 5.dp.toPx())
                        drawLine(EchoPalette.Caliper, p, q, strokeWidth = 2.5.dp.toPx())
                        drawCircle(EchoPalette.Caliper, 3.5.dp.toPx(), q)
                    }
                    drawCircle(Color.Black, ring + 1.5f, p, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
                    drawCircle(EchoPalette.Caliper, ring, p, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    val layout = text.measure(m.label, androidx.compose.ui.text.TextStyle(color = EchoPalette.Caliper,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    val tx = (p.x + ring + 4f).coerceAtMost(size.width - layout.size.width - 2f)
                    val ty = (p.y - layout.size.height - 2f).coerceAtLeast(0f)
                    drawRect(Color.Black.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(tx - 3f, ty - 1f),
                        androidx.compose.ui.geometry.Size(layout.size.width + 6f, layout.size.height + 2f))
                    drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(tx, ty))
                }
            }
        }
        Text("© ${trace.credit}", color = EchoMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * Pathology examples: the Echocardiographer.org Image Library (55 cases, YouTube videos, opened
 * online – they cannot be bundled), grouped by disease category.
 */
@Composable
internal fun CaseLibrarySection(library: CaseLibrary, language: AppLanguage) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    ValveCard(stringResource(R.string.case_library_title)) {
        Text(stringResource(R.string.case_library_hint), color = EchoMuted, style = MaterialTheme.typography.labelSmall)
        library.groups.forEach { g ->
            val expanded = open == g.id
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { open = if (expanded) null else g.id }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(g.title.get(language), color = EchoText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${g.cases.size}", color = EchoMuted, fontSize = 12.sp)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = EchoMuted)
            }
            if (expanded) {
                g.cases.forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { runCatching { uri.openUri(c.url) } }
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("▶", color = EchoCritical, fontSize = 12.sp, modifier = Modifier.width(20.dp))
                        Text(c.title, color = EchoSky, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Text("© ${library.credit}", color = EchoMuted, fontSize = 9.sp)
    }
}
