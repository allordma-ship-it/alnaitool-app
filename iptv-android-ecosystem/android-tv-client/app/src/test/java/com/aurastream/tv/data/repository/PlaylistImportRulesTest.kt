package com.aurastream.tv.data.repository

import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.remote.M3uEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaylistImportRulesTest {

    private fun entry(title: String, url: String) = M3uEntry(title, url, -1, null, null, null, null, null, null, emptyMap())

    @Test
    fun `classifies by xtream path segments`() {
        assertEquals(StreamType.LIVE, PlaylistImportRules.classify(entry("CNN", "http://h/live/u/p/1.ts")))
        assertEquals(StreamType.LIVE, PlaylistImportRules.classify(entry("CNN", "http://h/u/p/1")))
        assertEquals(StreamType.MOVIE, PlaylistImportRules.classify(entry("Heat (1995)", "http://h/movie/u/p/2.mkv")))
        assertEquals(StreamType.SERIES, PlaylistImportRules.classify(entry("Dark S01 E01", "http://h/series/u/p/3.mp4")))
    }

    @Test
    fun `progressive files are vod and SxxEyy titles are series`() {
        assertEquals(StreamType.MOVIE, PlaylistImportRules.classify(entry("Film", "http://cdn/x/film.mp4?sig=1")))
        assertEquals(StreamType.SERIES, PlaylistImportRules.classify(entry("Show.Name.s02e10", "http://cdn/x/show.mkv")))
    }

    @Test
    fun `series pattern extracts name season and episode`() {
        val m = PlaylistImportRules.SERIES_PATTERN.find("Breaking Bad - S05E14 - Ozymandias")!!
        assertEquals("Breaking Bad", m.groupValues[1].trim())
        assertEquals("05", m.groupValues[2])
        assertEquals("14", m.groupValues[3])
    }

    @Test
    fun `stable ids are deterministic and distinct`() {
        assertEquals(PlaylistImportRules.stableId("a|b"), PlaylistImportRules.stableId("a|b"))
        assertNotEquals(PlaylistImportRules.stableId("a|b"), PlaylistImportRules.stableId("a|c"))
        // FNV-1a 64 reference value for the empty string is the offset basis.
        assertEquals("cbf29ce484222325", PlaylistImportRules.stableId(""))
    }
}
