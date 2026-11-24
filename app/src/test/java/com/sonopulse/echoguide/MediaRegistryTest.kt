package com.sonopulse.echoguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Media licence registry: every bundled video / image is registered, nothing restricted or unverified
 * is marked commercially usable, and the mapping layer is the identity today (app unchanged).
 */
class MediaRegistryTest {

    private val assets = File("src/main/assets")
    private val res = File("src/main/res")
    private val registry = MediaRegistry.parse(File(assets, MediaRegistry.REGISTRY_ASSET).readText())
    private val mediaExt = setOf("mp4", "webm", "jpg", "jpeg", "png", "webp", "svg", "gif")

    @Test
    fun everyBundledMediaFileIsRegisteredAndExists() {
        val bundled = assets.walkTopDown().filter { it.isFile && it.extension.lowercase() in mediaExt }
            .map { it.relativeTo(assets).invariantSeparatorsPath }.toSet()
        val registered = registry.assets.map { it.path }.toSet()
        assertTrue("unregistered: ${bundled - registered}", registered.containsAll(bundled))
        registry.assets.forEach { e ->
            val file = if (e.path.startsWith("res/")) File(res, e.path.removePrefix("res/")) else File(assets, e.path)
            assertTrue("registered but missing: ${e.path}", file.isFile)
        }
        assertEquals("one entry per path", registry.assets.size, registered.size)
    }

    @Test
    fun noRestrictedOrUnverifiedAssetIsMarkedCommercial() {
        registry.assets.forEach { e ->
            val lic = e.license.orEmpty()
            if (lic.contains("NC")) assertEquals(e.path, CommercialUse.NON_COMMERCIAL_ONLY, e.commercialUse)
            if (lic.contains("UNVERIFIED", ignoreCase = true)) assertEquals(e.path, CommercialUse.LICENSE_UNVERIFIED, e.commercialUse)
            if (e.path.startsWith("echoorg/")) assertEquals(e.path, CommercialUse.PERMISSION_REQUIRED, e.commercialUse)
        }
        val status = registry.assets.associate { it.path to it.commercialUse }
        // POCUS Atlas loops (CC BY-NC 4.0)
        listOf("loops/psax_av.mp4", "loops/apical_5c.mp4", "loops/color/a4c_mv_color.mp4", "loops/color/a4c_tv_color.mp4",
            "loops/color/a5c_av_color.mp4", "loops/color/plax_color.mp4").forEach {
            assertEquals(it, CommercialUse.NON_COMMERCIAL_ONLY, status[it])
        }
        assertEquals(CommercialUse.LICENSE_UNVERIFIED, status["diagrams/subcostal_ivc.png"])
        // Schema drawings derived from Echocardiographer.org need permission; the IVC drawing is project-original.
        assertEquals(CommercialUse.PERMISSION_REQUIRED, status["schema/apical_4c_dark.webp"])
        assertEquals(CommercialUse.COMMERCIAL_ALLOWED, status["schema/subcostal_ivc_dark.webp"])
    }

    @Test
    fun mappingIsIdentityTodaySoTheAppIsUnchanged() {
        assertTrue("no replacements configured yet", registry.assets.all { it.replacement == null })
        MediaRegistry.install(registry)
        registry.assets.forEach { assertEquals(it.path, MediaRegistry.resolve(it.path)) }
        assertEquals("file:///android_asset/loops/apical_4c.mp4", MediaRegistry.assetUri("loops/apical_4c.mp4"))
    }

    @Test
    fun aRegisteredReplacementIsUsedInsteadOfTheOriginal() {
        val original = registry.assets.first { it.path == "loops/psax_av.mp4" }
        val withReplacement = registry.copy(assets = registry.assets.map {
            if (it.path == original.path) it.copy(replacement = "media/commercial/psax_av.mp4") else it
        })
        MediaRegistry.install(withReplacement)
        assertEquals("media/commercial/psax_av.mp4", MediaRegistry.resolve("loops/psax_av.mp4"))
        assertEquals("loops/apical_4c.mp4", MediaRegistry.resolve("loops/apical_4c.mp4"))
        assertNull(MediaRegistry.entry("unknown.mp4"))
        MediaRegistry.install(registry)   // restore identity for other tests
    }
}
