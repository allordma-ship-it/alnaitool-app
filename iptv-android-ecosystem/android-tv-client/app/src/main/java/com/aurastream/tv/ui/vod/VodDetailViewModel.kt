package com.aurastream.tv.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.local.entity.EpisodeEntity
import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.repository.ChannelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailUiState(
    val item: ChannelEntity? = null,
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int? = null,
    val episodes: List<EpisodeEntity> = emptyList(),
    val loadingEpisodes: Boolean = false,
    val error: String? = null,
) {
    val isSeries get() = item?.streamType == StreamType.SERIES
    /** Episode to continue: last one with progress, else first of the selected season. */
    val resumeEpisode: EpisodeEntity? get() = episodes.lastOrNull { it.resumePositionMs > 0 } ?: episodes.firstOrNull()
}

class VodDetailViewModel(
    savedState: SavedStateHandle,
    private val repository: ChannelRepository,
) : ViewModel() {

    private val streamId: String = checkNotNull(savedState["streamId"])
    private val selectedSeason = MutableStateFlow<Int?>(null)
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<DetailUiState> = combine(
        repository.observe(streamId),
        repository.episodes(streamId),
        selectedSeason,
        loading,
        error,
    ) { item, episodes, season, isLoading, err ->
        val seasons = episodes.map { it.season }.distinct().sorted()
        val active = season?.takeIf { it in seasons } ?: seasons.firstOrNull()
        DetailUiState(
            item = item,
            seasons = seasons,
            selectedSeason = active,
            episodes = episodes.filter { it.season == active },
            loadingEpisodes = isLoading,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        viewModelScope.launch {
            val item = repository.observe(streamId).filterNotNull().first()
            loading.value = true
            try {
                if (item.streamType == StreamType.SERIES) repository.ensureEpisodes(item) else repository.ensureVodDetails(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message
            } finally {
                loading.value = false
            }
        }
    }

    fun selectSeason(season: Int) {
        selectedSeason.value = season
    }

    fun toggleFavorite() {
        viewModelScope.launch { repository.toggleFavorite(streamId) }
    }
}
