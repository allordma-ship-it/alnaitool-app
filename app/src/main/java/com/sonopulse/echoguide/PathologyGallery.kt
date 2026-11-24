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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
                                .background(if (selected) EchoCriticalFill else Color.Transparent)
                                .border(1.dp, if (selected) EchoCriticalFill else EchoSky.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .clickable { selectedId = c.id }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        Text(case.title.get(language), color = EchoPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // Media: real loops first, then the still images; each with its own credit and licence.
        val media = case.media
        val index = imageIndex.coerceIn(0, (media.size - 1).coerceAtLeast(0))
        val item = media.getOrNull(index)
        if (item != null) {
            // Imaging keeps its left-to-right orientation in Arabic.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(item.aspect.coerceIn(0.6f, 2.2f))
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    if (item.isLoop) {
                        key(item.asset) {
                            val player = rememberEchoPlayer()
                            val loop = rememberLoopState(player, item.asset)
                            LoopBox(player, loop, item.asset, attached = true, modifier = Modifier.matchParentSize())
                        }
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(MediaRegistry.assetUri(item.asset)).build(),
                            contentDescription = item.caption.get(language),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
            }
            if (media.size > 1) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    media.forEachIndexed { i, m ->
                        Text(
                            (if (m.isLoop) "▶ " else "▣ ") + m.caption.get(language),
                            fontSize = 11.sp,
                            color = if (i == index) Color.White else EchoMuted,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (i == index) EchoPrimaryFill else EchoSurface)
                                .clickable { imageIndex = i }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Text("© ${item.credit} · ${item.license}", color = EchoMuted, style = MaterialTheme.typography.labelSmall)
            Text(
                item.source,
                color = EchoSky.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.clickable { runCatching { uri.openUri(item.source) } }
            )
        }
        Text(case.findings.get(language), color = EchoText, style = MaterialTheme.typography.bodyMedium)
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
