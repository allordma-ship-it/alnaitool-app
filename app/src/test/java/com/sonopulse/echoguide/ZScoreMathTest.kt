package com.sonopulse.echoguide

import org.junit.Assert.assertEquals
import org.junit.Test

class ZScoreMathTest {

    @Test
    fun haycockBsaMatchesReferenceValues() {
        // 70 kg / 175 cm adult ≈ 1.85 m²; 10 kg / 75 cm infant ≈ 0.46 m²
        assertEquals(1.85, ZScoreMath.haycockBsa(70.0, 175.0), 0.01)
        assertEquals(0.46, ZScoreMath.haycockBsa(10.0, 75.0), 0.01)
    }

    @Test
    fun zScoreIsZeroAtPredictedMeanAndSymmetricInLogSpace() {
        val bsa = 1.0
        CardiacStructure.entries.forEach { s ->
            val mean = s.predictedMeanMm(bsa)
            assertEquals(0.0, s.zScore(mean, bsa), 1e-9)
            assertEquals(2.0, s.zScore(s.valueAtZ(2.0, bsa), bsa), 1e-9)
            assertEquals(-2.0, s.zScore(s.valueAtZ(-2.0, bsa), bsa), 1e-9)
        }
    }

    @Test
    fun interpretationThresholds() {
        assertEquals(ZInterpretation.NORMAL, ZScoreMath.interpret(2.0))
        assertEquals(ZInterpretation.NORMAL, ZScoreMath.interpret(-1.5))
        assertEquals(ZInterpretation.BORDERLINE, ZScoreMath.interpret(2.01))
        assertEquals(ZInterpretation.BORDERLINE, ZScoreMath.interpret(-3.0))
        assertEquals(ZInterpretation.SIGNIFICANT, ZScoreMath.interpret(3.01))
    }

    @Test
    fun arabicAndGermanNumberInputIsNormalized() {
        assertEquals("12.5", normalizeNumberInput("١٢٫٥"))   // Arabic-Indic digits + Arabic decimal separator
        assertEquals("12.5", normalizeNumberInput("۱۲.۵"))   // Eastern Arabic-Indic digits
        assertEquals("12.5", normalizeNumberInput("12,5"))   // German decimal comma
        assertEquals("70", normalizeNumberInput("70"))
    }
}
