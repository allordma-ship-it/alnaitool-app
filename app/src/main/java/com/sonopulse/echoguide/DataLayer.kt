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
    val title: LocalizedText,
    val references: List<String> = emptyList(),
    val disclaimer: LocalizedText,
    val windows: List<AcousticWindow>,
    /** Function of every console key / knob, keyed by control id (e.g. "GAIN", "PW"). */
    val consoleControls: Map<String, LocalizedText> = emptyMap(),
    /** Structured heart-valve assessment (AV, MV, TV, PV). */
    val valves: List<HeartValve> = emptyList(),
    /** Real colour Doppler loops (assets/loops/color), keyed by id. */
    val colorLoops: Map<String, ColorLoop> = emptyMap(),
    val caseLibrary: CaseLibrary? = null,
    /** Real pathology examples (Wikimedia Commons, POCUS Atlas, Echocardiographer.org) for comparison with normal. */
    val pathology: List<PathologyCase> = emptyList()
) {
    /** Colour loops offered by a view's 2D ⇄ colour toggle. */
    fun colorLoopsFor(viewId: String): List<ColorLoop> = colorLoops.values.filter { viewId in it.views }
    fun pathologyFor(viewId: String): List<PathologyCase> = pathology.filter { viewId in it.views }
    fun pathologyForValve(valveId: String): List<PathologyCase> = pathology.filter { valveId in it.valves }
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
    val videoSource: VideoSource = VideoSource(),
    /** Anatomical reference illustration (raster) shown in the Anatomy tab, e.g. `diagrams/apical_4c.png`. */
    val diagramAsset: String? = null,
    /** Width / height of [diagramAsset]. */
    val diagramAspect: Float = 1.15f,
    val diagramCredit: LocalizedText? = null,
    /** Schema tab: medical drawing + labelled echo frame (Echocardiographer.org), paged. */
    val schemaGallery: List<GalleryImage> = emptyList(),
    /** Step-1 torso & transducer illustration (English file; `_de` / `_ar` variants beside it). */
    val orientationAsset: String? = null,
    /** Extra pages of the Sonde panel: probe photo, beam-cut drawing (Echocardiographer.org). */
    val probeGallery: List<GalleryImage> = emptyList(),
    /** Extra pages of the Atlas panel: labelled echo frame, view drawing, zooms. */
    val atlasGallery: List<GalleryImage> = emptyList(),
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
    val esMs: Long = 325,
    /**
     * Explicit R-wave times (ms) for loops with varying beat length (real clips, joined clips).
     * When set, each beat runs from one R-wave to the next and end-systole falls at the same
     * fraction of the beat as `(esMs - edMs) / beatMs`.
     */
    val rWavesMs: List<Long> = emptyList(),
    /** True when the clip has no recorded ECG and the markers follow a nominal rhythm. */
    val nominal: Boolean = false
)

/** Where a view's cine loop comes from; real clips carry the attribution their licence requires. */
@Immutable
@Serializable
data class VideoSource(
    val type: String = "simulated",
    val title: String? = null,
    val author: String? = null,
    val license: String? = null,
    val licenseUrl: String? = null,
    val sourceUrl: String? = null
) {
    val isSimulated: Boolean get() = type != "real"

    /** Where the clip is published (shown in the credit line). */
    val platform: String get() = when {
        sourceUrl.orEmpty().contains("pocusatlas") -> "The POCUS Atlas"
        sourceUrl.orEmpty().contains("wikimedia") -> "Wikimedia Commons"
        else -> sourceUrl.orEmpty()
    }
}

/** One ordered step on the ultrasound console for a view. */
@Immutable
@Serializable
data class MachineControlStep(
    val control: String,
    val instruction: LocalizedText
)

/** Cardiac / respiratory phase a guided step needs on screen. */
object StepPhase {
    /** End-diastole: freeze on the R-wave. */
    const val ED = "ED"
    /** End-systole: freeze at the end of the T-wave. */
    const val ES = "ES"
    /** End-expiration: freeze on [GuideStep.freezeAtMs] (respiration-dependent IVC). */
    const val EXP = "EXP"
    /** Keep the loop playing (orientation, Doppler, colour). */
    const val LIVE = "LIVE"

    val all = setOf(ED, ES, EXP, LIVE)
}

/**
 * Measurement drawn on both the cine-loop frame ([video]) and the atlas illustration ([diagram]).
 * Both are `[x1, y1, x2, y2]` normalised to 0–1 of the respective image.
 *
 * [type]: `LINE` (caliper between two landmarks), `DOPPLER_GATE` (cursor from (x1, y1) to a PW/TDI
 * sample gate at (x2, y2)), `CURSOR` (M-mode / CW line) or `AREA` (colour-Doppler box).
 */
@Immutable
@Serializable
data class CaliperOverlay(
    val type: String,
    val label: String,
    val video: List<Float>? = null,
    val diagram: List<Float>? = null,
    /** The same measurement on the dark Schema view drawing (schema_dark/<view>.png, scripts/schema_overlays.py). */
    val schema: List<Float>? = null
) {
    companion object {
        const val LINE = "LINE"
        const val DOPPLER_GATE = "DOPPLER_GATE"
        const val CURSOR = "CURSOR"
        const val AREA = "AREA"
        val types = setOf(LINE, DOPPLER_GATE, CURSOR, AREA)
    }
}

/**
 * One step of the guided exam. [caliper] is an optional measurement line
 * `[x1, y1, x2, y2]` in the 600x420 coordinate space of the view's SVG schematic;
 * [caliperOverlay] is the same measurement on the real loop and the atlas illustration.
 */
@Immutable
@Serializable
data class GuideStep(
    val title: LocalizedText,
    val instruction: LocalizedText,
    val normal: LocalizedText? = null,
    val note: LocalizedText? = null,
    val caliper: List<Float>? = null,
    /** [StepPhase] the loop is frozen on when this step opens. */
    val requiredPhase: String = StepPhase.LIVE,
    /** Exact frame for [StepPhase.EXP] steps (ms into the loop). */
    val freezeAtMs: Long? = null,
    val caliperOverlay: CaliperOverlay? = null,
    /** Further calipers shown together with [caliperOverlay] (D1 + D2, e' septal + lateral). */
    val extraOverlays: List<CaliperOverlay> = emptyList(),
    /** Real example recordings for this step (PW / CW / TDI / M-mode), opened from the step card. */
    val examples: List<GalleryImage> = emptyList()
) {
    /** Every caliper of the step, [caliperOverlay] first. */
    val overlays: List<CaliperOverlay> get() = listOfNotNull(caliperOverlay) + extraOverlays
}


/** A bundled reference image with caption and the credit its licence requires. */
@Immutable
@Serializable
data class GalleryImage(
    val asset: String,
    val aspect: Float,
    val caption: LocalizedText,
    val credit: String? = null,
    val source: String? = null,
    /** Schema view drawing: the step's calipers / Doppler cursors are drawn on it ([CaliperOverlay.schema]). */
    val calipers: Boolean = false,
    /** Structure names drawn by the app as pills over a vector schema (scale with the font size). */
    val labels: List<SchemaLabel> = emptyList()
)

/** One label of a vector schema: normalised position; [kind] left / right (heart side), valve or other. */
@Immutable
@Serializable
data class SchemaLabel(val text: String, val x: Float, val y: Float, val kind: String = "other")

// ---------------------------------------------------------------------------------------------
// Heart-valve assessment (scripts/valve_content.py; ASE/EACVI 2009 / 2017 recommendations)
// ---------------------------------------------------------------------------------------------

/** One checklist step, optionally linked to the view it is done in. */
@Immutable
@Serializable
data class ValveCheckItem(val text: LocalizedText, val view: String? = null)

@Immutable
@Serializable
data class ValveNormal(val parameter: LocalizedText, val value: LocalizedText)

@Immutable
@Serializable
data class GradeRow(
    val parameter: LocalizedText,
    val mild: LocalizedText,
    val moderate: LocalizedText,
    val severe: LocalizedText
)

@Immutable
@Serializable
data class GradingTable(val title: LocalizedText, val rows: List<GradeRow>, val note: LocalizedText? = null)

@Immutable
@Serializable
data class HeartValve(
    val id: String,
    val title: LocalizedText,
    val short: String,
    /** View ids to image this valve in. */
    val views: List<String>,
    val morphology: List<ValveCheckItem>,
    val color: List<ValveCheckItem>,
    val spectral: List<ValveCheckItem>,
    val normals: List<ValveNormal>,
    val grading: List<GradingTable>,
    val examples: List<GalleryImage> = emptyList(),
    /** Step-by-step measurement guide: cursor on the loop + how to measure on the trace. */
    val measurements: List<ValveMeasurement> = emptyList()
)

/** A real colour Doppler cine loop with its Nyquist limit and credit. */
@Immutable
@Serializable
data class ColorLoop(
    val asset: String,
    val title: String,
    val author: String,
    val license: String,
    val licenseUrl: String? = null,
    val sourceUrl: String,
    val nyquistCmS: Int,
    val views: List<String> = emptyList(),
    /** Valve the colour box covers ("AV", "MV", "TV"). */
    val valve: String = ""
)

/**
 * One measurement of the valve guide: [loop] (2D or colour) with the cursor / gate / caliper
 * [overlay] where it belongs, a schematic [spectral] trace showing how to measure on it, the
 * steps, formula and normal values, and an optional real example recording.
 */
@Immutable
@Serializable
data class ValveMeasurement(
    val id: String,
    val title: LocalizedText,
    val view: String,
    val loop: String,
    val overlay: CaliperOverlay? = null,
    val spectral: String,
    val steps: List<LocalizedText>,
    val formula: LocalizedText? = null,
    val normal: LocalizedText? = null,
    val example: String? = null,
    val nyquistCmS: Int? = null,
    /** Real spectral / M-mode recording with the measurement points marked on it. */
    val trace: SpectralTrace? = null
)

/** A measurement point ("Vmax") or line ("DT", from x/y to x2/y2) on a real trace, normalised. */
@Immutable
@Serializable
data class TraceMark(val label: String, val x: Float, val y: Float, val x2: Float? = null, val y2: Float? = null)

@Immutable
@Serializable
data class SpectralTrace(val asset: String, val aspect: Float, val credit: String, val marks: List<TraceMark>)

/** Echocardiographer.org Image Library: pathology cases (online YouTube videos), grouped. */
@Immutable
@Serializable
data class CaseLibrary(val source: String, val credit: String, val groups: List<CaseGroup>)

@Immutable
@Serializable
data class CaseGroup(val id: String, val title: LocalizedText, val cases: List<LibraryCase>)

@Immutable
@Serializable
data class LibraryCase(val title: String, val url: String)

/** Valve to open first from a view's cockpit. */
fun valveForView(viewId: String): String = when (viewId) {
    "plax_standard", "psax_av", "apical_5c", "suprasternal_arch" -> "aortic"
    "subcostal_4c", "subcostal_ivc" -> "tricuspid"
    else -> "mitral"
}

/** Width / height of the step-1 orientation illustrations (600 x 420). */
const val ORIENTATION_ASPECT = 600f / 420f

/** Language variant of an orientation illustration: `x.svg` → `x_de.svg` / `x_ar.svg`. */
fun localizedOrientationAsset(path: String, language: AppLanguage): String =
    if (language == AppLanguage.EN) path else path.removeSuffix(".svg") + "_${language.tag}.svg"

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

/**
 * One pathology example: real cine loops and still images ([media], each with its own author and
 * licence), the views and valves it belongs to, and what to look for compared with normal.
 */
@Serializable
data class PathologyCase(
    val id: String,
    /** Group key: aortic, mitral, tricuspid, cardiomyopathy, pericardium, aorta, congenital. */
    val group: String,
    val title: LocalizedText,
    val findings: LocalizedText,
    val views: List<String> = emptyList(),
    val valves: List<String> = emptyList(),
    val media: List<CaseMedia> = emptyList(),
    /** Optional further online video (Echocardiographer.org Image Library). */
    val onlineUrl: String? = null
)

/** A loop (assets/pathology, mp4) or still image of a [PathologyCase]. */
@Serializable
data class CaseMedia(
    val asset: String,
    val aspect: Float,
    val caption: LocalizedText,
    val credit: String,
    val license: String,
    val source: String
) {
    val isLoop: Boolean get() = asset.endsWith(".mp4")
}
