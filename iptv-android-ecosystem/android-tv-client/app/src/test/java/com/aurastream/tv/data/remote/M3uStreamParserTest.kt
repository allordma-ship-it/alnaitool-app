package com.aurastream.tv.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class M3uStreamParserTest {

    private fun parse(text: String) = M3uStreamParser().parse(BufferedReader(StringReader(text)))

    @Test
    fun `parses header epg and extended attributes`() {
        val doc = parse(
            "\uFEFF#EXTM3U url-tvg=\"http://epg.example/a.xml,http://epg.example/b.xml\" x-tvg-url=\"http://epg.example/a.xml\"\r\n" +
                "#EXTINF:-1 tvg-id=\"bbc1.uk\" tvg-name=\"BBC One\" tvg-logo=\"http://logo/bbc.png\" group-title=\"UK | News\",BBC One HD\r\n" +
                "http://host:8080/u/p/101\r\n",
        )
        assertEquals(listOf("http://epg.example/a.xml", "http://epg.example/b.xml"), doc.header.epgUrls)
        val entries = doc.entries.toList()
        assertEquals(1, entries.size)
        with(entries[0]) {
            assertEquals("BBC One HD", title)
            assertEquals("http://host:8080/u/p/101", url)
            assertEquals("bbc1.uk", tvgId)
            assertEquals("http://logo/bbc.png", tvgLogo)
            assertEquals("UK | News", groupTitle)
            assertEquals(-1, durationSecs)
        }
    }

    @Test
    fun `commas inside quoted attributes do not split the title`() {
        val entry = parse(
            "#EXTM3U\n#EXTINF:0 tvg-name=\"Sky, Sports\" group-title=\"Sport, Live\",Sky Sports, Main Event\nhttp://s/1.ts\n",
        ).entries.single()
        assertEquals("Sky Sports, Main Event", entry.title)
        assertEquals("Sport, Live", entry.groupTitle)
        assertEquals("Sky, Sports", entry.tvgName)
    }

    @Test
    fun `supports EXTGRP and EXTVLCOPT overrides`() {
        val entry = parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel
            #EXTGRP:Movies
            #EXTVLCOPT:http-user-agent=VLC/3.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            http://s/ch.m3u8
            """.trimIndent(),
        ).entries.single()
        assertEquals("Movies", entry.groupTitle)
        assertEquals("VLC/3.0", entry.userAgent)
        assertEquals("http://ref.example/", entry.referrer)
    }

    @Test
    fun `headerless playlists and bare urls are accepted, junk is skipped`() {
        val entries = parse("http://a/1.ts\n# comment\nnot a url\n#EXTINF:-1,B\nhttp://a/2.ts\n").entries.toList()
        assertEquals(listOf("1.ts", "B"), entries.map { it.title })
        assertNull(entries[0].groupTitle)
    }

    @Test
    fun `unquoted and single quoted attribute values`() {
        val entry = parse("#EXTM3U\n#EXTINF:-1 tvg-id=abc group-title='Kids',Cartoons\nhttp://k/1\n").entries.single()
        assertEquals("abc", entry.tvgId)
        assertEquals("Kids", entry.groupTitle)
    }

    @Test
    fun `streams 60k entries lazily`() {
        val sb = StringBuilder("#EXTM3U\n")
        repeat(60_000) { i -> sb.append("#EXTINF:-1 tvg-id=\"c$i\" group-title=\"G${i % 50}\",Channel $i\nhttp://h/live/u/p/$i.ts\n") }
        val start = System.nanoTime()
        var count = 0
        parse(sb.toString()).entries.forEach { count++ }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(60_000, count)
        assertTrue("parsing took ${ms}ms", ms < 5_000)
    }
}
