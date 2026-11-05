package com.aurastream.tv.player

import com.aurastream.tv.data.local.StreamFormat

/** Normalised failure categories (decoupled from Media3 so the policy is unit-testable). */
enum class FailureKind {
    HTTP_FORBIDDEN, // 401 / 403 – token / geo / user-agent block
    HTTP_NOT_FOUND, // 404 / 410 – often "wrong container for this panel"
    HTTP_SERVER, // 5xx incl. 502 / 504 gateway timeouts
    NETWORK,
    TIMEOUT,
    PARSING, // malformed container / manifest
    DECODER, // codec init / decode failure
    BEHIND_LIVE_WINDOW,
    UNKNOWN,
}

sealed interface RecoveryAction {
    val stage: Int

    /** Stage 1: re-open the same URL after a short back-off (transient network / gateway errors). */
    data class Reconnect(val url: String, val delayMs: Long, override val stage: Int) : RecoveryAction

    /** Stage 2: switch container MPEG-TS <-> HLS. */
    data class SwitchFormat(val url: String, val format: StreamFormat, val delayMs: Long, override val stage: Int) : RecoveryAction

    /** Stage 3: rebuild the player preferring software decoders, on the most promising URL. */
    data class DecoderFallback(val url: String, val format: StreamFormat?, val delayMs: Long, override val stage: Int) : RecoveryAction

    /** Live stream fell behind the window: jump to the live edge, not a failure. */
    data object SeekToLiveEdge : RecoveryAction {
        override val stage: Int = 0
    }

    data class GiveUp(val kind: FailureKind, override val stage: Int) : RecoveryAction
}

/**
 * Three-stage self-healing policy for IPTV playback.
 *
 * ```
 *   failure ──► stage 1  reconnect same URL (back-off)          [network / timeout / 5xx]
 *           └─► stage 2  switch TS ⇄ HLS                       [403 / 404 / parsing, or stage 1 failed]
 *           └─► stage 3  software-decoder fallback + original   [decoder errors, or stage 2 failed]
 *           └─► give up (surface error, allow manual retry)
 * ```
 * Stages reset after the stream has played stably ([onPlaybackStable]) or when new
 * media is started ([reset]).
 */
class StreamRecoveryManager(private val maxStages: Int = 3) {

    private var stage = 0
    private var originalUrl: String? = null
    private var originalFormat: StreamFormat? = null

    val currentStage: Int get() = stage

    fun reset(url: String, format: StreamFormat?) {
        stage = 0
        originalUrl = url
        originalFormat = format
    }

    fun onPlaybackStable() {
        stage = 0
    }

    fun onFailure(kind: FailureKind, currentUrl: String, currentFormat: StreamFormat?): RecoveryAction {
        if (kind == FailureKind.BEHIND_LIVE_WINDOW) return RecoveryAction.SeekToLiveEdge

        stage++
        // Errors that a plain reconnect cannot fix skip straight to the container switch.
        if (stage == 1 && kind in SKIP_RECONNECT) stage = 2
        // Decoder failures are not fixed by changing the transport container first.
        if (kind == FailureKind.DECODER && stage < 3) stage = 3

        if (stage > maxStages) return RecoveryAction.GiveUp(kind, stage)

        return when (stage) {
            1 -> RecoveryAction.Reconnect(currentUrl, backoff(kind), stage)
            2 -> {
                val alt = StreamUrlResolver.alternate(currentUrl, currentFormat)
                if (alt != null) {
                    RecoveryAction.SwitchFormat(alt.first, alt.second, 500, stage)
                } else {
                    RecoveryAction.Reconnect(currentUrl, 2_000, stage)
                }
            }
            else -> {
                // Prefer the format we have not just tried; for decoder errors stay on the same URL.
                val url = if (kind == FailureKind.DECODER) currentUrl else originalUrl ?: currentUrl
                val format = if (kind == FailureKind.DECODER) currentFormat else originalFormat
                RecoveryAction.DecoderFallback(url, format, 3_000, stage)
            }
        }
    }

    private fun backoff(kind: FailureKind): Long = when (kind) {
        FailureKind.HTTP_SERVER -> 1_500
        FailureKind.TIMEOUT -> 1_000
        else -> 800
    }

    private companion object {
        val SKIP_RECONNECT = setOf(FailureKind.HTTP_FORBIDDEN, FailureKind.HTTP_NOT_FOUND, FailureKind.PARSING)
    }
}
