package com.sonopulse.echoguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EchoCalcTest {

    @Test
    fun continuityEquation() {
        // LVOT 2.0 cm → area 3.14 cm²; SV = 3.14 × 20 = 62.8 ml; AVA = 62.8 / 80 = 0.785 cm²
        val r = EchoCalc.continuity(2.0, 20.0, 80.0)
        assertEquals(3.1416, r.lvotAreaCm2, 1e-3)
        assertEquals(62.83, r.strokeVolumeMl, 0.01)
        assertEquals(0.785, r.avaCm2, 0.001)
        assertEquals(0.25, r.dvi, 1e-9)
        assertEquals(Severity.SEVERE, r.severity)
    }

    @Test
    fun avaGrades() {
        assertEquals(Severity.NORMAL, EchoCalc.avaSeverity(2.5))
        assertEquals(Severity.MILD, EchoCalc.avaSeverity(1.8))
        assertEquals(Severity.MILD, EchoCalc.avaSeverity(1.5))
        assertEquals(Severity.MODERATE, EchoCalc.avaSeverity(1.2))
        assertEquals(Severity.SEVERE, EchoCalc.avaSeverity(0.8))
    }

    @Test
    fun rightAtrialPressureFromTheIvc() {
        assertEquals(3, EchoCalc.rightAtrialPressure(1.8, 60.0))
        assertEquals(15, EchoCalc.rightAtrialPressure(2.5, 30.0))
        assertEquals(8, EchoCalc.rightAtrialPressure(1.8, 30.0))
        assertEquals(8, EchoCalc.rightAtrialPressure(2.5, 60.0))
    }

    @Test
    fun rvsp() {
        val r = EchoCalc.rvsp(3.0, 2.5, 30.0)          // 4·9 = 36 + 15
        assertEquals(36.0, r.gradientMmHg, 1e-9)
        assertEquals(51.0, r.rvspMmHg, 1e-9)
        assertEquals(Severity.MODERATE, r.phProbability)
        assertEquals(Severity.NORMAL, EchoCalc.rvsp(2.5, 1.5, 70.0).phProbability)
        assertEquals(Severity.SEVERE, EchoCalc.rvsp(3.8, 1.5, 70.0).phProbability)
    }

    @Test
    fun diastolicGrades() {
        // Impaired relaxation: E/A 0.6, E 45
        assertEquals(1, EchoCalc.diastolic(45.0, 75.0, 6.0, 8.0).grade)
        // Restrictive: E/A ≥ 2
        assertEquals(3, EchoCalc.diastolic(110.0, 45.0, 5.0, 7.0).grade)
        // Pseudonormal: E/A 1.3, E/e' avg 100/6 = 16.7 > 14, TR 3.0 > 2.8 → 2 of 2 positive
        val pseudo = EchoCalc.diastolic(100.0, 77.0, 5.0, 7.0, trVmax = 3.0)
        assertEquals(16.67, pseudo.eOverEPrimeAverage, 0.01)
        assertEquals(2, pseudo.grade)
        // Normal-range E/A with only E/e' known → indeterminate
        assertNull(EchoCalc.diastolic(80.0, 70.0, 10.0, 14.0).grade)
        // 3 criteria, only 1 positive → grade I
        assertEquals(1, EchoCalc.diastolic(80.0, 70.0, 5.0, 5.0, trVmax = 2.5, lavi = 30.0).grade)
    }

    @Test
    fun mitralValveAreaFromPht() {
        val r = EchoCalc.mvaPht(220.0)
        assertEquals(1.0, r.mvaCm2, 1e-9)
        assertEquals(Severity.MODERATE, r.severity)
        assertEquals(Severity.SEVERE, EchoCalc.mvaPht(300.0).severity)
        assertEquals(Severity.MILD, EchoCalc.mvaPht(100.0).severity)
    }
}
