package com.sonopulse.echoguide.features.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonopulse.echoguide.AppLanguage
import com.sonopulse.echoguide.EchoRepository
import com.sonopulse.echoguide.ProtocolState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Result of checking the bundled offline database (protocol JSON + every referenced asset). */
sealed interface DatabaseStatus {
    data object Checking : DatabaseStatus
    data class Active(
        val views: Int,
        val steps: Int,
        val loops: Int,
        val pathologyCases: Int,
        val valves: Int,
        val schemaVersion: Int,
        val verifiedAt: Long
    ) : DatabaseStatus
    data class Error(val message: String) : DatabaseStatus
}

data class SettingsUiState(
    val settings: AppSettings,
    val database: DatabaseStatus
)

/**
 * Settings & info: theme mode, language (instant, no restart) and the offline-database status.
 * The first settings value is read synchronously from the tiny DataStore file so the app never
 * flashes the wrong language or theme on launch.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val database = MutableStateFlow<DatabaseStatus>(DatabaseStatus.Checking)

    private val initial: AppSettings = runBlocking { repository.settings.first() }

    val uiState: StateFlow<SettingsUiState> = combine(repository.settings, database) { s, db -> SettingsUiState(s, db) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(initial, DatabaseStatus.Checking))

    init {
        verifyDatabase()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { repository.setLanguage(language) }
    }

    /** Parses the protocol and checks that every loop, drawing and case it references is bundled. */
    fun verifyDatabase() {
        database.value = DatabaseStatus.Checking
        viewModelScope.launch {
            val app = getApplication<Application>()
            database.value = when (val state = EchoRepository.load(app)) {
                is ProtocolState.Error -> DatabaseStatus.Error(state.message)
                ProtocolState.Loading -> DatabaseStatus.Checking
                is ProtocolState.Ready -> withContext(Dispatchers.IO) {
                    val p = state.protocol
                    val loops = (p.allViews.map { it.videoAsset } + p.colorLoops.values.map { it.asset } +
                        p.pathology.mapNotNull { it.loop }).distinct()
                    val images = (p.allViews.flatMap { v -> v.schemaGallery.map { it.asset } + v.steps.flatMap { s -> s.examples.map { it.asset } } } +
                        p.pathology.flatMap { c -> c.images.map { it.asset } }).distinct()
                    val missing = (loops + images).filterNot { EchoRepository.assetExists(app, it) }
                    if (missing.isNotEmpty()) {
                        DatabaseStatus.Error("${missing.size} missing: ${missing.take(3).joinToString()}")
                    } else {
                        val now = System.currentTimeMillis()
                        val status = DatabaseStatus.Active(
                            views = p.allViews.size,
                            steps = p.allViews.sumOf { it.steps.size },
                            loops = loops.size,
                            pathologyCases = p.pathology.size,
                            valves = p.valves.size,
                            schemaVersion = p.schemaVersion,
                            verifiedAt = now
                        )
                        repository.setDatabaseVerified(now, "${status.views}/${status.steps}/${status.loops}/${status.pathologyCases}")
                        status
                    }
                }
            }
        }
    }
}
