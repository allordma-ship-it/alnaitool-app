package com.aurastream.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.remote.dto.RemotePlaylist
import com.aurastream.tv.data.repository.ChannelRepository
import com.aurastream.tv.data.repository.DeviceRepository
import com.aurastream.tv.data.repository.DeviceSession
import com.aurastream.tv.data.repository.ImportState
import com.aurastream.tv.data.repository.PlaylistRepository
import com.aurastream.tv.data.repository.SyncOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val session: DeviceSession? = null,
    val playlist: RemotePlaylist? = null,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val recent: List<ChannelEntity> = emptyList(),
    val importing: ImportState.Running? = null,
    val refreshing: Boolean = false,
    val message: String? = null,
)

class HomeViewModel(
    devices: DeviceRepository,
    private val playlists: PlaylistRepository,
    channels: ChannelRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val _needsActivation = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val needsActivation: SharedFlow<Unit> = _needsActivation

    private val counts = combine(
        channels.count(StreamType.LIVE),
        channels.count(StreamType.MOVIE),
        channels.count(StreamType.SERIES),
    ) { l, m, s -> Triple(l, m, s) }

    val state: StateFlow<HomeUiState> = combine(
        combine(devices.session, playlists.activePlaylist, ::Pair),
        counts,
        channels.recent(),
        playlists.importState,
        combine(refreshing, message, ::Pair),
    ) { (session, playlist), (live, movies, series), recent, import, (busy, msg) ->
        HomeUiState(
            session = session,
            playlist = playlist,
            liveCount = live,
            movieCount = movies,
            seriesCount = series,
            recent = recent,
            importing = import as? ImportState.Running,
            refreshing = busy,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        // Pick up playlist changes made in the web portal while the TV is idling on Home.
        viewModelScope.launch {
            while (true) {
                delay(AUTO_SYNC_INTERVAL_MS)
                if (!refreshing.value) playlists.sync(forceImport = false)
            }
        }
    }

    fun refresh(force: Boolean = false) {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            message.value = null
            when (val outcome = playlists.sync(forceImport = force)) {
                is SyncOutcome.LicenseExpired, SyncOutcome.NoPlaylists -> _needsActivation.tryEmit(Unit)
                is SyncOutcome.Failed -> message.value = outcome.message
                is SyncOutcome.Offline -> message.value = outcome.cause.message
                is SyncOutcome.Ready -> Unit
            }
            refreshing.value = false
        }
    }

    private companion object {
        const val AUTO_SYNC_INTERVAL_MS = 10 * 60 * 1000L
    }
}
