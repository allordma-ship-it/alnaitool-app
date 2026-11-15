package com.sonopulse.echoguide

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Clinical ergonomic palette – "Night-Shift / Echo-Suite" dark theme.
 *
 * The ONLY place in the app with colour hex codes. Composables read the semantic tokens through
 * `MaterialTheme.echoColors` (or the short aliases in Theme.kt); drawing code outside composition
 * uses these constants. Backgrounds are charcoal slate, never pure black (OLED smearing).
 */
object EchoPalette {
    // Backgrounds & surfaces
    val BgPrimary = Color(0xFF0F1419)
    val Card = Color(0xFF171E26)
    val CardHigh = Color(0xFF1F2833)
    val Border = Color(0xFF2B3642)

    // Semantic accents
    val MedicalBlue = Color(0xFF1E88E5)   // primary: active windows, navigation, selection
    val Emerald = Color(0xFF00897B)       // normal values, ED marker, real clips
    val Amber = Color(0xFFFB8C00)         // caution, hints, borderline Z-score, simulated clips
    val Crimson = Color(0xFFE53935)       // critical, ES marker, abnormal
    val Sky = Color(0xFF38BDF8)           // informational links (right-heart blue family)

    // Text
    val Text = Color(0xFFF1F5F9)
    val TextMuted = Color(0xFF94A3B8)
    val OnAccent = Color(0xFFFFFFFF)

    /** Light card behind the anatomical atlas illustrations so their dark labels stay readable. */
    val AtlasCard = Color(0xFFE2E8F0)

    // Measurement overlays
    val Caliper = Color(0xFFFFD60A)        // yellow caliper "+-----+"
    val Cursor = Color(0xFF26C6DA)         // Doppler / M-mode cursor
    val Gate = Crimson                     // PW gate, colour box

    // ECG monitor strip
    val EcgTrace = Color(0xFF00E676)
    val EcgPaper = Color(0xFF0A1014)
    val EcgGridMinor = Color(0xFF12261D)
    val EcgGridMajor = Color(0xFF1B3B2C)
    val EcgCursor = Text

    // Ultrasound console hardware look
    val ConsolePanelTop = CardHigh
    val ConsolePanelBottom = Color(0xFF12181F)
    val ConsoleKeyFace = Color(0xFF26313D)
    val ConsoleKeyFaceLow = Color(0xFF1A222B)
    val ConsoleKeyEdge = Border
    val ConsoleKeyLabel = Color(0xFFCBD5E1)
    val ConsoleKnobLight = Color(0xFF3A4757)
    val ConsoleKnobDark = Color(0xFF151C24)
    val ConsoleKnobRim = Color(0xFF0E141A)
    val ConsoleWell = Color(0xFF0C1116)

    // Schematic legend (legacy SVG diagrams)
    val Myocardium = Color(0xFFC2475B)
    val Oxygenated = Color(0xFFE53935)
    val Deoxygenated = Color(0xFF1E88E5)
}

/** Semantic colour tokens of the app theme. */
@Immutable
data class EchoColors(
    val background: Color,
    val card: Color,
    val cardHigh: Color,
    val border: Color,
    val primary: Color,
    val normal: Color,
    val caution: Color,
    val critical: Color,
    val info: Color,
    val text: Color,
    val textMuted: Color,
    val onAccent: Color,
    val atlasCard: Color
)

val DarkEchoColors = EchoColors(
    background = EchoPalette.BgPrimary,
    card = EchoPalette.Card,
    cardHigh = EchoPalette.CardHigh,
    border = EchoPalette.Border,
    primary = EchoPalette.MedicalBlue,
    normal = EchoPalette.Emerald,
    caution = EchoPalette.Amber,
    critical = EchoPalette.Crimson,
    info = EchoPalette.Sky,
    text = EchoPalette.Text,
    textMuted = EchoPalette.TextMuted,
    onAccent = EchoPalette.OnAccent,
    atlasCard = EchoPalette.AtlasCard
)

val LocalEchoColors = staticCompositionLocalOf { DarkEchoColors }

/** `MaterialTheme.echoColors.primary` etc. – the app's semantic colours. */
val MaterialTheme.echoColors: EchoColors
    @Composable
    @ReadOnlyComposable
    get() = LocalEchoColors.current
