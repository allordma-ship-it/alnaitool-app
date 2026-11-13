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

        private lateinit var protocol: EchoProtocol

        @JvmStatic
        @BeforeClass
        fun loadProtocol() {
            val text = File(assetsDir, EchoRepository.PROTOCOL_ASSET).readText(Charsets.UTF_8)
            protocol = Json { ignoreUnknownKeys = true }.decodeFromString(EchoProtocol.serializer(), text)
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
