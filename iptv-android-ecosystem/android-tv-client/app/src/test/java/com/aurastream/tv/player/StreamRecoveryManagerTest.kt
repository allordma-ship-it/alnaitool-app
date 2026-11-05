package com.aurastream.tv.player

import com.aurastream.tv.data.local.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamRecoveryManagerTest {

    private val ts = "http://h/live/u/p/1.ts"
    private val hls = "http://h/live/u/p/1.m3u8"
    private lateinit var manager: StreamRecoveryManager

    @Before
    fun setUp() {
        manager = StreamRecoveryManager()
        manager.reset(ts, StreamFormat.TS)
    }

    @Test
    fun `timeouts escalate reconnect then format switch then decoder fallback then give up`() {
        val a1 = manager.onFailure(FailureKind.TIMEOUT, ts, StreamFormat.TS)
        assertTrue(a1 is RecoveryAction.Reconnect)
        assertEquals(1, a1.stage)

        val a2 = manager.onFailure(FailureKind.TIMEOUT, ts, StreamFormat.TS) as RecoveryAction.SwitchFormat
        assertEquals(hls, a2.url)
        assertEquals(StreamFormat.HLS, a2.format)

        val a3 = manager.onFailure(FailureKind.TIMEOUT, hls, StreamFormat.HLS) as RecoveryAction.DecoderFallback
        assertEquals(ts, a3.url) // back to the original source with software decoding

        assertTrue(manager.onFailure(FailureKind.TIMEOUT, ts, StreamFormat.TS) is RecoveryAction.GiveUp)
    }

    @Test
    fun `http 403 and 404 skip straight to the container switch`() {
        val a = manager.onFailure(FailureKind.HTTP_FORBIDDEN, ts, StreamFormat.TS)
        assertTrue(a is RecoveryAction.SwitchFormat)
        assertEquals(2, a.stage)
    }

    @Test
    fun `decoder failures go directly to software decoding on the same url`() {
        val a = manager.onFailure(FailureKind.DECODER, hls, StreamFormat.HLS) as RecoveryAction.DecoderFallback
        assertEquals(hls, a.url)
        assertEquals(3, a.stage)
    }

    @Test
    fun `behind live window is not counted as a failure`() {
        repeat(5) { assertEquals(RecoveryAction.SeekToLiveEdge, manager.onFailure(FailureKind.BEHIND_LIVE_WINDOW, ts, StreamFormat.TS)) }
        assertEquals(0, manager.currentStage)
    }

    @Test
    fun `stable playback resets the stages`() {
        manager.onFailure(FailureKind.NETWORK, ts, StreamFormat.TS)
        manager.onFailure(FailureKind.NETWORK, ts, StreamFormat.TS)
        manager.onPlaybackStable()
        assertTrue(manager.onFailure(FailureKind.NETWORK, ts, StreamFormat.TS) is RecoveryAction.Reconnect)
    }

    @Test
    fun `non rewritable vod urls reconnect instead of switching`() {
        val mp4 = "http://h/movie/u/p/9.mp4"
        manager.reset(mp4, null)
        manager.onFailure(FailureKind.HTTP_SERVER, mp4, null)
        assertTrue(manager.onFailure(FailureKind.HTTP_SERVER, mp4, null) is RecoveryAction.Reconnect)
    }
}
