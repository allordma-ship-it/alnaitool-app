package com.aurastream.tv.ui.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurastream.tv.data.repository.DeviceConflictException
import com.aurastream.tv.data.repository.DeviceRepository
import com.aurastream.tv.data.repository.DeviceSession
import com.aurastream.tv.data.repository.ImportState
import com.aurastream.tv.data.repository.LicenseStatus
import com.aurastream.tv.data.repository.PlaylistRepository
import com.aurastream.tv.data.repository.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ActivationUiState(
    val mac: String = "",
    val session: DeviceSession? = null,
    val loading: Boolean = true,
    val conflict: Boolean = false,
    val expired: Boolean = false,
    val error: String? = null,
    val importing: ImportState.Running? = null,
    val lastCheckAt: Long = 0,
)

/**
 * Pairing screen logic: shows MAC + Device Key + QR and polls the backend until a
 * playlist has been configured remotely through the web portal.
 */
class ActivationViewModel(
    private val devices: DeviceRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivationUiState(mac = devices.macAddress()))
    val state: StateFlow<ActivationUiState> = _state.asStateFlow()

    private val _activated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val activated: SharedFlow<Unit> = _activated

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            playlists.importState.collect { s -> _state.update { it.copy(importing = s as? ImportState.Running) } }
        }
        connect(reset = false)
    }

    fun repair() = connect(reset = true)

    fun checkNow() {
        viewModelScope.launch { pollOnce() }
    }

    private fun connect(reset: Boolean) {
        pollJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val session = devices.handshake(reset)
                _state.update {
                    it.copy(
                        session = session,
                        loading = false,
                        conflict = false,
                        expired = session.status == LicenseStatus.EXPIRED,
                    )
                }
                startPolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceConflictException) {
                _state.update { it.copy(loading = false, conflict = true) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Connection failed") }
                // keep retrying the handshake in the background
                delay(RETRY_MS)
                if (isActive) connect(reset)
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (pollOnce()) return@launch
                delay(POLL_MS)
            }
        }
    }

    /** @return true when activation completed. */
    private suspend fun pollOnce(): Boolean {
        val outcome = playlists.sync()
        _state.update { it.copy(lastCheckAt = System.currentTimeMillis()) }
        return when (outcome) {
            is SyncOutcome.Ready -> {
                _activated.tryEmit(Unit)
                true
            }
            is SyncOutcome.LicenseExpired -> {
                _state.update { it.copy(expired = true) }
                false
            }
            is SyncOutcome.Failed -> {
                _state.update { it.copy(error = outcome.message) }
                false
            }
            else -> {
                _state.update { it.copy(error = null, expired = false) }
                false
            }
        }
    }

    private companion object {
        const val POLL_MS = 8_000L
        const val RETRY_MS = 10_000L
    }
}
