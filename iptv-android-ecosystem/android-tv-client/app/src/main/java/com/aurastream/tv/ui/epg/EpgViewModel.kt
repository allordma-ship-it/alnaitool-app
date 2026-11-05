package com.aurastream.tv.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurastream.tv.data.local.dao.CategoryWithCount
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.local.entity.EpgEntity
import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.repository.ChannelRepository
import com.aurastream.tv.data.repository.VirtualCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class EpgUiState(
    val categories: List<CategoryWithCount> = emptyList(),
    val selectedCategoryId: String? = null,
    val channels: List<ChannelEntity> = emptyList(),
    val selectedChannel: ChannelEntity? = null,
    val schedule: List<EpgEntity> = emptyList(),
)

/** Simple per-channel programme guide: categories → channels → schedule (now − 2 h … now + 24 h). */
@OptIn(ExperimentalCoroutinesApi::class)
class EpgViewModel(
    private val repository: ChannelRepository,
    favoritesLabel: String,
    allLabel: String,
) : ViewModel() {

    private val selectedCategory = MutableStateFlow(VirtualCategory.all(StreamType.LIVE))
    private val selectedChannel = MutableStateFlow<ChannelEntity?>(null)

    private val categories = repository.categoriesWithVirtual(StreamType.LIVE, favoritesLabel, allLabel)
        .map { list -> list.filter { !it.isLocked } }

    private val channels = selectedCategory.flatMapLatest { repository.channels(it) }
        .map { list -> list.filter { !it.epgChannelId.isNullOrBlank() } }

    private val schedule = selectedChannel.flatMapLatest { ch ->
        val id = ch?.epgChannelId
        if (id.isNullOrBlank()) {
            flowOf(emptyList())
        } else {
            val now = System.currentTimeMillis()
            repository.schedule(id, now - 2 * HOUR, now + 24 * HOUR)
        }
    }

    val state: StateFlow<EpgUiState> = combine(categories, selectedCategory, channels, selectedChannel, schedule) { cats, catId, list, ch, sched ->
        EpgUiState(cats, catId, list, ch ?: list.firstOrNull(), sched)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpgUiState())

    fun selectCategory(id: String) {
        selectedCategory.value = id
        selectedChannel.value = null
    }

    fun selectChannel(channel: ChannelEntity) {
        selectedChannel.value = channel
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
