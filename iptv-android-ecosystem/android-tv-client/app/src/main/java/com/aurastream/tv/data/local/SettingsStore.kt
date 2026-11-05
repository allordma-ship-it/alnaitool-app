package com.aurastream.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.auraDataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_settings")

data class AppSettings(
    val userAgent: String = DEFAULT_USER_AGENT,
    val preferredFormat: StreamFormat = StreamFormat.TS,
    val aspectMode: AspectMode = AspectMode.FIT,
    val activePlaylistId: String? = null,
    val parentalPin: String = DEFAULT_PIN,
    val autoRecovery: Boolean = true,
    val lastChannelId: String? = null,
    val lastSyncAt: Long = 0,
    val playlistsVersion: String? = null,
    val importedPlaylistKey: String? = null,
    val playlistsJson: String? = null,
) {
    companion object {
        const val DEFAULT_USER_AGENT = "AuraStream/1.0 (Linux; Android TV) ExoPlayer/Media3"
        const val DEFAULT_PIN = "0000"
    }
}

/** Typed facade over Jetpack DataStore. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val userAgent = stringPreferencesKey("user_agent")
        val preferredFormat = stringPreferencesKey("preferred_format")
        val aspectMode = stringPreferencesKey("aspect_mode")
        val activePlaylistId = stringPreferencesKey("active_playlist_id")
        val parentalPin = stringPreferencesKey("parental_pin")
        val autoRecovery = booleanPreferencesKey("auto_recovery")
        val lastChannelId = stringPreferencesKey("last_channel_id")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val playlistsVersion = stringPreferencesKey("playlists_version")
        val importedPlaylistKey = stringPreferencesKey("imported_playlist_key")
        val playlistsJson = stringPreferencesKey("playlists_json")
    }

    val settings: Flow<AppSettings> = context.auraDataStore.data
        .map { p ->
            AppSettings(
                userAgent = p[Keys.userAgent]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_USER_AGENT,
                preferredFormat = p[Keys.preferredFormat].toEnum(StreamFormat.TS),
                aspectMode = p[Keys.aspectMode].toEnum(AspectMode.FIT),
                activePlaylistId = p[Keys.activePlaylistId],
                parentalPin = p[Keys.parentalPin] ?: AppSettings.DEFAULT_PIN,
                autoRecovery = p[Keys.autoRecovery] ?: true,
                lastChannelId = p[Keys.lastChannelId],
                lastSyncAt = p[Keys.lastSyncAt] ?: 0L,
                playlistsVersion = p[Keys.playlistsVersion],
                importedPlaylistKey = p[Keys.importedPlaylistKey],
                playlistsJson = p[Keys.playlistsJson],
            )
        }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun setUserAgent(value: String) = edit { it[Keys.userAgent] = value.trim().take(256) }
    suspend fun setPreferredFormat(value: StreamFormat) = edit { it[Keys.preferredFormat] = value.name }
    suspend fun setAspectMode(value: AspectMode) = edit { it[Keys.aspectMode] = value.name }
    suspend fun setActivePlaylistId(value: String?) = edit { prefs ->
        if (value == null) prefs.remove(Keys.activePlaylistId) else prefs[Keys.activePlaylistId] = value
    }
    suspend fun setParentalPin(value: String) = edit { it[Keys.parentalPin] = value }
    suspend fun setAutoRecovery(value: Boolean) = edit { it[Keys.autoRecovery] = value }
    suspend fun setLastChannelId(value: String) = edit { it[Keys.lastChannelId] = value }

    suspend fun saveSyncResult(version: String, playlistsJson: String) = edit {
        it[Keys.playlistsVersion] = version
        it[Keys.playlistsJson] = playlistsJson
        it[Keys.lastSyncAt] = System.currentTimeMillis()
    }

    suspend fun setImportedPlaylistKey(value: String?) = edit { prefs ->
        if (value == null) prefs.remove(Keys.importedPlaylistKey) else prefs[Keys.importedPlaylistKey] = value
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.auraDataStore.edit { block(it) }
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
}
