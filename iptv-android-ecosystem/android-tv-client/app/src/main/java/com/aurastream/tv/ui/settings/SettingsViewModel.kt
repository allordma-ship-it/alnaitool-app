package com.aurastream.tv.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurastream.tv.data.local.AppSettings
import com.aurastream.tv.data.local.AspectMode
import com.aurastream.tv.data.local.SettingsStore
import com.aurastream.tv.data.local.StreamFormat
import com.aurastream.tv.data.remote.dto.RemotePlaylist
import com.aurastream.tv.data.repository.DeviceRepository
import com.aurastream.tv.data.repository.DeviceSession
import com.aurastream.tv.data.repository.ImportState
import com.aurastream.tv.data.repository.PlaylistRepository
import com.aurastream.tv.data.repository.SyncOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppLanguage(val tag: String) { SYSTEM(""), ENGLISH("en"), ARABIC("ar") }

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val session: DeviceSession? = null,
    val mac: String = "",
    val playlists: List<RemotePlaylist> = emptyList(),
    val activePlaylistId: String? = null,
    val importing: ImportState.Running? = null,
    val message: String? = null,
    val language: AppLanguage = AppLanguage.SYSTEM,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val devices: DeviceRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val language = MutableStateFlow(currentLanguage())

    val state: StateFlow<SettingsUiState> = combine(
        store.settings,
        devices.session,
        playlists.playlists,
        playlists.importState,
        combine(message, language, ::Pair),
    ) { s, session, list, import, (msg, lang) ->
        SettingsUiState(
            settings = s,
            session = session,
            mac = devices.macAddress(),
            playlists = list,
            activePlaylistId = s.activePlaylistId ?: list.firstOrNull()?.id,
            importing = import as? ImportState.Running,
            message = msg,
            language = lang,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(mac = devices.macAddress()))

    init {
        // Refresh licence information when settings are opened (best effort).
        viewModelScope.launch { runCatching { devices.handshake() } }
    }

    fun setFormat(format: StreamFormat) = launch { store.setPreferredFormat(format) }

    fun cycleAspect() = launch {
        val modes = AspectMode.entries
        store.setAspectMode(modes[(modes.indexOf(state.value.settings.aspectMode) + 1) % modes.size])
    }

    fun setAutoRecovery(enabled: Boolean) = launch { store.setAutoRecovery(enabled) }

    fun setUserAgent(value: String) = launch { store.setUserAgent(value.ifBlank { AppSettings.DEFAULT_USER_AGENT }) }

    fun resetUserAgent() = launch { store.setUserAgent(AppSettings.DEFAULT_USER_AGENT) }

    fun checkPin(pin: String): Boolean = pin == state.value.settings.parentalPin

    fun setPin(pin: String) = launch { store.setParentalPin(pin) }

    fun selectPlaylist(id: String) = launch { report(playlists.selectPlaylist(id)) }

    fun refreshNow() = launch { report(playlists.sync(forceImport = true)) }

    fun refreshEpg() = launch {
        runCatching { playlists.refreshEpg() }.onFailure { message.value = it.message }
    }

    fun setLanguage(lang: AppLanguage) {
        language.value = lang
        val locales = if (lang == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(lang.tag)
        AppCompatDelegate.setApplicationLocales(locales) // recreates the activity with the new locale / layout direction
    }

    fun clearMessage() {
        message.value = null
    }

    private fun report(outcome: SyncOutcome) {
        message.value = when (outcome) {
            is SyncOutcome.Ready -> null
            is SyncOutcome.Failed -> outcome.message
            is SyncOutcome.Offline -> outcome.cause.message
            is SyncOutcome.LicenseExpired -> "License expired"
            SyncOutcome.NoPlaylists -> "No playlists configured"
        }
    }

    private fun currentLanguage(): AppLanguage {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when {
            tag.startsWith("ar") -> AppLanguage.ARABIC
            tag.startsWith("en") -> AppLanguage.ENGLISH
            else -> AppLanguage.SYSTEM
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
