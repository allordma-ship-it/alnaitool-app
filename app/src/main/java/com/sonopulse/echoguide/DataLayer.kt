package com.sonopulse.echoguide

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Root of `assets/echo_protocol_full.json`. */
@Immutable
@Serializable
data class EchoProtocol(
    val schemaVersion: Int,
    val titleEn: String,
    val titleAr: String,
    val references: List<String> = emptyList(),
    val disclaimerEn: String = "",
    val disclaimerAr: String = "",
    val windows: List<AcousticWindow>
) {
    val allViews: List<EchoView> get() = windows.flatMap { it.views }
}

/** One of the four transthoracic acoustic windows (Subcostal, Parasternal, Apical, Suprasternal). */
@Immutable
@Serializable
data class AcousticWindow(
    val id: String,
    val titleEn: String,
    val titleAr: String,
    val descriptionEn: String,
    val descriptionAr: String,
    val views: List<EchoView>
)

/** A standard TTE view with its probe instructions, media assets and reference values. */
@Immutable
@Serializable
data class EchoView(
    val id: String,
    val titleEn: String,
    val titleAr: String,
    val probePositionEn: String,
    val probePositionAr: String,
    val notchClock: String,
    val notchHours: List<Double>,
    val notchDescriptionEn: String,
    val notchDescriptionAr: String,
    val videoAsset: String,
    val svgAsset: String,
    val keyStructures: List<String>,
    val referenceMeasurements: ReferenceMeasurements,
    val checklist: List<String>
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
    val parameter: String,
    val normal: String,
    val note: String = ""
)

@Immutable
@Serializable
data class PediatricFocus(
    val parameter: String,
    val focus: String
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
