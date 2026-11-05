package com.aurastream.tv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.repository.VirtualCategory
import com.aurastream.tv.ui.auraViewModel
import com.aurastream.tv.ui.components.CenteredMessage
import com.aurastream.tv.ui.components.Chip
import com.aurastream.tv.ui.components.LoadingIndicator
import com.aurastream.tv.ui.components.PinDialog
import com.aurastream.tv.ui.components.ProgressLine
import com.aurastream.tv.ui.components.RemoteImage
import com.aurastream.tv.ui.components.StopPlaybackEffect
import com.aurastream.tv.ui.theme.AuraColors
import com.aurastream.tv.ui.theme.AuraFocus

@Composable
fun VodScreen(onOpenDetail: (String) -> Unit, onBack: () -> Unit) {
    StopPlaybackEffect()
    val favLabel = stringResource(R.string.category_favorites)
    val allLabel = stringResource(R.string.category_all)
    val vm = auraViewModel { c, saved -> VodViewModel(saved, c.channelRepository, c.settings, favLabel, allLabel) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    BoxWithConstraints(Modifier.fillMaxSize().background(AuraColors.Canvas)) {
        val compact = maxWidth < 840.dp
        // Backdrop of the focused title for a cinematic feel on TV.
        if (!compact) {
            state.focused?.let { f ->
                RemoteImage(
                    url = f.backdropUrl ?: f.logoUrl,
                    fallbackText = "",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(0.dp),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(AuraColors.Canvas, AuraColors.Canvas.copy(alpha = 0.92f), AuraColors.Canvas.copy(alpha = 0.75f))),
                    ),
                )
            }
        }

        if (state.loading) {
            LoadingIndicator()
            return@BoxWithConstraints
        }
        val title = stringResource(if (state.type == StreamType.SERIES) R.string.section_series else R.string.section_movies)

        if (compact) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                SearchField(state.query, vm::setQuery, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.categories, key = { it.categoryId }) { cat ->
                        CategoryPill(cat, cat.categoryId == state.selectedCategoryId && state.query.isBlank()) { vm.select(cat) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                PosterGrid(state.items, minSize = 110.dp, onFocus = vm::onFocus, onClick = { onOpenDetail(it.streamId) })
            }
        } else {
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(280.dp).fillMaxHeight()) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = AuraColors.NeonCyan)
                    Spacer(Modifier.height(12.dp))
                    SearchField(state.query, vm::setQuery, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
                        items(state.categories, key = { it.categoryId }) { cat ->
                            CategoryRow(cat, cat.categoryId == state.selectedCategoryId && state.query.isBlank()) { vm.select(cat) }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    FocusedInfo(state.focused)
                    Spacer(Modifier.height(12.dp))
                    PosterGrid(state.items, minSize = 150.dp, onFocus = vm::onFocus, onClick = { onOpenDetail(it.streamId) })
                }
            }
        }
    }

    state.pinFor?.let { cat ->
        PinDialog(stringResource(R.string.pin_enter_for, cat.categoryName), onDismiss = vm::dismissPin, onSubmit = vm::submitPin)
    }
}

@Composable
private fun FocusedInfo(item: ChannelEntity?) {
    Column(Modifier.fillMaxWidth().height(120.dp)) {
        if (item == null) return@Column
        Text(item.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.releaseYear?.let { Chip(it) }
            item.genre?.let { Chip(it.take(40)) }
            item.rating?.takeIf { it != "0" }?.let { Chip("★ $it", color = AuraColors.Warning) }
        }
        item.plot?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AuraColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PosterGrid(
    items: List<ChannelEntity>,
    minSize: androidx.compose.ui.unit.Dp,
    onFocus: (ChannelEntity) -> Unit,
    onClick: (ChannelEntity) -> Unit,
) {
    if (items.isEmpty()) {
        CenteredMessage(title = stringResource(R.string.empty_category))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(items, key = { it.streamId }) { item -> PosterCard(item, onFocus = { onFocus(item) }, onClick = { onClick(item) }) }
    }
}

@Composable
fun PosterCard(item: ChannelEntity, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.08f),
        border = AuraFocus.border(shape),
        colors = AuraFocus.colors(),
    ) {
        Box(Modifier.fillMaxSize()) {
            RemoteImage(item.logoUrl, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, shape = shape)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(10.dp),
            ) {
                Column {
                    Text(item.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.resumePositionMs > 0 && (item.durationSecs ?: 0) > 0) {
                        Spacer(Modifier.height(4.dp))
                        ProgressLine(item.resumePositionMs / (item.durationSecs!! * 1000f), height = 3.dp)
                    }
                }
            }
            if (item.isFavorite) {
                Icon(Icons.Filled.Star, null, tint = AuraColors.Warning, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp))
            }
        }
    }
}

@Composable
private fun CategoryRow(category: CategoryWithCount, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.03f),
        border = AuraFocus.border(shape, 2.dp),
        colors = AuraFocus.colors(
            container = if (selected) AuraColors.NeonCyanDim else Color.Transparent,
            content = if (selected) AuraColors.NeonCyan else AuraColors.TextPrimary,
        ),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (VirtualCategory.isFavorites(category.categoryId)) {
                Icon(Icons.Filled.Star, null, Modifier.size(18.dp), tint = AuraColors.Warning)
                Spacer(Modifier.width(8.dp))
            } else if (category.isLocked) {
                Icon(Icons.Filled.Lock, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(category.categoryName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${category.channelCount}", style = MaterialTheme.typography.labelMedium, color = AuraColors.TextTertiary)
        }
    }
}

@Composable
private fun CategoryPill(category: CategoryWithCount, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        shape = AuraFocus.shape(shape),
        border = AuraFocus.border(shape, 2.dp),
        colors = AuraFocus.colors(
            container = if (selected) AuraColors.NeonCyanDim else AuraColors.Surface,
            content = if (selected) AuraColors.NeonCyan else AuraColors.TextPrimary,
        ),
    ) {
        Text(category.categoryName, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** TV-friendly search box: focus ring + system IME (on-screen keyboard or voice). */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(AuraColors.Surface)
            .border(2.dp, if (focused) AuraColors.NeonCyan else AuraColors.Outline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = AuraColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(stringResource(R.string.search_hint), color = AuraColors.TextTertiary, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AuraColors.TextPrimary),
                cursorBrush = SolidColor(AuraColors.NeonCyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
    }
}
