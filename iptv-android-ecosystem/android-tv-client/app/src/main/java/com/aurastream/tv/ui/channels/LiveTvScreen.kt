package com.aurastream.tv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurastream.tv.R
import com.aurastream.tv.data.local.dao.CategoryWithCount
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.repository.NowNext
import com.aurastream.tv.data.repository.VirtualCategory
import com.aurastream.tv.ui.appContainer
import com.aurastream.tv.ui.auraViewModel
import com.aurastream.tv.ui.components.CenteredMessage
import com.aurastream.tv.ui.components.LiveBadge
import com.aurastream.tv.ui.components.LoadingIndicator
import com.aurastream.tv.ui.components.PinDialog
import com.aurastream.tv.ui.components.ProgressLine
import com.aurastream.tv.ui.components.RemoteImage
import com.aurastream.tv.ui.player.PlayerSurface
import com.aurastream.tv.ui.theme.AuraColors
import com.aurastream.tv.ui.theme.AuraFocus
import java.text.DateFormat
import java.util.Date

@Composable
fun LiveTvScreen(onOpenFullscreen: (String) -> Unit, onBack: () -> Unit) {
    val favLabel = stringResource(R.string.category_favorites)
    val allLabel = stringResource(R.string.category_all)
    val vm = auraViewModel { c, _ -> LiveTvViewModel(c.channelRepository, c.settings, c.player, favLabel, allLabel) }
    val state by vm.state.collectAsStateWithLifecycle()

    BackHandler {
        vm.stopPreview()
        onBack()
    }

    val channelClick: (ChannelEntity) -> Unit = { ch -> if (vm.onChannelClick(ch)) onOpenFullscreen(ch.streamId) }

    BoxWithConstraints(Modifier.fillMaxSize().background(AuraColors.Canvas)) {
        val compact = maxWidth < 840.dp
        when {
            state.loading -> LoadingIndicator()
            state.categories.all { it.channelCount == 0 } -> CenteredMessage(
                title = stringResource(R.string.empty_live_title),
                message = stringResource(R.string.empty_live_message),
            )
            compact -> CompactLayout(state, vm, channelClick, onOpenFullscreen)
            else -> WideLayout(state, vm, channelClick, onOpenFullscreen)
        }
    }

    state.pinRequest?.let { req ->
        PinDialog(
            title = if (req is PinRequest.ToggleLock) {
                stringResource(if (req.category.isLocked) R.string.pin_unlock_category else R.string.pin_lock_category, req.category.categoryName)
            } else {
                stringResource(R.string.pin_enter_for, req.category.categoryName)
            },
            onDismiss = vm::dismissPin,
            onSubmit = vm::submitPin,
        )
    }
}

@Composable
private fun WideLayout(
    state: LiveTvUiState,
    vm: LiveTvViewModel,
    onChannelClick: (ChannelEntity) -> Unit,
    onOpenFullscreen: (String) -> Unit,
) {
    val channelFocus = remember { FocusRequester() }
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // 1. Categories drawer
        CategoryList(
            categories = state.categories,
            selectedId = state.selectedCategoryId,
            onSelect = vm::selectCategory,
            onLongPress = vm::requestToggleLock,
            modifier = Modifier.width(270.dp).fillMaxHeight(),
        )
        // 2. Channel list
        ChannelList(
            channels = state.channels,
            previewId = state.previewChannel?.streamId,
            onClick = onChannelClick,
            onLongClick = vm::toggleFavorite,
            focusRequester = channelFocus,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        // 3. Mini player + EPG
        PreviewPane(
            channel = state.previewChannel,
            nowNext = state.nowNext,
            vm = vm,
            onOpenFullscreen = onOpenFullscreen,
            modifier = Modifier.weight(1.25f).fillMaxHeight(),
        )
    }
}

@Composable
private fun CompactLayout(
    state: LiveTvUiState,
    vm: LiveTvViewModel,
    onChannelClick: (ChannelEntity) -> Unit,
    onOpenFullscreen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PreviewPane(state.previewChannel, state.nowNext, vm, onOpenFullscreen, Modifier.fillMaxWidth().padding(12.dp), compact = true)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.categories, key = { it.categoryId }) { cat ->
                CategoryItem(cat, cat.categoryId == state.selectedCategoryId, { vm.selectCategory(cat) }, { vm.requestToggleLock(cat) }, pill = true)
            }
        }
        Spacer(Modifier.height(8.dp))
        ChannelList(
            channels = state.channels,
            previewId = state.previewChannel?.streamId,
            onClick = onChannelClick,
            onLongClick = vm::toggleFavorite,
            focusRequester = remember { FocusRequester() },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun CategoryList(
    categories: List<CategoryWithCount>,
    selectedId: String?,
    onSelect: (CategoryWithCount) -> Unit,
    onLongPress: (CategoryWithCount) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(categories.isNotEmpty()) {
        val idx = categories.indexOfFirst { it.categoryId == selectedId }.coerceAtLeast(0)
        if (categories.isNotEmpty()) {
            listState.scrollToItem(idx)
            runCatching { initialFocus.requestFocus() }
        }
    }
    Column(modifier) {
        Text(stringResource(R.string.categories), style = MaterialTheme.typography.titleMedium, color = AuraColors.TextSecondary)
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
            items(categories, key = { it.categoryId }) { cat ->
                val selected = cat.categoryId == selectedId
                CategoryItem(
                    category = cat,
                    selected = selected,
                    onClick = { onSelect(cat) },
                    onLongClick = { onLongPress(cat) },
                    modifier = if (selected) Modifier.focusRequester(initialFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun CategoryItem(
    category: CategoryWithCount,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    pill: Boolean = false,
) {
    val shape = RoundedCornerShape(if (pill) 50 else 10)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = if (pill) modifier else modifier.fillMaxWidth(),
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.03f),
        border = AuraFocus.border(shape, 2.dp),
        colors = AuraFocus.colors(
            container = if (selected) AuraColors.NeonCyanDim else Color.Transparent,
            focusedContainer = AuraColors.SurfaceHighest,
            content = if (selected) AuraColors.NeonCyan else AuraColors.TextPrimary,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = if (pill) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                VirtualCategory.isFavorites(category.categoryId) -> Icon(Icons.Filled.Star, null, Modifier.size(18.dp), tint = AuraColors.Warning)
                category.isLocked -> Icon(Icons.Filled.Lock, null, Modifier.size(18.dp), tint = AuraColors.TextSecondary)
                else -> Unit
            }
            if (VirtualCategory.isFavorites(category.categoryId) || category.isLocked) Spacer(Modifier.width(8.dp))
            Text(
                category.categoryName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (pill) Modifier else Modifier.weight(1f),
            )
            if (!pill) {
                Text("${category.channelCount}", style = MaterialTheme.typography.labelMedium, color = AuraColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<ChannelEntity>,
    previewId: String?,
    onClick: (ChannelEntity) -> Unit,
    onLongClick: (ChannelEntity) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(channels.firstOrNull()?.categoryId, channels.size) {
        val idx = channels.indexOfFirst { it.streamId == previewId }
        if (idx >= 0) listState.scrollToItem((idx - 3).coerceAtLeast(0)) else if (channels.isNotEmpty()) listState.scrollToItem(0)
    }
    Column(modifier) {
        Text(stringResource(R.string.channels_count, channels.size), style = MaterialTheme.typography.titleMedium, color = AuraColors.TextSecondary)
        Spacer(Modifier.height(12.dp))
        if (channels.isEmpty()) {
            CenteredMessage(title = stringResource(R.string.empty_category))
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
                itemsIndexed(channels, key = { _, ch -> ch.streamId }) { index, ch ->
                    ChannelRow(
                        number = index + 1,
                        channel = ch,
                        playing = ch.streamId == previewId,
                        onClick = { onClick(ch) },
                        onLongClick = { onLongClick(ch) },
                        modifier = if (ch.streamId == previewId) Modifier.focusRequester(focusRequester) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    number: Int,
    channel: ChannelEntity,
    playing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = appContainer().channelRepository
    val nowNext by remember(channel.epgChannelId) { repo.nowNext(channel.epgChannelId) }
        .collectAsStateWithLifecycle(NowNext(null, null))
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.03f),
        border = AuraFocus.border(shape, 2.dp),
        colors = AuraFocus.colors(container = if (playing) AuraColors.SurfaceHigh else AuraColors.Surface),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium,
                color = if (playing) AuraColors.NeonCyan else AuraColors.TextTertiary,
                modifier = Modifier.width(40.dp),
            )
            RemoteImage(channel.logoUrl, channel.name, Modifier.size(width = 64.dp, height = 40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (channel.isFavorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Favorite, null, Modifier.size(14.dp), tint = AuraColors.Live)
                    }
                }
                nowNext.now?.let { now ->
                    Text(now.title, style = MaterialTheme.typography.bodySmall, color = AuraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(nowNext.progress(), height = 2.dp)
                }
            }
            if (playing) {
                Spacer(Modifier.width(8.dp))
                LiveBadge()
            }
        }
    }
}

@Composable
private fun PreviewPane(
    channel: ChannelEntity?,
    nowNext: NowNext,
    vm: LiveTvViewModel,
    onOpenFullscreen: (String) -> Unit,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val playerState by vm.player.state.collectAsStateWithLifecycle()
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black),
        ) {
            if (channel != null) {
                PlayerSurface(vm.player, Modifier.fillMaxSize(), showSubtitles = false)
                if (playerState.isBuffering) LoadingIndicator()
                playerState.error?.let {
                    Text(it, color = AuraColors.Error, modifier = Modifier.align(Alignment.Center).padding(16.dp))
                }
                playerState.recovery?.let {
                    Text(
                        it.description,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                            .background(AuraColors.Scrim, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            } else {
                Text(stringResource(R.string.preview_hint), color = AuraColors.TextSecondary, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
        }
        if (channel != null && !compact) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(channel.logoUrl, channel.name, Modifier.size(width = 72.dp, height = 46.dp))
                Spacer(Modifier.width(12.dp))
                Text(channel.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Surface(
                    onClick = { onOpenFullscreen(channel.streamId) },
                    shape = AuraFocus.shape(RoundedCornerShape(50)),
                    border = AuraFocus.border(RoundedCornerShape(50)),
                    scale = AuraFocus.scale(),
                    colors = AuraFocus.colors(container = AuraColors.SurfaceHigh),
                ) {
                    Icon(Icons.Filled.Fullscreen, stringResource(R.string.fullscreen), Modifier.padding(10.dp).size(24.dp))
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { vm.toggleFavorite(channel) },
                    shape = AuraFocus.shape(RoundedCornerShape(50)),
                    border = AuraFocus.border(RoundedCornerShape(50)),
                    scale = AuraFocus.scale(),
                    colors = AuraFocus.colors(container = AuraColors.SurfaceHigh),
                ) {
                    Icon(
                        if (channel.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        stringResource(R.string.favorite),
                        Modifier.padding(10.dp).size(24.dp),
                        tint = if (channel.isFavorite) AuraColors.Live else AuraColors.TextPrimary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            EpgBlock(nowNext)
        }
    }
}

@Composable
fun EpgBlock(nowNext: NowNext, modifier: Modifier = Modifier) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(modifier) {
        val now = nowNext.now
        if (now == null) {
            Text(stringResource(R.string.no_epg), color = AuraColors.TextTertiary, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.epg_now), style = MaterialTheme.typography.labelLarge, color = AuraColors.NeonCyan)
            Spacer(Modifier.width(8.dp))
            Text("${time.format(Date(now.startTime))} – ${time.format(Date(now.endTime))}", style = MaterialTheme.typography.labelMedium, color = AuraColors.TextSecondary)
        }
        Text(now.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        ProgressLine(nowNext.progress())
        now.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        nowNext.next?.let { next ->
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.epg_next), style = MaterialTheme.typography.labelLarge, color = AuraColors.TextSecondary)
                Spacer(Modifier.width(8.dp))
                Text("${time.format(Date(next.startTime))}  ${next.title}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
