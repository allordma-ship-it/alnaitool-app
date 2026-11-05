package com.aurastream.tv.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurastream.tv.data.local.AppSettings
import com.aurastream.tv.data.local.SettingsStore
import com.aurastream.tv.data.local.dao.CategoryWithCount
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.repository.ChannelRepository
import com.aurastream.tv.data.repository.VirtualCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VodUiState(
    val type: String = StreamType.MOVIE,
    val categories: List<CategoryWithCount> = emptyList(),
    val selectedCategoryId: String? = null,
    val items: List<ChannelEntity> = emptyList(),
    val query: String = "",
    val focused: ChannelEntity? = null,
    val pinFor: CategoryWithCount? = null,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class VodViewModel(
    savedState: SavedStateHandle,
    private val repository: ChannelRepository,
    settings: SettingsStore,
    favoritesLabel: String,
    allLabel: String,
) : ViewModel() {

    val type: String = savedState["type"] ?: StreamType.MOVIE

    private val selected = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val focused = MutableStateFlow<ChannelEntity?>(null)
    private val pinFor = MutableStateFlow<CategoryWithCount?>(null)
    private val unlocked = mutableSetOf<String>()
    private var pin = AppSettings.DEFAULT_PIN

    private val categories = repository.categoriesWithVirtual(type, favoritesLabel, allLabel)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val items = combine(selected, query.debounce(250)) { id, q -> id to q }
        .flatMapLatest { (id, q) ->
            when {
                q.length >= 2 -> repository.search(type, q)
                id != null -> repository.channels(id)
                else -> flowOf(emptyList())
            }
        }

    val state: StateFlow<VodUiState> = combine(
        categories,
        combine(selected, query, ::Pair),
        items,
        focused,
        pinFor,
    ) { cats, (sel, q), list, foc, pinCat ->
        VodUiState(type, cats.orEmpty(), sel, list, q, foc, pinCat, loading = cats == null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodUiState(type = type))

    init {
        viewModelScope.launch { settings.settings.collect { pin = it.parentalPin } }
        viewModelScope.launch {
            val cats = categories.filterNotNull().first()
            selected.value = cats.firstOrNull { !VirtualCategory.isVirtual(it.categoryId) && !it.isLocked }?.categoryId
                ?: VirtualCategory.all(type)
        }
    }

    fun select(category: CategoryWithCount) {
        query.value = ""
        if (category.isLocked && category.categoryId !in unlocked) {
            pinFor.value = category
            return
        }
        selected.value = category.categoryId
    }

    fun submitPin(value: String): Boolean {
        val cat = pinFor.value ?: return false
        if (value != (cat.pinCode ?: pin)) return false
        unlocked += cat.categoryId
        pinFor.value = null
        selected.value = cat.categoryId
        return true
    }

    fun dismissPin() {
        pinFor.value = null
    }

    fun setQuery(value: String) {
        query.value = value.take(60)
    }

    fun onFocus(item: ChannelEntity) {
        focused.value = item
    }
}
