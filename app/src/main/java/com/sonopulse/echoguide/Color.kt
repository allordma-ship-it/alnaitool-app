package com.sonopulse.echoguide

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Clinical ergonomic palette: brand hues plus the instrument surfaces (cine, ECG paper, console),
 * which stay dark in both themes like a real ultrasound system.
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
    val BeamPw = Color(0xFFFF5252)         // PW Doppler beam (red)
    val BeamCw = Color(0xFFFF9100)         // CW Doppler beam (orange)
    val BeamMMode = Color(0xFF448AFF)      // M-mode beam (blue)

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

/**
 * Semantic colour tokens of the app theme.
 *
 * Text / stroke tokens ([primary], [normal], [caution], [critical], [info], [text], [textMuted])
 * reach WCAG AA (≥ 4.5 : 1) on [background], [card] and [cardHigh] of their theme – checked by
 * ThemeContrastTest. The brand hues (#1E88E5, #00897B, #FB8C00, #E53935) stay the identity, but
 * as text they score only 3.3–4.1 : 1 on dark cards and 2.1–3.8 : 1 on white, so each theme uses
 * the nearest tint / shade of the same hue that passes. The *Fill tokens are badge / chip
 * backgrounds that carry [onAccent] white text at ≥ 4.5 : 1 in both themes.
 */
@Immutable
data class EchoColors(
    val isDark: Boolean,
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
    val atlasCard: Color,
    val primaryFill: Color,
    val normalFill: Color,
    val cautionFill: Color,
    val criticalFill: Color
)

/** Badge / chip fills shared by both themes (white text ≥ 5 : 1). */
private val PrimaryFill = Color(0xFF1565C0)
private val NormalFill = Color(0xFF00796B)
private val CautionFill = Color(0xFFB45309)
private val CriticalFill = Color(0xFFC62828)

/** Dark mode: #0F1419 canvas, off-white #F1F5F9 text. */
val DarkEchoColors = EchoColors(
    isDark = true,
    background = EchoPalette.BgPrimary,
    card = EchoPalette.Card,
    cardHigh = EchoPalette.CardHigh,
    border = EchoPalette.Border,
    primary = Color(0xFF42A5F5),     // 5.6 : 1 on #171E26 (brand #1E88E5: 4.0)
    normal = Color(0xFF26A69A),      // 5.0 : 1 (brand #00897B: 3.5)
    caution = EchoPalette.Amber,     // 6.3 : 1
    critical = Color(0xFFFF6B66),    // 5.4 : 1 (brand #E53935: 3.5)
    info = EchoPalette.Sky,          // 7.0 : 1
    text = EchoPalette.Text,
    textMuted = EchoPalette.TextMuted,
    onAccent = EchoPalette.OnAccent,
    atlasCard = EchoPalette.AtlasCard,
    primaryFill = PrimaryFill,
    normalFill = NormalFill,
    cautionFill = CautionFill,
    criticalFill = CriticalFill
)

/** Light mode (daylight / ward): #F8FAFC canvas, slate #171E26 text, high-contrast borders. */
val LightEchoColors = EchoColors(
    isDark = false,
    background = Color(0xFFF8FAFC),
    card = Color(0xFFFFFFFF),
    cardHigh = Color(0xFFEEF2F6),
    border = Color(0xFF94A3B8),
    primary = Color(0xFF1565C0),     // 5.1 : 1 on #F8FAFC
    normal = Color(0xFF00695C),      // 5.9 : 1
    caution = Color(0xFF9A4D00),     // 5.4 : 1
    critical = Color(0xFFC62828),    // 5.0 : 1
    info = Color(0xFF0369A1),        // 5.3 : 1
    text = Color(0xFF171E26),        // 14.9 : 1
    textMuted = Color(0xFF516072),   // 5.7 : 1
    onAccent = Color(0xFFFFFFFF),
    atlasCard = Color(0xFFF1F5F9),
    primaryFill = PrimaryFill,
    normalFill = NormalFill,
    cautionFill = CautionFill,
    criticalFill = CriticalFill
)

val LocalEchoColors = staticCompositionLocalOf { DarkEchoColors }

/** `MaterialTheme.echoColors.primary` etc. – the app's semantic colours. */
val MaterialTheme.echoColors: EchoColors
    @Composable
    @ReadOnlyComposable
    get() = LocalEchoColors.current
