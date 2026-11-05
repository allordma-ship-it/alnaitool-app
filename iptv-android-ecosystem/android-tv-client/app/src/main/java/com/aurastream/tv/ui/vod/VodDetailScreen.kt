package com.aurastream.tv.ui.vod

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.aurastream.tv.R
import com.aurastream.tv.data.local.entity.EpisodeEntity
import com.aurastream.tv.ui.auraViewModel
import com.aurastream.tv.ui.components.AuraButton
import com.aurastream.tv.ui.components.Chip
import com.aurastream.tv.ui.components.LoadingIndicator
import com.aurastream.tv.ui.components.ProgressLine
import com.aurastream.tv.ui.components.RemoteImage
import com.aurastream.tv.ui.components.StopPlaybackEffect
import com.aurastream.tv.ui.theme.AuraColors
import com.aurastream.tv.ui.theme.AuraFocus

@Composable
fun VodDetailScreen(onPlay: (streamId: String, episodeId: String?) -> Unit, onBack: () -> Unit) {
    StopPlaybackEffect()
    val vm = auraViewModel { c, saved -> VodDetailViewModel(saved, c.channelRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    val item = state.item
    if (item == null) {
        LoadingIndicator()
        return
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(item.streamId) { runCatching { playFocus.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(AuraColors.Canvas)) {
        val compact = maxWidth < 840.dp
        RemoteImage(
            item.backdropUrl ?: item.logoUrl,
            "",
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            shape = RoundedCornerShape(0.dp),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(AuraColors.Canvas, AuraColors.Canvas.copy(alpha = 0.9f), Color(0x66080A0F))),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, AuraColors.Canvas))))

        LazyColumn(
            contentPadding = PaddingValues(horizontal = if (compact) 16.dp else 56.dp, vertical = if (compact) 16.dp else 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    if (!compact) {
                        RemoteImage(
                            item.logoUrl,
                            item.name,
                            Modifier.width(220.dp).aspectRatio(2f / 3f),
                            contentScale = ContentScale.Crop,
                            shape = RoundedCornerShape(14.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.releaseYear?.let { Chip(it) }
                            item.genre?.let { Chip(it.take(48)) }
                            item.rating?.takeIf { it != "0" && it.isNotBlank() }?.let { Chip("★ $it", color = AuraColors.Warning) }
                            item.durationSecs?.takeIf { it > 0 }?.let { Chip("${it / 60} min") }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            item.plot?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_plot),
                            style = MaterialTheme.typography.bodyLarge,
                            color = AuraColors.TextSecondary,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (state.isSeries) {
                                val resume = state.resumeEpisode
                                AuraButton(
                                    text = stringResource(if ((resume?.resumePositionMs ?: 0L) > 0L) R.string.resume else R.string.play),
                                    icon = Icons.Filled.PlayArrow,
                                    primary = true,
                                    enabled = resume != null,
                                    onClick = { resume?.let { onPlay(item.streamId, it.episodeId) } },
                                    modifier = Modifier.focusRequester(playFocus),
                                )
                            } else {
                                AuraButton(
                                    text = stringResource(if (item.resumePositionMs > 0) R.string.resume else R.string.play),
                                    icon = Icons.Filled.PlayArrow,
                                    primary = true,
                                    onClick = { onPlay(item.streamId, null) },
                                    modifier = Modifier.focusRequester(playFocus),
                                )
                            }
                            AuraButton(
                                text = stringResource(if (item.isFavorite) R.string.remove_favorite else R.string.add_favorite),
                                icon = if (item.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                onClick = vm::toggleFavorite,
                            )
                        }
                    }
                }
            }

            if (state.isSeries) {
                item {
                    Text(stringResource(R.string.seasons), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    if (state.loadingEpisodes && state.seasons.isEmpty()) {
                        Box(Modifier.height(80.dp)) { LoadingIndicator() }
                    } else if (state.seasons.isEmpty()) {
                        Text(state.error ?: stringResource(R.string.no_episodes), color = AuraColors.TextSecondary)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(state.seasons) { season ->
                            val selected = season == state.selectedSeason
                            AuraButton(
                                text = stringResource(R.string.season_n, season),
                                onClick = { vm.selectSeason(season) },
                                primary = selected,
                            )
                        }
                    }
                }
                items(state.episodes, key = { it.episodeId }) { ep ->
                    EpisodeRow(ep, compact) { onPlay(item.streamId, ep.episodeId) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: EpisodeEntity, compact: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(if (compact) 1f else 0.75f),
        shape = AuraFocus.shape(shape),
        scale = AuraFocus.scale(1.02f),
        border = AuraFocus.border(shape, 2.dp),
        colors = AuraFocus.colors(container = AuraColors.Surface.copy(alpha = 0.85f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(
                episode.imageUrl,
                "E${episode.episodeNum}",
                Modifier.width(if (compact) 110.dp else 180.dp).aspectRatio(16f / 9f),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${episode.episodeNum}. ${episode.title}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.plot?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = AuraColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val duration = (episode.durationSecs ?: 0) * 1000L
                if (episode.resumePositionMs > 0 && duration > 0) {
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(episode.resumePositionMs.toFloat() / duration, height = 3.dp)
                }
            }
            Spacer(Modifier.width(12.dp))
            androidx.tv.material3.Icon(Icons.Filled.PlayArrow, null, tint = AuraColors.NeonCyan, modifier = Modifier.size(28.dp))
        }
    }
}
