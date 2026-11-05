package com.aurastream.tv.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurastream.tv.data.local.AppSettings
import com.aurastream.tv.data.local.SettingsStore
import com.aurastream.tv.data.local.dao.CategoryWithCount
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.repository.ChannelRepository
import com.aurastream.tv.data.repository.NowNext
import com.aurastream.tv.data.repository.VirtualCategory
import com.aurastream.tv.player.Media3PlayerAdapter
import com.aurastream.tv.player.PlaybackRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A PIN-protected action awaiting confirmation. */
sealed interface PinRequest {
    val category: CategoryWithCount

    data class Open(override val category: CategoryWithCount) : PinRequest
    data class ToggleLock(override val category: CategoryWithCount) : PinRequest
}

data class LiveTvUiState(
    val categories: List<CategoryWithCount> = emptyList(),
    val selectedCategoryId: String? = null,
    val channels: List<ChannelEntity> = emptyList(),
    val previewChannel: ChannelEntity? = null,
    val nowNext: NowNext = NowNext(null, null),
    val pinRequest: PinRequest? = null,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvViewModel(
    private val repository: ChannelRepository,
    private val settings: SettingsStore,
    val player: Media3PlayerAdapter,
    favoritesLabel: String,
    allLabel: String,
) : ViewModel() {

    private val selectedCategory = MutableStateFlow<String?>(null)
    private val previewChannel = MutableStateFlow<ChannelEntity?>(null)
    private val pinRequest = MutableStateFlow<PinRequest?>(null)
    private val unlocked = mutableSetOf<String>()
    private var cachedPin: String = AppSettings.DEFAULT_PIN

    private val categories = repository.categoriesWithVirtual(StreamType.LIVE, favoritesLabel, allLabel)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val channels = selectedCategory.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.channels(id)
    }

    private val nowNext = previewChannel.flatMapLatest { repository.nowNext(it?.epgChannelId) }

    val state: StateFlow<LiveTvUiState> = combine(
        categories,
        selectedCategory,
        channels,
        combine(previewChannel, nowNext, ::Pair),
        pinRequest,
    ) { cats, selected, list, (preview, epg), pin ->
        LiveTvUiState(
            categories = cats.orEmpty(),
            selectedCategoryId = selected,
            channels = list,
            previewChannel = preview,
            nowNext = epg,
            pinRequest = pin,
            loading = cats == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveTvUiState())

    init {
        viewModelScope.launch { settings.settings.collect { cachedPin = it.parentalPin } }
        viewModelScope.launch {
            val cats = categories.filterNotNull().first()
            val last = settings.current().lastChannelId?.let { repository.get(it) }
            val lastCategoryLocked = last?.let { repository.category(it.categoryId)?.isLocked } == true
            selectedCategory.value = when {
                last != null && !lastCategoryLocked -> last.categoryId
                (cats.firstOrNull { VirtualCategory.isFavorites(it.categoryId) }?.channelCount ?: 0) > 0 -> VirtualCategory.favorites(StreamType.LIVE)
                else -> VirtualCategory.all(StreamType.LIVE)
            }
            // Resume the last watched channel in the mini-player (or keep what is already playing).
            val current = player.state.value.mediaId?.let { repository.get(it) }
            when {
                current != null && current.streamType == StreamType.LIVE -> previewChannel.value = current
                last != null && !lastCategoryLocked -> preview(last)
            }
        }
    }

    init {
        // Keep the mini-player selection in sync with zapping done in the fullscreen player.
        viewModelScope.launch {
            player.state.map { it.mediaId }.distinctUntilChanged().collect { id ->
                if (id != null && id != previewChannel.value?.streamId) {
                    repository.get(id)?.takeIf { it.streamType == StreamType.LIVE }?.let { previewChannel.value = it }
                }
            }
        }
    }

    fun selectCategory(category: CategoryWithCount) {
        if (category.isLocked && category.categoryId !in unlocked) {
            pinRequest.value = PinRequest.Open(category)
            return
        }
        selectedCategory.value = category.categoryId
    }

    fun requestToggleLock(category: CategoryWithCount) {
        if (VirtualCategory.isVirtual(category.categoryId)) return
        pinRequest.value = PinRequest.ToggleLock(category)
    }

    fun dismissPin() {
        pinRequest.value = null
    }

    /** @return true when the PIN is correct. */
    fun submitPin(pin: String): Boolean {
        val request = pinRequest.value ?: return false
        val expected = request.category.pinCode ?: cachedPin
        if (pin != expected) return false
        pinRequest.value = null
        when (request) {
            is PinRequest.Open -> {
                unlocked += request.category.categoryId
                selectedCategory.value = request.category.categoryId
            }
            is PinRequest.ToggleLock -> viewModelScope.launch {
                val lockNow = !request.category.isLocked
                repository.setCategoryLocked(request.category.categoryId, lockNow)
                if (lockNow) unlocked -= request.category.categoryId else unlocked += request.category.categoryId
            }
        }
        return true
    }

    /**
     * Click behaviour: first click previews the channel in the mini-player, a second click
     * on the already-previewed channel opens fullscreen.
     * @return true when the caller should navigate to fullscreen.
     */
    fun onChannelClick(channel: ChannelEntity): Boolean {
        if (previewChannel.value?.streamId == channel.streamId && player.isPlayingMedia(channel.streamId)) return true
        preview(channel)
        return false
    }

    fun preview(channel: ChannelEntity) {
        previewChannel.value = channel
        player.play(
            PlaybackRequest(
                mediaId = channel.streamId,
                url = channel.streamUrl,
                title = channel.name,
                isLive = true,
                userAgent = channel.httpUserAgent,
                referrer = channel.httpReferrer,
                artworkUrl = channel.logoUrl,
            ),
        )
        viewModelScope.launch {
            settings.setLastChannelId(channel.streamId)
            repository.saveProgress(channel.streamId, 0)
        }
    }

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch { repository.toggleFavorite(channel.streamId) }
    }

    fun stopPreview() {
        player.stop()
    }
}
