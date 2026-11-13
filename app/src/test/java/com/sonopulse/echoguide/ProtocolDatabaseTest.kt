package com.sonopulse.echoguide

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class ProtocolDatabaseTest {

    companion object {
        private val assetsDir = listOf("src/main/assets", "app/src/main/assets")
            .map(::File)
            .first { it.isDirectory }

        private val json = Json { ignoreUnknownKeys = true }

        private lateinit var protocol: EchoProtocol

        @JvmStatic
        @BeforeClass
        fun loadProtocol() {
            val text = File(assetsDir, EchoRepository.PROTOCOL_ASSET).readText(Charsets.UTF_8)
            protocol = json.decodeFromString(EchoProtocol.serializer(), text)
        }

        private val expectedViews = listOf(
            "subcostal_4c", "subcostal_ivc", "plax_standard", "psax_av", "psax_papillary",
            "apical_4c", "apical_5c", "apical_2c_3c", "suprasternal_arch"
        )
    }

    @Test
    fun hasFourWindowsAndNineViewsInOrder() {
        assertEquals(listOf("subcostal", "parasternal", "apical", "suprasternal"), protocol.windows.map { it.id })
        assertEquals(expectedViews, protocol.allViews.map { it.id })
    }

    @Test
    fun everyViewPointsToBundledSvgAndLoop() {
        protocol.allViews.forEach { view ->
            assertEquals("loops/${view.id}.mp4", view.videoAsset)
            assertEquals("svg/${view.id}.svg", view.svgAsset)
            assertTrue("missing ${view.svgAsset}", File(assetsDir, view.svgAsset).isFile)
            assertTrue("missing ${view.videoAsset}", File(assetsDir, view.videoAsset).isFile)
        }
    }

    @Test
    fun descriptiveTextIsTranslatedToGermanAndArabic() {
        val texts = buildList {
            add(protocol.title)
            add(protocol.disclaimer)
            protocol.windows.forEach { add(it.title); add(it.description) }
            protocol.allViews.forEach { v ->
                add(v.title); add(v.probePosition); add(v.notchDescription)
                addAll(v.keyStructures); addAll(v.checklist)
                v.referenceMeasurements.pediatric.forEach { add(it.focus) }
            }
        }
        texts.forEach { t ->
            assertFalse("German missing for: ${t.en}", t.de.isNullOrBlank())
            assertFalse("Arabic missing for: ${t.en}", t.ar.isNullOrBlank())
        }
    }

    @Test
    fun everyViewHasReferenceDataAndChecklist() {
        protocol.allViews.forEach { v ->
            assertTrue(v.referenceMeasurements.adult.isNotEmpty())
            assertTrue(v.referenceMeasurements.pediatric.isNotEmpty())
            assertTrue(v.checklist.size >= 5)
            assertTrue(v.notchHours.isNotEmpty())
        }
    }

    @Test
    fun guidedExamHasTranslatedStepsWithValidCalipers() {
        val steps = protocol.allViews.flatMap { it.steps }
        assertEquals(45, steps.size)
        protocol.allViews.forEach { assertTrue("no steps for ${it.id}", it.steps.isNotEmpty()) }
        steps.forEach { step ->
            listOfNotNull(step.title, step.instruction, step.normal, step.note).forEach { t ->
                assertFalse("German missing for: ${t.en}", t.de.isNullOrBlank())
                assertFalse("Arabic missing for: ${t.en}", t.ar.isNullOrBlank())
            }
            step.caliper?.let { c ->
                assertEquals("caliper needs 4 values: ${step.title.en}", 4, c.size)
                assertTrue(c[0] in 0f..SVG_WIDTH && c[2] in 0f..SVG_WIDTH)
                assertTrue(c[1] in 0f..SVG_HEIGHT && c[3] in 0f..SVG_HEIGHT)
            }
        }
    }

    @Test
    fun consoleStepsUseKnownControlsAndAreTranslated() {
        val known = setOf("GAIN", "DEPTH", "FOCUS", "SCALE", "2D", "M-MODE", "COLOR", "PW", "CW", "TDI", "ZOOM",
            "TRACKBALL", "FREEZE", "CALIPER", "STORE")
        assertEquals(known, protocol.consoleControls.keys)
        protocol.consoleControls.values.forEach { t ->
            assertFalse(t.de.isNullOrBlank()); assertFalse(t.ar.isNullOrBlank())
        }
        protocol.allViews.forEach { v ->
            assertTrue("no console steps for ${v.id}", v.machineControls.size >= 4)
            v.machineControls.forEach { step ->
                assertTrue("unknown control ${step.control}", step.control in known)
                assertFalse(step.instruction.de.isNullOrBlank()); assertFalse(step.instruction.ar.isNullOrBlank())
            }
        }
    }

    @Test
    fun everyViewHasEcgTimingAndTimingNote() {
        protocol.allViews.forEach { v ->
            assertTrue(v.ecg.beatMs > 0)
            assertTrue("ES must follow ED in ${v.id}", v.ecg.esMs in (v.ecg.edMs + 1) until (v.ecg.edMs + v.ecg.beatMs))
            val note = v.timingNote
            assertTrue("timing note missing for ${v.id}", note != null && !note.de.isNullOrBlank() && !note.ar.isNullOrBlank())
            v.referenceMeasurements.adult.forEach { m -> m.phases.forEach { assertTrue(it == "ED" || it == "ES") } }
        }
        assertTrue(protocol.allViews.sumOf { v -> v.referenceMeasurements.adult.count { it.phases.isNotEmpty() } } >= 20)
    }

    @Test
    fun svgAssetsUseTheTextbookViewBox() {
        protocol.allViews.forEach { v ->
            val svg = File(assetsDir, v.svgAsset).readText()
            assertTrue("${v.svgAsset} viewBox", svg.contains("viewBox=\"0 0 600 420\""))
            assertTrue("${v.svgAsset} sector clip", svg.contains("clip-path=\"url(#sectorClip)\""))
        }
    }

    @Test
    fun localizedTextFallsBackToEnglish() {
        val t = LocalizedText(en = "TAPSE")
        assertEquals("TAPSE", t.get(AppLanguage.DE))
        assertEquals("TAPSE", t.get(AppLanguage.AR))
        val full = LocalizedText(en = "Valves", de = "Klappen", ar = "الصمامات")
        assertEquals("Klappen", full.get(AppLanguage.DE))
        assertEquals("الصمامات", full.get(AppLanguage.AR))
    }

    @Test
    fun languageTagsResolve() {
        assertEquals(AppLanguage.DE, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.AR, AppLanguage.fromTag("ar"))
        assertEquals(null, AppLanguage.fromTag("fr"))
        assertTrue(AppLanguage.AR.isRtl)
        assertFalse(AppLanguage.DE.isRtl)
    }
}
