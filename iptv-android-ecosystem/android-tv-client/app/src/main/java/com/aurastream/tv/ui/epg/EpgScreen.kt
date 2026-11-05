package com.aurastream.tv.ui.epg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurastream.tv.R
import com.aurastream.tv.data.local.entity.EpgEntity
import com.aurastream.tv.ui.auraViewModel
import com.aurastream.tv.ui.components.AuraButton
import com.aurastream.tv.ui.components.CenteredMessage
import com.aurastream.tv.ui.components.ProgressLine
import com.aurastream.tv.ui.components.RemoteImage
import com.aurastream.tv.ui.components.StopPlaybackEffect
import com.aurastream.tv.ui.theme.AuraColors
import com.aurastream.tv.ui.theme.AuraFocus
import java.text.DateFormat
import java.util.Date

@Composable
fun EpgScreen(onPlay: (String) -> Unit, onBack: () -> Unit) {
    StopPlaybackEffect()
    val favLabel = stringResource(R.string.category_favorites)
    val allLabel = stringResource(R.string.category_all)
    val vm = auraViewModel { c, _ -> EpgViewModel(c.channelRepository, favLabel, allLabel) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(AuraColors.Canvas).padding(24.dp)) {
        Text(stringResource(R.string.section_epg), style = MaterialTheme.typography.headlineMedium, color = AuraColors.NeonCyan)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
            items(state.categories, key = { it.categoryId }) { cat ->
                AuraButton(cat.categoryName, onClick = { vm.selectCategory(cat.categoryId) }, primary = cat.categoryId == state.selectedCategoryId)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.channels.isEmpty()) {
            CenteredMessage(title = stringResource(R.string.no_epg_channels), message = stringResource(R.string.no_epg_channels_hint))
            return@Column
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
                items(state.channels, key = { it.streamId }) { ch ->
                    val selected = ch.streamId == state.selectedChannel?.streamId
                    val shape = RoundedCornerShape(10.dp)
                    Surface(
                        onClick = { onPlay(ch.streamId) },
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) vm.selectChannel(ch) },
                        shape = AuraFocus.shape(shape),
                        scale = AuraFocus.scale(1.03f),
                        border = AuraFocus.border(shape, 2.dp),
                        colors = AuraFocus.colors(container = if (selected) AuraColors.SurfaceHigh else AuraColors.Surface),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RemoteImage(ch.logoUrl, ch.name, Modifier.size(width = 56.dp, height = 36.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Schedule(state.schedule, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Schedule(events: List<EpgEntity>, modifier: Modifier) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val day = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val now = System.currentTimeMillis()
    val listState = rememberLazyListState()
    LaunchedEffect(events) {
        val idx = events.indexOfFirst { it.endTime > now }
        if (idx > 0) listState.scrollToItem(idx)
    }
    if (events.isEmpty()) {
        CenteredMessage(title = stringResource(R.string.no_epg), modifier = modifier)
        return
    }
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(events, key = { it.epgId }) { e ->
            val live = e.startTime <= now && e.endTime > now
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (live) AuraColors.SurfaceHigh else Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${time.format(Date(e.startTime))} – ${time.format(Date(e.endTime))}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (live) AuraColors.NeonCyan else AuraColors.TextSecondary,
                        modifier = Modifier.width(150.dp),
                    )
                    Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(day.format(Date(e.startTime)), style = MaterialTheme.typography.labelSmall, color = AuraColors.TextTertiary)
                }
                if (live) {
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(((now - e.startTime).toFloat() / (e.endTime - e.startTime)).coerceIn(0f, 1f))
                }
                e.description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = AuraColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
