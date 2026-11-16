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
            "apical_4c", "apical_5c", "apical_2c", "apical_3c", "suprasternal_arch"
        )
    }

    @Test
    fun hasFourWindowsAndTenViewsInOrder() {
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
        assertEquals(52, steps.size)
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
    fun realClipsCarryAttributionAndTiming() {
        val real = protocol.allViews.filter { !it.videoSource.isSimulated }
        assertTrue("expected real clips", real.isNotEmpty())
        val attribution = File(assetsDir, "loops/ATTRIBUTION.txt").readText()
        real.forEach { v ->
            val src = v.videoSource
            assertFalse("author missing for ${v.id}", src.author.isNullOrBlank())
            assertFalse("licence missing for ${v.id}", src.license.isNullOrBlank())
            // Approved sources only: Wikimedia Commons (CC BY / BY-SA) and The POCUS Atlas (CC BY-NC).
            assertTrue("source URL missing for ${v.id}", src.sourceUrl.orEmpty().startsWith("https://commons.wikimedia.org/") ||
                src.sourceUrl.orEmpty().startsWith("https://www.thepocusatlas.com/"))
            assertTrue("${v.id} not credited in ATTRIBUTION.txt", attribution.contains("loops/${v.id}.mp4"))
            assertTrue(v.ecg.rWavesMs.zipWithNext().all { (a, b) -> b > a })
        }
    }

    @Test
    fun apicalFiveChamberIsARealLoop() {
        val a5c = protocol.allViews.first { it.id == "apical_5c" }
        assertFalse("apical_5c must not be simulated", a5c.videoSource.isSimulated)
        assertEquals("The POCUS Atlas", a5c.videoSource.platform)
        assertEquals(listOf(0L, 1100L), a5c.ecg.rWavesMs)
        val labels = a5c.steps.flatMap { it.overlays }.map { it.label }
        assertTrue(labels.containsAll(listOf("PW · LVOT", "CW · AV")))
        // Every view now plays a real clip.
        assertEquals(emptyList<String>(), protocol.allViews.filter { it.videoSource.isSimulated }.map { it.id })
    }

    @Test
    fun colourLoopsAndMeasurementGuideAreComplete() {
        val attribution = File(assetsDir, "loops/ATTRIBUTION.txt").readText()
        val viewIds = protocol.allViews.map { it.id }.toSet()
        assertEquals(setOf("plax_color", "a4c_mv_color", "a4c_tv_color", "a5c_av_color"), protocol.colorLoops.keys)
        protocol.colorLoops.forEach { (id, c) ->
            assertTrue("missing ${c.asset}", File(assetsDir, c.asset).isFile)
            assertTrue("$id not credited", attribution.contains(c.asset))
            assertTrue("$id nyquist", c.nyquistCmS in 30..80)
            assertTrue("$id views", c.views.isNotEmpty() && c.views.all { it in viewIds })
        }
        val spectral = setOf("AV_CW", "LVOT_PW", "AR_PHT", "MV_PW", "MS_CW", "TR_CW", "TAPSE", "PV_PW", "PISA", "VC", "CONTINUITY")
        protocol.valves.forEach { v ->
            assertTrue("${v.id} measurements", v.measurements.size >= 2)
            v.measurements.forEach { m ->
                assertTrue("${m.id} view", m.view in viewIds)
                assertTrue("${m.id} loop ${m.loop}", File(assetsDir, m.loop).isFile)
                assertTrue("${m.id} spectral ${m.spectral}", m.spectral in spectral)
                m.overlay?.let { assertTrue("${m.id} overlay", isNormalizedBox(it.video)) }
                assertTrue("${m.id} translated", m.steps.all { it.de != null && it.ar != null })
            }
        }
        // CW cursors carry the focus diamond at the valve plane; Doppler steps cover all valves.
        val labels = protocol.allViews.flatMap { v -> v.steps.flatMap { it.overlays } }.map { it.label }
        assertTrue(labels.containsAll(listOf("PW · E/A", "e' septal", "e' lateral", "PW · LVOT", "CW · AV", "CW · TR", "PW · PV")))
    }

    @Test
    fun apicalFiveChamberDopplerRunsThroughTheCentralAorticValve() {
        // In the A5C loop the LVOT / aortic root is central (x ≈ 0.50); MV and LA lie to the right.
        val a5c = protocol.allViews.first { it.id == "apical_5c" }
        val overlays = a5c.steps.flatMap { it.overlays }.associateBy { it.label }
        val gate = overlays.getValue("PW · LVOT").video!!
        val cw = overlays.getValue("CW · AV").video!!
        assertEquals(0.505f, gate[2], 0.03f)
        assertEquals(0.50f, cw[0] + (cw[2] - cw[0]) * CW_FOCUS, 0.03f)
        assertEquals(0.62f, cw[1] + (cw[3] - cw[1]) * CW_FOCUS, 0.03f)
    }

    @Test
    fun realTracesAndCaseLibrary() {
        val traces = protocol.valves.flatMap { it.measurements }.mapNotNull { m -> m.trace?.let { m.id to it } }.toMap()
        assertTrue(traces.keys.containsAll(listOf("av_cw", "lvot_pw", "mv_pw", "tapse")))
        traces.values.forEach { t ->
            assertTrue("missing ${t.asset}", File(assetsDir, t.asset).isFile)
            assertTrue(t.marks.all { it.x in 0f..1f && it.y in 0f..1f })
        }
        val lib = protocol.caseLibrary!!
        assertTrue(lib.groups.sumOf { it.cases.size } >= 50)
        assertTrue(lib.groups.flatMap { it.cases }.all { it.url.startsWith("https://youtu.be/") })
        assertTrue(lib.groups.flatMap { it.cases }.map { it.title }.containsAll(
            listOf("Dilated Cardiomyopathy", "Apical HCM", "Ascending Aortic Aneurysm with AR", "Cor Pulmonale")))
    }

    @Test
    fun everyViewHasAReadableAtlasDiagramWithCredit() {
        protocol.allViews.forEach { v ->
            val asset = v.diagramAsset
            assertTrue("diagram missing for ${v.id}", asset != null && File(assetsDir, asset).isFile)
            assertTrue(v.diagramAspect > 0.5f && v.diagramAspect < 2.5f)
            val credit = v.diagramCredit
            assertTrue("credit missing for ${v.id}", credit != null && !credit.de.isNullOrBlank() && !credit.ar.isNullOrBlank())
        }
    }

    @Test
    fun measurementStepsFreezeOnTheirPhaseWithOverlays() {
        var frozen = 0
        protocol.allViews.forEach { view ->
            view.steps.forEach { step ->
                val where = "${view.id}: ${step.title.en}"
                assertTrue("$where phase ${step.requiredPhase}", step.requiredPhase in StepPhase.all)
                if (step.requiredPhase == StepPhase.EXP) assertTrue("$where needs freezeAtMs", step.freezeAtMs != null)
                step.overlays.forEach { o ->
                    assertTrue("$where type ${o.type}", o.type in CaliperOverlay.types)
                    assertTrue("$where label", o.label.isNotBlank())
                    assertTrue("$where video coords", isNormalizedBox(o.video))
                    assertTrue("$where diagram coords", isNormalizedBox(o.diagram))
                }
                if (step.requiredPhase != StepPhase.LIVE) {
                    frozen++
                    // Every frozen step measures something, except the plain "freeze end-diastole" step.
                    if (!step.title.en.startsWith("Freeze")) {
                        assertTrue("$where has no caliper overlay", step.caliperOverlay?.type == CaliperOverlay.LINE)
                    }
                }
            }
        }
        assertTrue("ED / ES steps present", frozen >= 15)
    }

    @Test
    fun everyViewHasASchemaAndOrientationIllustrations() {
        val attribution = File(assetsDir, "echoorg/ATTRIBUTION.txt").readText()
        protocol.allViews.forEach { view ->
            // Schema = real medical drawings / labelled echo frames (Echocardiographer.org), no generated art.
            assertTrue("${view.id} schema pages", view.schemaGallery.isNotEmpty())
            view.schemaGallery.forEach { img ->
                assertTrue("missing ${img.asset}", img.asset.startsWith("echoorg/") && File(assetsDir, img.asset).isFile)
                assertTrue("${img.asset} credit", img.credit?.contains("Echocardiographer.org") == true)
                assertTrue("${img.asset} attribution", attribution.contains(img.asset))
            }
            val orientation = view.orientationAsset
            assertTrue("${view.id} orientation", orientation != null)
            AppLanguage.entries.forEach { lang ->
                val path = localizedOrientationAsset(orientation!!, lang)
                assertTrue("missing $path", File(assetsDir, path).isFile)
                assertTrue("$path is not an SVG", File(assetsDir, path).readText().contains("<svg"))
            }
        }
    }

    @Test
    fun everySvgAssetIsWellFormedForAndroid() {
        // Android's SVG parser rejects malformed XML (e.g. an unescaped "&" in a title) and shows
        // nothing; it also ignores paint-order, which would hide labels under their outline.
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val svgs = listOf("orientation", "svg").flatMap { dir ->
            File(assetsDir, dir).listFiles { f -> f.name.endsWith(".svg") }.orEmpty().toList()
        }
        assertTrue("SVG assets found", svgs.size >= 10 * 3 + 9)
        svgs.forEach { f ->
            try {
                factory.newDocumentBuilder().parse(f)
            } catch (e: Exception) {
                throw AssertionError("${f.name} is not well-formed XML: ${e.message}")
            }
            assertFalse("${f.name} uses paint-order", f.readText().contains("paint-order"))
        }
    }

    @Test
    fun echocardiographerOrgImagesAreBundledAndCredited() {
        val images = protocol.allViews.flatMap { v -> v.probeGallery + v.atlasGallery + v.steps.flatMap { it.examples } }
        assertTrue("gallery images", images.size >= 40)
        images.forEach { img ->
            assertTrue("missing ${img.asset}", File(assetsDir, img.asset).isFile)
            assertTrue("${img.asset} credit", img.credit?.contains("Echocardiographer.org") == true)
            assertTrue("${img.asset} caption", img.caption.de != null && img.caption.ar != null)
        }
        val attribution = File(assetsDir, "echoorg/ATTRIBUTION.txt").readText()
        images.map { it.asset }.distinct().forEach { assertTrue("$it not in ATTRIBUTION", attribution.contains(it)) }
        // Every view has a probe photo; the Doppler steps have real example recordings.
        protocol.allViews.forEach { assertTrue("${it.id} probe photo", it.probeGallery.isNotEmpty()) }
        assertEquals(2, protocol.allViews.first { it.id == "apical_4c" }.steps[6].examples.size)
        protocol.allViews.forEach { v ->
            assertFalse("${v.id} atlas credit unresolved", v.diagramCredit?.en.orEmpty().contains("to be confirmed") &&
                v.id == "suprasternal_arch")
        }
    }

    @Test
    fun valveAssessmentCoversAllFourValves() {
        assertEquals(listOf("aortic", "mitral", "tricuspid", "pulmonary"), protocol.valves.map { it.id })
        val viewIds = protocol.allViews.map { it.id }.toSet()
        val attribution = File(assetsDir, "echoorg/ATTRIBUTION.txt").readText()
        protocol.valves.forEach { v ->
            assertTrue(v.id, v.views.isNotEmpty() && v.views.all { it in viewIds })
            listOf(v.morphology, v.color, v.spectral).forEach { section ->
                assertTrue("${v.id} checklist", section.isNotEmpty())
                section.forEach { item ->
                    assertTrue("${v.id} item translated", item.text.de != null && item.text.ar != null)
                    item.view?.let { assertTrue("${v.id} links $it", it in viewIds) }
                }
            }
            assertTrue("${v.id} normals", v.normals.isNotEmpty())
            assertTrue("${v.id} grading", v.grading.isNotEmpty())
            v.grading.flatMap { it.rows }.forEach { r ->
                listOf(r.parameter, r.mild, r.moderate, r.severe).forEach { cell ->
                    assertTrue("${v.id} ${r.parameter.en} untranslated", cell.de != null && cell.ar != null)
                }
            }
            v.examples.forEach { img ->
                assertTrue("missing ${img.asset}", File(assetsDir, img.asset).isFile)
                assertTrue("${img.asset} not in ATTRIBUTION", attribution.contains(img.asset))
            }
        }
        // Severe aortic stenosis per EACVI/ASE 2017.
        val severeAs = protocol.valves.first { it.id == "aortic" }.grading.first().rows.map { it.severe.en }
        assertTrue(severeAs.containsAll(listOf("≥ 4.0 m/s", "≥ 40 mmHg", "< 1.0 cm²")))
    }

    @Test
    fun germanAndEnglishTextContainsNoArabic() {
        val arabic = Regex("[\\u0600-\\u06FF]")
        val raw = File(assetsDir, EchoRepository.PROTOCOL_ASSET).readText()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        fun walk(e: kotlinx.serialization.json.JsonElement, path: String) {
            when (e) {
                is kotlinx.serialization.json.JsonObject -> e.forEach { (k, v) ->
                    if ((k == "en" || k == "de") && v is kotlinx.serialization.json.JsonPrimitive && v.isString) {
                        assertFalse("Arabic in $path.$k", arabic.containsMatchIn(v.content))
                    }
                    walk(v, "$path.$k")
                }
                is kotlinx.serialization.json.JsonArray -> e.forEachIndexed { i, v -> walk(v, "$path[$i]") }
                else -> Unit
            }
        }
        walk(json, "")
    }

    @Test
    fun eccentricityIndexHasD1AndD2InDiastoleAndSystole() {
        val pap = protocol.allViews.first { it.id == "psax_papillary" }
        val phases = pap.steps.filter { s -> s.overlays.map { it.label }.containsAll(listOf("D1", "D2")) }.map { it.requiredPhase }
        assertEquals(listOf(StepPhase.ED, StepPhase.ES), phases)
        val tdi = protocol.allViews.first { it.id == "apical_4c" }.steps.first { s -> s.overlays.size == 2 }
        assertTrue(tdi.overlays.all { it.type == CaliperOverlay.DOPPLER_GATE })
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
