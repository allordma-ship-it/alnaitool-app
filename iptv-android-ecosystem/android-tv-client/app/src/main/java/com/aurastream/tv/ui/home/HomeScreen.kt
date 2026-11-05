package com.aurastream.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurastream.tv.R
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.repository.LicenseStatus
import com.aurastream.tv.ui.auraViewModel
import com.aurastream.tv.ui.components.AuraButton
import com.aurastream.tv.ui.components.Chip
import com.aurastream.tv.ui.components.ImportProgress
import com.aurastream.tv.ui.components.RemoteImage
import com.aurastream.tv.ui.components.SectionTitle
import com.aurastream.tv.ui.components.StopPlaybackEffect
import com.aurastream.tv.ui.theme.AuraColors
import com.aurastream.tv.ui.theme.AuraFocus
import java.text.DateFormat
import java.util.Date

private data class HubSection(
    val title: Int,
    val icon: ImageVector,
    val count: Int?,
    val accent: Color,
    val onClick: () -> Unit,
)

@Composable
fun HomeScreen(
    onOpenLive: () -> Unit,
    onOpenVod: (String) -> Unit,
    onOpenEpg: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenItem: (ChannelEntity) -> Unit,
    onNeedsActivation: () -> Unit,
) {
    StopPlaybackEffect()
    val vm = auraViewModel { c, _ -> HomeViewModel(c.deviceRepository, c.playlistRepository, c.channelRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.needsActivation.collect { onNeedsActivation() } }

    val firstCard = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstCard.requestFocus() } }

    val sections = listOf(
        HubSection(R.string.section_live, Icons.Filled.LiveTv, state.liveCount, Color(0xFF00E5FF), onOpenLive),
        HubSection(R.string.section_movies, Icons.Filled.Movie, state.movieCount, Color(0xFFB388FF)) { onOpenVod(StreamType.MOVIE) },
        HubSection(R.string.section_series, Icons.Filled.VideoLibrary, state.seriesCount, Color(0xFFFF8A65)) { onOpenVod(StreamType.SERIES) },
        HubSection(R.string.section_epg, Icons.Filled.CalendarMonth, null, Color(0xFF69F0AE), onOpenEpg),
        HubSection(R.string.section_settings, Icons.Filled.Settings, null, Color(0xFF90A4AE), onOpenSettings),
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E1420), AuraColors.Canvas))),
    ) {
        val compact = maxWidth < 720.dp
        val hPad = if (compact) 16.dp else 48.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = if (compact) 16.dp else 32.dp),
        ) {
            TopBar(state, Modifier.padding(horizontal = hPad), onRefresh = { vm.refresh(force = false) })
            Spacer(Modifier.height(if (compact) 20.dp else 36.dp))

            state.importing?.let {
                ImportProgress(it, Modifier.padding(horizontal = hPad).fillMaxWidth())
                Spacer(Modifier.height(24.dp))
            }
            state.message?.let {
                Text(it, color = AuraColors.Error, modifier = Modifier.padding(horizontal = hPad))
                Spacer(Modifier.height(16.dp))
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = hPad),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
            ) {
                items(sections.size) { i ->
                    val s = sections[i]
                    SectionCard(
                        section = s,
                        compact = compact,
                        modifier = if (i == 0) Modifier.focusRequester(firstCard) else Modifier,
                    )
                }
            }

            if (state.recent.isNotEmpty()) {
                Spacer(Modifier.height(36.dp))
                SectionTitle(stringResource(R.string.continue_watching), Modifier.padding(horizontal = hPad))
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = hPad, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.recent, key = { it.streamId }) { item -> RecentCard(item) { onOpenItem(item) } }
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: HomeUiState, modifier: Modifier, onRefresh: () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = AuraColors.NeonCyan)
            state.playlist?.let {
                Text(it.name, style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        state.session?.let { s ->
            val (label, color) = when (s.status) {
                LicenseStatus.TRIAL -> stringResource(R.string.license_trial_days, s.daysLeft ?: 0) to AuraColors.Warning
                LicenseStatus.ACTIVE -> stringResource(R.string.license_active) to AuraColors.Success
                LicenseStatus.EXPIRED -> stringResource(R.string.license_expired) to AuraColors.Error
                LicenseStatus.UNKNOWN -> "" to AuraColors.TextSecondary
            }
            if (label.isNotEmpty()) Chip(label, color = color)
            Spacer(Modifier.width(16.dp))
        }
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
            style = MaterialTheme.typography.titleLarge,
            color = AuraColors.TextSecondary,
        )
        Spacer(Modifier.width(16.dp))
        AuraButton(
            text = stringResource(if (state.refreshing) R.string.refreshing else R.string.refresh),
            icon = Icons.Filled.Refresh,
            onClick = onRefresh,
            enabled = !state.refreshing,
        )
    }
}

@Composable
private fun SectionCard(section: HubSection, compact: Boolean, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = section.onClick,
        modifier = modifier
            .width(if (compact) 150.dp else 220.dp)
            .aspectRatio(0.85f)
            .onFocusChanged { focused = it.isFocused },
        shape = AuraFocus.shape(RoundedCornerShape(20.dp)),
        scale = AuraFocus.scale(1.08f),
        border = AuraFocus.border(RoundedCornerShape(20.dp)),
        colors = AuraFocus.colors(container = AuraColors.Surface, focusedContainer = AuraColors.SurfaceHighest),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(section.accent.copy(alpha = if (focused) 0.35f else 0.15f), Color.Transparent),
                    ),
                )
                .padding(20.dp),
        ) {
            Icon(section.icon, null, tint = section.accent, modifier = Modifier.size(if (compact) 36.dp else 52.dp))
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(stringResource(section.title), style = MaterialTheme.typography.titleLarge)
                section.count?.let {
                    Text(stringResource(R.string.items_count, it), style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun RecentCard(item: ChannelEntity, onClick: () -> Unit) {
    val isLive = item.streamType == StreamType.LIVE
    Surface(
        onClick = onClick,
        modifier = Modifier.width(if (isLive) 200.dp else 140.dp),
        shape = AuraFocus.shape(),
        scale = AuraFocus.scale(),
        border = AuraFocus.border(),
        colors = AuraFocus.colors(),
    ) {
        Column {
            RemoteImage(
                url = item.logoUrl,
                fallbackText = item.name,
                contentScale = if (isLive) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (isLive) 16f / 9f else 2f / 3f),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            )
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}
