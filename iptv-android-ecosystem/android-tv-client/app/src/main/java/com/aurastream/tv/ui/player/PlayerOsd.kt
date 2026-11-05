package com.aurastream.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurastream.tv.R
import com.aurastream.tv.data.local.AspectMode
import com.aurastream.tv.data.local.StreamFormat
import com.aurastream.tv.player.PlayerUiState
import com.aurastream.tv.ui.channels.EpgBlock
import com.aurastream.tv.ui.components.AuraButton
import com.aurastream.tv.ui.components.LiveBadge
import com.aurastream.tv.ui.components.ProgressLine
import com.aurastream.tv.ui.components.RemoteImage
import com.aurastream.tv.ui.theme.AuraColors
import com.aurastream.tv.ui.theme.AuraFocus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlayerOsd(
    screen: PlayerScreenState,
    playback: PlayerUiState,
    vm: PlayerViewModel,
    onOpenPanel: (OsdPanel) -> Unit,
    onInteraction: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        // ---- Top: title / channel info --------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent)))
                .padding(horizontal = 48.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            screen.channel?.let { ch ->
                if (screen.isLive) {
                    val number = screen.currentIndex + 1
                    if (number > 0) {
                        Text("$number", style = MaterialTheme.typography.headlineMedium, color = AuraColors.NeonCyan)
                        Spacer(Modifier.width(16.dp))
                    }
                    RemoteImage(ch.logoUrl, ch.name, Modifier.size(width = 80.dp, height = 50.dp))
                    Spacer(Modifier.width(16.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(screen.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (screen.isLive) {
                        Spacer(Modifier.width(12.dp))
                        LiveBadge()
                    }
                }
                if (screen.episode != null) {
                    Text(screen.channel?.name.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary)
                }
                StreamInfoLine(playback)
            }
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
                style = MaterialTheme.typography.titleLarge,
                color = AuraColors.TextSecondary,
            )
        }

        // ---- Bottom: progress + controls ---------------------------------------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0000000))))
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            if (screen.isLive) {
                EpgBlock(screen.nowNext, Modifier.fillMaxWidth(0.6f))
            } else {
                SeekBar(playback, onSeekBy = { vm.player.seekBy(it); onInteraction() })
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OsdIconButton(
                    icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = stringResource(if (playback.isPlaying) R.string.pause else R.string.play),
                    onClick = { vm.player.togglePlayPause(); onInteraction() },
                    modifier = Modifier.focusRequester(primaryFocus),
                )
                if (screen.isLive) {
                    OsdIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.channel_prev), { vm.zap(-1); onInteraction() })
                    OsdIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.channel_next), { vm.zap(1); onInteraction() })
                    OsdIconButton(Icons.AutoMirrored.Filled.List, stringResource(R.string.channel_list), { onOpenPanel(OsdPanel.CHANNELS) })
                } else {
                    OsdIconButton(Icons.Filled.Replay10, stringResource(R.string.rewind), { vm.player.seekBy(-10_000); onInteraction() })
                    OsdIconButton(Icons.Filled.Forward10, stringResource(R.string.forward), { vm.player.seekBy(10_000); onInteraction() })
                    if (screen.nextEpisode != null) {
                        OsdIconButton(Icons.Filled.SkipNext, stringResource(R.string.next_episode), { vm.playNextEpisode() })
                    }
                }
                Spacer(Modifier.width(12.dp))
                OsdIconButton(
                    Icons.Filled.Audiotrack,
                    stringResource(R.string.audio_track),
                    { onOpenPanel(OsdPanel.AUDIO) },
                    enabled = playback.audioTracks.size > 1,
                )
                OsdIconButton(
                    Icons.Filled.ClosedCaption,
                    stringResource(R.string.subtitles),
                    { onOpenPanel(OsdPanel.SUBTITLES) },
                    enabled = playback.subtitleTracks.isNotEmpty(),
                    active = playback.subtitlesEnabled,
                )
                OsdTextButton(
                    icon = Icons.Filled.AspectRatio,
                    text = aspectLabel(playback.aspectMode),
                    onClick = { vm.player.cycleAspectMode(); onInteraction() },
                )
                if (screen.isLive && playback.canSwitchFormat) {
                    OsdTextButton(
                        icon = Icons.Filled.SettingsEthernet,
                        text = playback.format?.name ?: "AUTO",
                        onClick = {
                            val target = if (playback.format == StreamFormat.HLS) StreamFormat.TS else StreamFormat.HLS
                            vm.player.switchFormat(target)
                            onInteraction()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamInfoLine(playback: PlayerUiState) {
    val parts = buildList {
        if (playback.videoHeight > 0) add("${playback.videoHeight}p")
        playback.format?.let { add(it.name) }
        if (playback.softwareDecoding) add("SW")
        playback.recovery?.let { add(it.description) }
    }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = AuraColors.TextTertiary)
    }
}

@Composable
private fun aspectLabel(mode: AspectMode) = stringResource(
    when (mode) {
        AspectMode.FIT -> R.string.aspect_fit
        AspectMode.FILL -> R.string.aspect_fill
        AspectMode.ZOOM -> R.string.aspect_zoom
    },
)

/** Focusable seek bar: D-pad left/right seeks while focused (10 s, accelerating to 30 s on repeat). */
@Composable
private fun SeekBar(playback: PlayerUiState, onSeekBy: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val duration = playback.durationMs.coerceAtLeast(1)
    Column(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = if (ev.nativeKeyEvent.repeatCount > 3) 30_000L else 10_000L
                // Media timelines run left-to-right in every locale; flip D-pad meaning in RTL so
                // "right" still means forward visually.
                when (ev.key) {
                    Key.DirectionRight -> { onSeekBy(if (rtl) -step else step); true }
                    Key.DirectionLeft -> { onSeekBy(if (rtl) step else -step); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Box(Modifier.fillMaxWidth().height(if (focused) 10.dp else 6.dp).clip(RoundedCornerShape(50)).background(AuraColors.Outline)) {
            Box(
                Modifier
                    .fillMaxWidth((playback.bufferedMs.toFloat() / duration).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(AuraColors.TextTertiary),
            )
            Box(
                Modifier
                    .fillMaxWidth((playback.positionMs.toFloat() / duration).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(if (focused) AuraColors.NeonCyan else AuraColors.TextPrimary),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(playback.positionMs), style = MaterialTheme.typography.labelLarge, color = AuraColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                "-" + formatTime((playback.durationMs - playback.positionMs).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelLarge,
                color = AuraColors.TextSecondary,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

@Composable
private fun OsdIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(56.dp),
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.12f),
        border = AuraFocus.border(shape),
        colors = AuraFocus.colors(
            container = if (active) AuraColors.NeonCyanDim else Color(0x33FFFFFF),
            focusedContainer = AuraColors.TextPrimary,
            content = if (active) AuraColors.NeonCyan else AuraColors.TextPrimary,
            focusedContent = AuraColors.Canvas,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun OsdTextButton(icon: ImageVector, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = Modifier.height(56.dp),
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.08f),
        border = AuraFocus.border(shape),
        colors = AuraFocus.colors(container = Color(0x33FFFFFF), focusedContainer = AuraColors.TextPrimary, focusedContent = AuraColors.Canvas),
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ------------------------------------------------------------------ side panel

private data class PanelOption(val id: String?, val label: String, val selected: Boolean, val subtitle: String? = null, val logo: String? = null)

@Composable
fun OsdSidePanel(
    panel: OsdPanel,
    screen: PlayerScreenState,
    playback: PlayerUiState,
    vm: PlayerViewModel,
    onClose: () -> Unit,
) {
    val title: String
    val options: List<PanelOption>
    val onSelect: (String?) -> Unit
    when (panel) {
        OsdPanel.AUDIO -> {
            title = stringResource(R.string.audio_track)
            options = playback.audioTracks.map { PanelOption(it.id, it.label, it.selected) }
            onSelect = { id -> id?.let(vm.player::selectAudio); onClose() }
        }
        OsdPanel.SUBTITLES -> {
            title = stringResource(R.string.subtitles)
            options = listOf(PanelOption(null, stringResource(R.string.subtitles_off), !playback.subtitlesEnabled)) +
                playback.subtitleTracks.map { PanelOption(it.id, it.label, playback.subtitlesEnabled && it.selected) }
            onSelect = { id -> vm.player.selectSubtitle(id); onClose() }
        }
        OsdPanel.CHANNELS -> {
            title = stringResource(R.string.channel_list)
            options = screen.siblings.mapIndexed { i, ch ->
                PanelOption(ch.streamId, "${i + 1}  ${ch.name}", ch.streamId == screen.channel?.streamId, logo = ch.logoUrl ?: "")
            }
            onSelect = { id ->
                screen.siblings.firstOrNull { it.streamId == id }?.let(vm::zapTo)
                onClose()
            }
        }
        OsdPanel.NONE -> return
    }

    val listState = rememberLazyListState()
    val selectedFocus = remember { FocusRequester() }
    val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    LaunchedEffect(panel) {
        listState.scrollToItem((selectedIndex - 3).coerceAtLeast(0))
        runCatching { selectedFocus.requestFocus() }
    }

    Column(
        Modifier
            .width(420.dp)
            .fillMaxHeight()
            .background(Color(0xF2101520))
            .padding(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
            itemsIndexed(options, key = { i, o -> o.id ?: "none-$i" }) { i, option ->
                val shape = RoundedCornerShape(10.dp)
                Surface(
                    onClick = { onSelect(option.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (i == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier),
                    shape = AuraFocus.shape(shape),
                    scale = AuraFocus.scale(1.02f),
                    border = AuraFocus.border(shape, 2.dp),
                    colors = AuraFocus.colors(container = Color.Transparent),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (option.logo != null) {
                            RemoteImage(option.logo.ifEmpty { null }, option.label, Modifier.size(width = 52.dp, height = 32.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(option.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (option.selected) Icon(Icons.Filled.Check, null, tint = AuraColors.NeonCyan, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerErrorOverlay(message: String, onRetry: () -> Unit, onExit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(message) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(AuraColors.Scrim), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Warning, null, tint = AuraColors.Warning, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.playback_failed), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = AuraColors.TextSecondary)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AuraButton(stringResource(R.string.retry), onRetry, icon = Icons.Filled.Refresh, primary = true, modifier = Modifier.focusRequester(focus))
                AuraButton(stringResource(R.string.back), onExit)
            }
        }
    }
}
