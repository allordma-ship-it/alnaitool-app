package com.sonopulse.echoguide

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Languages
// ---------------------------------------------------------------------------------------------

/** Languages supported by EchoScope's UI and clinical database. */
enum class AppLanguage(val tag: String, val nativeName: String, val isRtl: Boolean) {
    DE("de", "Deutsch", false),
    AR("ar", "العربية", true),
    EN("en", "English", false);

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.firstOrNull { it.tag == tag }

        /** Follows the device language when it is German or Arabic, English otherwise. */
        fun fromSystem(configuration: Configuration): AppLanguage {
            val system = configuration.locales.get(0)?.language
            return fromTag(system) ?: EN
        }
    }
}

/** Persists the user's in-app language choice (offline, SharedPreferences). */
object LanguagePreferences {
    private const val PREFS = "echoscope_prefs"
    private const val KEY_LANGUAGE = "app_language"

    fun load(context: Context): AppLanguage? =
        AppLanguage.fromTag(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
        )

    fun save(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.tag)
            .apply()
    }
}

/** Text in the three app languages; German and Arabic fall back to English when absent. */
@Immutable
@Serializable
data class LocalizedText(
    val en: String,
    val de: String? = null,
    val ar: String? = null
) {
    fun get(language: AppLanguage): String = when (language) {
        AppLanguage.DE -> de ?: en
        AppLanguage.AR -> ar ?: en
        AppLanguage.EN -> en
    }
}

// ---------------------------------------------------------------------------------------------
// Protocol model (assets/echo_protocol_full.json)
// ---------------------------------------------------------------------------------------------

@Immutable
@Serializable
data class EchoProtocol(
    val schemaVersion: Int,
    val loopsSimulated: Boolean = false,
    val title: LocalizedText,
    val references: List<String> = emptyList(),
    val disclaimer: LocalizedText,
    val windows: List<AcousticWindow>,
    /** Function of every console key / knob, keyed by control id (e.g. "GAIN", "PW"). */
    val consoleControls: Map<String, LocalizedText> = emptyMap()
) {
    val allViews: List<EchoView> get() = windows.flatMap { it.views }
}

/** One of the four transthoracic acoustic windows (Subcostal, Parasternal, Apical, Suprasternal). */
@Immutable
@Serializable
data class AcousticWindow(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val views: List<EchoView>
)

/** A standard TTE view with its probe instructions, media assets and reference values. */
@Immutable
@Serializable
data class EchoView(
    val id: String,
    val title: LocalizedText,
    val probePosition: LocalizedText,
    val notchClock: String,
    val notchHours: List<Double>,
    val notchDescription: LocalizedText,
    val videoAsset: String,
    val svgAsset: String,
    val keyStructures: List<LocalizedText>,
    val referenceMeasurements: ReferenceMeasurements,
    val checklist: List<LocalizedText>,
    val steps: List<GuideStep> = emptyList(),
    val ecg: EcgTiming = EcgTiming(),
    val timingNote: LocalizedText? = null,
    val machineControls: List<MachineControlStep> = emptyList()
)

/**
 * Cardiac-cycle timing of a view's cine loop: beat length and the offsets of end-diastole
 * (R-wave) and end-systole (T-wave end) inside each beat, all in milliseconds.
 */
@Immutable
@Serializable
data class EcgTiming(
    val beatMs: Long = 833,
    val edMs: Long = 0,
    val esMs: Long = 325
)

/** One ordered step on the ultrasound console for a view. */
@Immutable
@Serializable
data class MachineControlStep(
    val control: String,
    val instruction: LocalizedText
)

/**
 * One step of the guided exam. [caliper] is an optional measurement line
 * `[x1, y1, x2, y2]` in the 400x300 coordinate space of the view's SVG diagram.
 */
@Immutable
@Serializable
data class GuideStep(
    val title: LocalizedText,
    val instruction: LocalizedText,
    val normal: LocalizedText? = null,
    val note: LocalizedText? = null,
    val caliper: List<Float>? = null
)

@Immutable
@Serializable
data class ReferenceMeasurements(
    val adult: List<AdultMeasurement>,
    val pediatric: List<PediatricFocus>
)

@Immutable
@Serializable
data class AdultMeasurement(
    val parameter: LocalizedText,
    val normal: LocalizedText,
    val note: LocalizedText? = null,
    /** Cardiac phase(s) to freeze for this measurement: "ED" and/or "ES". */
    val phases: List<String> = emptyList()
)

@Immutable
@Serializable
data class PediatricFocus(
    val parameter: LocalizedText,
    val focus: LocalizedText
)

/** Result wrapper used by the UI while the bundled JSON is parsed off the main thread. */
sealed interface ProtocolState {
    data object Loading : ProtocolState
    data class Ready(val protocol: EchoProtocol) : ProtocolState
    data class Error(val message: String) : ProtocolState
}

/**
 * Offline repository: everything is read from the APK assets, no network access is ever needed.
 */
object EchoRepository {

    const val PROTOCOL_ASSET = "echo_protocol_full.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    @Volatile
    private var cache: EchoProtocol? = null

    /** Parses the protocol database (cached after the first successful load). */
    suspend fun load(context: Context): ProtocolState = withContext(Dispatchers.IO) {
        cache?.let { return@withContext ProtocolState.Ready(it) }
        try {
            val text = context.applicationContext.assets.open(PROTOCOL_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val protocol = json.decodeFromString(EchoProtocol.serializer(), text)
            cache = protocol
            ProtocolState.Ready(protocol)
        } catch (e: Exception) {
            ProtocolState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Returns true when [assetPath] (relative to `assets/`) is bundled in the APK. */
    fun assetExists(context: Context, assetPath: String): Boolean {
        val slash = assetPath.lastIndexOf('/')
        val dir = if (slash >= 0) assetPath.substring(0, slash) else ""
        val name = assetPath.substring(slash + 1)
        return try {
            context.assets.list(dir)?.contains(name) == true
        } catch (_: Exception) {
            false
        }
    }
}
