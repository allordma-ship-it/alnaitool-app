package com.aurastream.tv.player

import com.aurastream.tv.data.local.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlResolverTest {

    @Test
    fun `detects container formats`() {
        assertEquals(StreamFormat.TS, StreamUrlResolver.detectFormat("http://h/live/u/p/1.ts"))
        assertEquals(StreamFormat.HLS, StreamUrlResolver.detectFormat("http://h/live/u/p/1.m3u8?token=x"))
        assertEquals(StreamFormat.TS, StreamUrlResolver.detectFormat("http://h/play?stream=1&output=ts"))
        assertEquals(StreamFormat.HLS, StreamUrlResolver.detectFormat("http://h/play?stream=1&output=hls"))
        assertNull(StreamUrlResolver.detectFormat("http://h/movie/u/p/1.mp4"))
    }

    @Test
    fun `swaps xtream extensions in both directions`() {
        assertEquals("http://h:80/live/u/p/1.m3u8", StreamUrlResolver.toFormat("http://h:80/live/u/p/1.ts", StreamFormat.HLS))
        assertEquals("http://h:80/live/u/p/1.ts", StreamUrlResolver.toFormat("http://h:80/live/u/p/1.m3u8", StreamFormat.TS))
        assertEquals("http://h/live/u/p/1.m3u8?t=1", StreamUrlResolver.toFormat("http://h/live/u/p/1.ts?t=1", StreamFormat.HLS))
    }

    @Test
    fun `extension-less xtream short form gets an explicit container`() {
        assertTrue(StreamUrlResolver.isXtreamLive("http://h:8080/user/pass/12345"))
        assertEquals("http://h:8080/user/pass/12345.m3u8", StreamUrlResolver.toFormat("http://h:8080/user/pass/12345", StreamFormat.HLS))
    }

    @Test
    fun `rewrites output query parameter`() {
        assertEquals("http://h/play?id=1&output=hls", StreamUrlResolver.toFormat("http://h/play?id=1&output=ts", StreamFormat.HLS))
        assertEquals("http://h/play?id=1&extension=ts", StreamUrlResolver.toFormat("http://h/play?id=1&extension=m3u8", StreamFormat.TS))
    }

    @Test
    fun `progressive files cannot be rewritten`() {
        assertNull(StreamUrlResolver.toFormat("http://h/movie/u/p/7.mkv", StreamFormat.HLS))
        assertNull(StreamUrlResolver.alternate("http://h/movie/u/p/7.mkv", null))
    }

    @Test
    fun `preferred format only applies to xtream live urls`() {
        val (url, format) = StreamUrlResolver.applyPreferred("http://h/live/u/p/1.ts", StreamFormat.HLS, isLive = true)
        assertEquals("http://h/live/u/p/1.m3u8", url)
        assertEquals(StreamFormat.HLS, format)

        val cdn = "https://cdn.example/master/index.m3u8"
        assertFalse(StreamUrlResolver.isXtreamLive(cdn))
        assertEquals(cdn, StreamUrlResolver.applyPreferred(cdn, StreamFormat.TS, isLive = true).first)
        assertEquals("http://h/live/u/p/1.ts", StreamUrlResolver.applyPreferred("http://h/live/u/p/1.ts", StreamFormat.HLS, isLive = false).first)
    }
}
