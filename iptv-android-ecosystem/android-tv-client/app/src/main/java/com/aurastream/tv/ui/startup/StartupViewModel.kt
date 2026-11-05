package com.aurastream.tv.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurastream.tv.data.repository.DeviceConflictException
import com.aurastream.tv.data.repository.DeviceRepository
import com.aurastream.tv.data.repository.ImportState
import com.aurastream.tv.data.repository.PlaylistRepository
import com.aurastream.tv.data.repository.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface StartupState {
    data object Connecting : StartupState
    data object Syncing : StartupState
    data object Ready : StartupState
    data object NeedsActivation : StartupState
    data class Error(val message: String) : StartupState
}

/**
 * First-launch orchestration: handshake -> sync -> import -> route to Home or Activation.
 * Falls back to the cached library when the backend is unreachable.
 */
class StartupViewModel(
    private val devices: DeviceRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<StartupState>(StartupState.Connecting)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    val importState: StateFlow<ImportState> = playlists.importState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportState.Idle)

    init {
        start()
    }

    fun start() {
        viewModelScope.launch {
            _state.value = StartupState.Connecting
            try {
                devices.handshake()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceConflictException) {
                _state.value = StartupState.NeedsActivation
                return@launch
            } catch (e: Exception) {
                _state.value = if (playlists.hasLocalData()) StartupState.Ready else StartupState.Error(e.message ?: "Connection failed")
                return@launch
            }

            _state.value = StartupState.Syncing
            _state.value = when (val outcome = playlists.sync()) {
                is SyncOutcome.Ready -> StartupState.Ready
                SyncOutcome.NoPlaylists, is SyncOutcome.LicenseExpired -> StartupState.NeedsActivation
                is SyncOutcome.Offline -> if (outcome.hasLocalData) StartupState.Ready else StartupState.Error(outcome.cause.message ?: "Offline")
                is SyncOutcome.Failed -> if (outcome.hasLocalData) StartupState.Ready else StartupState.Error(outcome.message)
            }
        }
    }
}
