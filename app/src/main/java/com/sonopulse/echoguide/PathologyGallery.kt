package com.sonopulse.echoguide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Pathology groups in display order. */
private val GROUPS = listOf(
    "aortic" to LocalizedText("Aortic valve", "Aortenklappe", "الصمام الأبهري"),
    "mitral" to LocalizedText("Mitral valve", "Mitralklappe", "الصمام التاجي"),
    "tricuspid" to LocalizedText("Tricuspid valve / RV / PH", "Trikuspidalklappe / RV / PH", "ثلاثي الشرف / البطين الأيمن"),
    "cardiomyopathy" to LocalizedText("Cardiomyopathies", "Kardiomyopathien", "اعتلالات العضلة القلبية"),
    "pericardium" to LocalizedText("Pericardium", "Perikard", "التامور"),
    "aorta" to LocalizedText("Aorta", "Aorta", "الأبهر"),
    "congenital" to LocalizedText("Septal defects / shunts", "Septumdefekte / Shunts", "عيوب الحاجز والتحويلات"),
)

/**
 * "Pathologie" viewer: chips per case (grouped), the selected case's real loop or image, what to
 * look for compared with normal, and its licence; optional online video link.
 */
@Composable
fun PathologyGallery(protocol: EchoProtocol, cases: List<PathologyCase>, language: AppLanguage, modifier: Modifier = Modifier) {
    if (cases.isEmpty()) return
    var selectedId by rememberSaveable(cases.map { it.id }.joinToString()) { mutableStateOf(cases.first().id) }
    val case = cases.firstOrNull { it.id == selectedId } ?: cases.first()
    var imageIndex by rememberSaveable(case.id) { mutableStateOf(0) }
    val uri = LocalUriHandler.current
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.pathology_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = EchoText)
        GROUPS.forEach { (groupId, groupTitle) ->
            val inGroup = cases.filter { it.group == groupId }
            if (inGroup.isNotEmpty()) {
                Text(groupTitle.get(language), color = EchoMuted, style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    inGroup.forEach { c ->
                        val selected = c.id == case.id
                        Text(
                            c.title.get(language),
                            color = if (selected) Color.White else EchoSky,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) EchoPalette.Crimson else Color.Transparent)
                                .border(1.dp, if (selected) EchoPalette.Crimson else EchoSky.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .clickable { selectedId = c.id }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        Text(case.title.get(language), color = EchoPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // Media: the loop first, then the still images.
        val media = listOfNotNull(case.loop) + case.images.map { it.asset }
        val index = imageIndex.coerceIn(0, (media.size - 1).coerceAtLeast(0))
        if (media.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(case.images.getOrNull(index - if (case.loop != null) 1 else 0)?.aspect ?: (4f / 3f))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                val asset = media[index]
                if (asset.endsWith(".mp4")) {
                    key(asset) {
                        val player = rememberEchoPlayer()
                        val loop = rememberLoopState(player, asset)
                        LoopBox(player, loop, asset, attached = true, modifier = Modifier.matchParentSize())
                    }
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data("file:///android_asset/$asset").build(),
                        contentDescription = case.title.get(language),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (media.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    media.indices.forEach { i ->
                        val label = if (media[i].endsWith(".mp4")) "▶ Loop" else
                            case.images[i - if (case.loop != null) 1 else 0].caption.get(language)
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = if (i == index) Color.White else EchoMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (i == index) EchoPalette.MedicalBlue else EchoSurface)
                                .clickable { imageIndex = i }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
        Text(case.findings.get(language), color = EchoText, style = MaterialTheme.typography.bodyMedium)
        Text("© ${case.credit} · ${case.license}", color = EchoMuted, style = MaterialTheme.typography.labelSmall)
        Text(
            case.source,
            color = EchoSky.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clickable { runCatching { uri.openUri(case.source) } }
        )
        case.onlineUrl?.let { url ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, EchoCritical.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { runCatching { uri.openUri(url) } }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("▶ ", color = EchoCritical, fontSize = 12.sp)
                Text(stringResource(R.string.pathology_online), color = EchoSky, fontSize = 12.sp, modifier = Modifier.width(260.dp))
            }
        }
    }
}
