package com.sonopulse.echoguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcgTimingTest {

    private val timing = EcgTiming(beatMs = 833, edMs = 0, esMs = 325)

    @Test
    fun jumpToEndDiastoleGoesToTheRWaveOfTheCurrentBeat() {
        assertEquals(0L, phaseJumpTarget(400, 1666, timing, endSystole = false))
        assertEquals(833L, phaseJumpTarget(1200, 1666, timing, endSystole = false))
    }

    @Test
    fun jumpToEndSystoleGoesToTheTWaveEndOfTheCurrentBeat() {
        assertEquals(325L, phaseJumpTarget(100, 1666, timing, endSystole = true))
        assertEquals(833L + 325L, phaseJumpTarget(900, 1666, timing, endSystole = true))
    }

    @Test
    fun jumpStaysInsideTheClip() {
        // ES of a partial last beat would be past the end → previous beat's ES
        assertEquals(325L, phaseJumpTarget(900, 1000, timing, endSystole = true))
        // a position reported beyond the clip still lands on a real R-wave
        assertEquals(833L, phaseJumpTarget(5000, 1666, timing, endSystole = false))
    }

    @Test
    fun waveformPeaksAtTheRWave() {
        val tEnd = 325.0 / 833.0
        val r = ecgAmplitude(0.0, tEnd)
        listOf(0.1, 0.3, 0.5, 0.88).forEach { assertTrue(ecgAmplitude(it, tEnd) < r) }
        assertTrue(ecgAmplitude(tEnd - 0.09, tEnd) > ecgAmplitude(0.6, tEnd)) // T wave above baseline
    }

    @Test
    fun explicitRWavesDefineIrregularBeats() {
        // two joined clips: 3 beats of 595 ms, then 3 beats of 673 ms
        val joined = EcgTiming(beatMs = 595, edMs = 0, esMs = 292, rWavesMs = listOf(0, 595, 1190, 1785, 2458, 3131))
        val beats = beatsFor(joined, 3804)
        assertEquals(6, beats.size)
        assertEquals(673L, beats[4].lengthMs)
        assertEquals(2458L, phaseJumpTarget(2600, 3804, joined, endSystole = false))
        assertEquals(2458L + Math.round(673 * 292.0 / 595), phaseJumpTarget(2600, 3804, joined, endSystole = true))
    }
}
