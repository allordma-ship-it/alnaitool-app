package com.sonopulse.echoguide

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.1 AA (≥ 4.5 : 1) for every text / badge colour token in both themes. */
class ThemeContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb)).toDouble()
    }

    private fun check(name: String, c: EchoColors) {
        val surfaces = mapOf("background" to c.background, "card" to c.card, "cardHigh" to c.cardHigh)
        val text = mapOf(
            "text" to c.text, "textMuted" to c.textMuted, "primary" to c.primary, "normal" to c.normal,
            "caution" to c.caution, "critical" to c.critical, "info" to c.info
        )
        text.forEach { (t, color) ->
            surfaces.forEach { (s, surface) ->
                val ratio = contrast(color, surface)
                assertTrue("$name: $t on $s = ${"%.2f".format(ratio)} : 1", ratio >= 4.5)
            }
        }
        listOf("primaryFill" to c.primaryFill, "normalFill" to c.normalFill, "cautionFill" to c.cautionFill,
            "criticalFill" to c.criticalFill).forEach { (f, fill) ->
            val ratio = contrast(c.onAccent, fill)
            assertTrue("$name: onAccent on $f = ${"%.2f".format(ratio)} : 1", ratio >= 4.5)
        }
    }

    @Test fun darkThemeMeetsWcagAa() = check("dark", DarkEchoColors)

    @Test fun lightThemeMeetsWcagAa() = check("light", LightEchoColors)

    @Test
    fun numbersAreIsolatedLeftToRight() {
        val s = ltr("-1.8 cm²")
        assertTrue(s.startsWith("⁦") && s.endsWith("⁩") && s.contains("-1.8 cm²"))
    }
}
