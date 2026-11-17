package com.sonopulse.echoguide

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaliperGeometryTest {

    @Test
    fun wideContainerLetterboxesLeftAndRight() {
        val r = fitRect(Size(400f, 200f), 4f / 3f)
        assertEquals(266.67f, r.width, 0.01f)
        assertEquals(200f, r.height, 0.01f)
        assertEquals((400f - r.width) / 2f, r.left, 0.01f)
        assertEquals(0f, r.top, 0.01f)
    }

    @Test
    fun tallContainerLetterboxesTopAndBottom() {
        val r = fitRect(Size(300f, 600f), 1.5f)
        assertEquals(300f, r.width, 0.01f)
        assertEquals(200f, r.height, 0.01f)
        assertEquals(200f, r.top, 0.01f)
    }

    @Test
    fun unknownAspectFillsContainer() {
        val r = fitRect(Size(120f, 80f), 0f)
        assertEquals(120f, r.width, 0.01f)
        assertEquals(80f, r.height, 0.01f)
    }

    @Test
    fun normalizedBoxValidation() {
        assertTrue(isNormalizedBox(listOf(0f, 0.5f, 1f, 0.25f)))
        assertFalse(isNormalizedBox(listOf(0f, 0.5f, 1.2f, 0.25f)))
        assertFalse(isNormalizedBox(listOf(0f, 0.5f, 1f)))
        assertFalse(isNormalizedBox(null))
    }
}
