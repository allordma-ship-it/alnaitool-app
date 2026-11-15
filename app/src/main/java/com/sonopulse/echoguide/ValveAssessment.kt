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

/** Narrow table cells: hyphenate long German words ("Regurgitations-volumen") instead of cutting them. */
private val CellText = TextStyle(fontSize = 10.5.sp, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)

private val GradeMild = Color(0xFF22C55E)
private val GradeModerate = Color(0xFFF59E0B)
private val GradeSevere = Color(0xFFEF4444)

/**
 * "Herzklappen" tab: structured assessment of the aortic, mitral, tricuspid and pulmonary valve –
 * 2D / colour / spectral Doppler checklists, normal values, stenosis and regurgitation grading,
 * links into the view cockpits and real example recordings.
 */
@Composable
fun ValveAssessmentScreen(language: AppLanguage, onOpenView: (String) -> Unit) {
    when (val state = rememberProtocolState()) {
        ProtocolState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = EchoTeal)
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
                        .background(if (active) EchoTeal.copy(alpha = 0.16f) else EchoSurface)
                        .border(if (active) 1.5.dp else 1.dp, if (active) EchoTeal else EchoOutline, RoundedCornerShape(12.dp))
                        .clickable { onSelectValve(v.id) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(v.short, color = if (active) EchoTeal else EchoText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                            colors = CheckboxDefaults.colors(checkedColor = EchoTeal, uncheckedColor = EchoMuted),
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
                    Text(normal.value.get(language), color = EchoTeal, style = MaterialTheme.typography.bodySmall,
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
        Text(stringResource(R.string.valve_disclaimer), color = EchoMuted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
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
        Text(title, color = EchoTeal, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
