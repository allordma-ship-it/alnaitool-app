package com.aurastream.tv.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurastream.tv.data.local.SettingsStore
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.local.entity.EpisodeEntity
import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.repository.ChannelRepository
import com.aurastream.tv.data.repository.NowNext
import com.aurastream.tv.player.Media3PlayerAdapter
import com.aurastream.tv.player.PlaybackRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerScreenState(
    val channel: ChannelEntity? = null,
    val episode: EpisodeEntity? = null,
    val nextEpisode: EpisodeEntity? = null,
    val siblings: List<ChannelEntity> = emptyList(),
    val nowNext: NowNext = NowNext(null, null),
    val notFound: Boolean = false,
) {
    val isLive get() = channel?.streamType == StreamType.LIVE
    val title get() = episode?.title ?: channel?.name.orEmpty()
    val currentIndex get() = siblings.indexOfFirst { it.streamId == channel?.streamId }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    savedState: SavedStateHandle,
    private val repository: ChannelRepository,
    private val settings: SettingsStore,
    val player: Media3PlayerAdapter,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val initialStreamId: String = checkNotNull(savedState["streamId"])
    private val initialEpisodeId: String? = savedState["episodeId"]

    private val screen = MutableStateFlow(PlayerScreenState())

    val state: StateFlow<PlayerScreenState> = combine(
        screen,
        screen.flatMapLatest { repository.nowNext(it.channel?.epgChannelId) },
    ) { s, epg -> s.copy(nowNext = epg) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerScreenState())

    init {
        viewModelScope.launch {
            val channel = repository.get(initialStreamId)
            if (channel == null) {
                screen.value = PlayerScreenState(notFound = true)
                return@launch
            }
            if (initialEpisodeId != null) {
                val episode = repository.episode(initialEpisodeId)
                if (episode == null) {
                    screen.value = PlayerScreenState(notFound = true)
                    return@launch
                }
                startEpisode(channel, episode)
            } else {
                startChannel(channel)
            }
        }
        // Periodic resume-point persistence for VOD.
        viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                saveProgress()
            }
        }
    }

    private suspend fun startChannel(channel: ChannelEntity) {
        val siblings = if (channel.streamType == StreamType.LIVE) repository.siblings(channel) else emptyList()
        screen.value = screen.value.copy(channel = channel, episode = null, nextEpisode = null, siblings = siblings)
        if (!player.isPlayingMedia(channel.streamId)) {
            player.play(
                PlaybackRequest(
                    mediaId = channel.streamId,
                    url = channel.streamUrl,
                    title = channel.name,
                    isLive = channel.streamType == StreamType.LIVE,
                    startPositionMs = if (channel.streamType == StreamType.MOVIE) channel.resumePositionMs else 0,
                    userAgent = channel.httpUserAgent,
                    referrer = channel.httpReferrer,
                    artworkUrl = channel.logoUrl,
                ),
            )
        }
        if (channel.streamType == StreamType.LIVE) settings.setLastChannelId(channel.streamId)
        repository.saveProgress(channel.streamId, if (channel.streamType == StreamType.MOVIE) channel.resumePositionMs else 0)
    }

    private suspend fun startEpisode(series: ChannelEntity, episode: EpisodeEntity) {
        val episodes = repository.episodeList(series.streamId)
        val next = episodes.getOrNull(episodes.indexOfFirst { it.episodeId == episode.episodeId } + 1)
        screen.value = screen.value.copy(channel = series, episode = episode, nextEpisode = next, siblings = emptyList())
        player.play(
            PlaybackRequest(
                mediaId = episode.episodeId,
                url = episode.streamUrl,
                title = episode.title,
                isLive = false,
                startPositionMs = episode.resumePositionMs,
                artworkUrl = episode.imageUrl ?: series.logoUrl,
            ),
        )
    }

    /** Channel up / down within the current category (wraps around). */
    fun zap(delta: Int) {
        val s = screen.value
        if (!s.isLive || s.siblings.isEmpty()) return
        val idx = s.currentIndex.coerceAtLeast(0)
        val target = s.siblings[(idx + delta).mod(s.siblings.size)]
        zapTo(target)
    }

    fun zapTo(channel: ChannelEntity) {
        viewModelScope.launch { startChannel(channel) }
    }

    fun playNextEpisode() {
        val s = screen.value
        val series = s.channel ?: return
        val next = s.nextEpisode ?: return
        viewModelScope.launch {
            saveProgress()
            startEpisode(series, next)
        }
    }

    private suspend fun saveProgress() {
        val s = screen.value
        val channel = s.channel ?: return
        if (s.isLive) return
        val ui = player.state.value
        if (ui.mediaId == null) return
        val position = ui.positionMs
        val duration = ui.durationMs
        // Treat > 95 % as finished so the next open starts from the beginning.
        val resume = if (duration > 0 && position > duration * 0.95) 0L else position
        val episode = s.episode
        if (episode != null) {
            repository.saveEpisodeProgress(channel.streamId, episode.episodeId, resume)
        } else {
            repository.saveProgress(channel.streamId, resume)
        }
    }

    /**
     * Persists the resume point synchronously-snapshotted on the main thread and written on the
     * app scope (survives ViewModel clearing). Falls back to the adapter's last stop snapshot when
     * another screen already stopped the shared player.
     */
    fun persistResumePoint() {
        val s = screen.value
        val channel = s.channel ?: return
        if (s.isLive) return
        val expectedId = s.episode?.episodeId ?: channel.streamId
        val ui = player.state.value
        val (position, duration) = when {
            ui.mediaId == expectedId -> ui.positionMs to ui.durationMs
            player.lastSnapshot?.mediaId == expectedId -> player.lastSnapshot!!.let { it.positionMs to it.durationMs }
            else -> return
        }
        val resume = if (duration > 0 && position > duration * 0.95) 0L else position
        val episode = s.episode
        appScope.launch {
            if (episode != null) repository.saveEpisodeProgress(channel.streamId, episode.episodeId, resume)
            else repository.saveProgress(channel.streamId, resume)
        }
    }

    override fun onCleared() {
        persistResumePoint()
        // VOD never keeps playing without a surface; live keeps running for the Live TV mini-player.
        if (!screen.value.isLive) player.stop()
        super.onCleared()
    }
}
