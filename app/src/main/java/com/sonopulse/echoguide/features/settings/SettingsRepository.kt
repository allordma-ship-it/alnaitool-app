package com.sonopulse.echoguide.features.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonopulse.echoguide.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Appearance: follow the system day / night setting, or force light / dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Persisted user settings. [language] null = follow the device (or the Android 13+ per-app language). */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage? = null,
    /** Last successful offline-database verification (epoch ms), 0 = never. */
    val databaseVerifiedAt: Long = 0L,
    /** Summary of that verification, e.g. "10/52/14/40" (views / steps / loops / cases). */
    val databaseSummary: String = ""
)

/** Single DataStore per process; migrates the language from the 1.x SharedPreferences ("echoscope_prefs"). */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "echoscope_settings",
    produceMigrations = { context -> listOf(SharedPreferencesMigration(context, "echoscope_prefs")) }
)

/** Theme, language and offline-database status, stored with Jetpack DataStore (Preferences). */
class SettingsRepository(context: Context) {
    private val app = context.applicationContext
    private val store = app.settingsStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            themeMode = p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            language = AppLanguage.fromTag(p[KEY_LANGUAGE]) ?: systemPerAppLanguage(app),
            databaseVerifiedAt = p[KEY_DB_VERIFIED] ?: 0L,
            databaseSummary = p[KEY_DB_SUMMARY].orEmpty()
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[KEY_THEME] = mode.name }
    }

    /** Saves the in-app language and mirrors it to the Android 13+ per-app language setting. */
    suspend fun setLanguage(language: AppLanguage) {
        store.edit { it[KEY_LANGUAGE] = language.tag }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                app.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(language.tag)
            }
        }
    }

    suspend fun setDatabaseVerified(timeMs: Long, summary: String) {
        store.edit {
            it[KEY_DB_VERIFIED] = timeMs
            it[KEY_DB_SUMMARY] = summary
        }
    }

    private companion object {
        // Same key as the 1.x SharedPreferences so the migration carries the language over.
        val KEY_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_DB_VERIFIED = longPreferencesKey("db_verified_at")
        val KEY_DB_SUMMARY = stringPreferencesKey("db_summary")

        /** Language chosen for this app in Android 13+ system settings (LocaleConfig), if any. */
        fun systemPerAppLanguage(context: Context): AppLanguage? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            val locales = runCatching { context.getSystemService(LocaleManager::class.java)?.applicationLocales }.getOrNull()
            if (locales == null || locales.isEmpty) return null
            return AppLanguage.fromTag(locales[0].language)
        }
    }
}
