@file:OptIn(UnstableApi::class)

package com.aurastream.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.aurastream.tv.data.local.AspectMode
import com.aurastream.tv.data.local.SettingsStore
import com.aurastream.tv.data.local.StreamFormat
import com.aurastream.tv.data.remote.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale

data class ExternalSubtitle(val url: String, val language: String?, val label: String?)

data class PlaybackRequest(
    val mediaId: String,
    val url: String,
    val title: String,
    val isLive: Boolean,
    val startPositionMs: Long = 0,
    val subtitles: List<ExternalSubtitle> = emptyList(),
    val userAgent: String? = null,
    val referrer: String? = null,
    val artworkUrl: String? = null,
)

data class TrackOption(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
)

data class RecoveryStatus(val stage: Int, val description: String)

/** Position of the last media item taken right before [Media3PlayerAdapter.stop]. */
data class PlaybackSnapshot(val mediaId: String, val positionMs: Long, val durationMs: Long)

data class PlayerUiState(
    val mediaId: String? = null,
    val title: String = "",
    val isLive: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    val format: StreamFormat? = null,
    val canSwitchFormat: Boolean = false,
    val aspectMode: AspectMode = AspectMode.FIT,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val recovery: RecoveryStatus? = null,
    val error: String? = null,
    val softwareDecoding: Boolean = false,
)

/**
 * App-wide ExoPlayer wrapper shared by the Live TV mini-player and the fullscreen
 * player so switching between them never re-buffers.
 *
 * Responsibilities:
 *  - building ExoPlayer (OkHttp data source with configurable User-Agent/Referer,
 *    TS extractor flags for IPTV, live-friendly buffering, decoder fallback)
 *  - audio / subtitle track discovery and selection via [DefaultTrackSelector]
 *  - aspect ratio mode exposure for the surface
 *  - automatic 3-stage error recovery through [StreamRecoveryManager]
 *
 * Must be used from the main thread.
 */
class Media3PlayerAdapter(
    context: Context,
    private val network: NetworkModule,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recovery = StreamRecoveryManager()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    /** Current ExoPlayer instance; changes when the player is rebuilt (software decoder fallback). */
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var trackSelector: DefaultTrackSelector? = null
    private var currentRequest: PlaybackRequest? = null
    private var currentUrl: String? = null
    private var currentFormat: StreamFormat? = null
    private var preferSoftwareDecoders = false
    private var progressJob: Job? = null
    private var stabilityJob: Job? = null
    private var recoveryJob: Job? = null
    private var autoRecovery = true

    init {
        scope.launch {
            settings.settings.collect { s ->
                autoRecovery = s.autoRecovery
                _state.update { it.copy(aspectMode = s.aspectMode) }
            }
        }
    }

    // ------------------------------------------------------------------ public

    fun play(request: PlaybackRequest) {
        scope.launch {
            val prefs = settings.current()
            val (url, format) = StreamUrlResolver.applyPreferred(request.url, prefs.preferredFormat, request.isLive)
            currentRequest = request
            currentUrl = url
            currentFormat = format
            recovery.reset(url, format)
            recoveryJob?.cancel()
            _state.value = PlayerUiState(
                mediaId = request.mediaId,
                title = request.title,
                isLive = request.isLive,
                isBuffering = true,
                format = format ?: StreamUrlResolver.detectFormat(url),
                canSwitchFormat = request.isLive && StreamUrlResolver.alternate(url, format) != null,
                aspectMode = prefs.aspectMode,
                softwareDecoding = preferSoftwareDecoders,
            )
            prepare(url, format, request.startPositionMs)
        }
    }

    fun isPlayingMedia(mediaId: String): Boolean = _state.value.mediaId == mediaId && _player.value != null

    fun togglePlayPause() {
        val p = _player.value ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.isPlaying) p.pause() else p.play()
    }

    fun pause() {
        _player.value?.pause()
    }

    fun resume() {
        _player.value?.play()
    }

    fun seekBy(deltaMs: Long) {
        val p = _player.value ?: return
        if (!p.isCurrentMediaItemSeekable) return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, duration))
        publishProgress()
    }

    fun seekTo(positionMs: Long) {
        _player.value?.seekTo(positionMs.coerceAtLeast(0))
        publishProgress()
    }

    fun currentPositionMs(): Long = _player.value?.currentPosition ?: 0

    /** Snapshot of the item that was playing before the last [stop] (used to persist resume points). */
    var lastSnapshot: PlaybackSnapshot? = null
        private set

    fun stop() {
        recoveryJob?.cancel()
        val current = _state.value
        val p = _player.value
        if (current.mediaId != null && p != null) {
            lastSnapshot = PlaybackSnapshot(
                mediaId = current.mediaId,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: current.durationMs,
            )
        }
        _player.value?.let {
            it.stop()
            it.clearMediaItems()
        }
        currentRequest = null
        _state.value = PlayerUiState(aspectMode = _state.value.aspectMode)
    }

    /** Called when the app goes to background: live streams are stopped, VOD paused. */
    fun onBackground() {
        val p = _player.value ?: return
        if (_state.value.isLive) p.stop() else p.pause()
    }

    fun onForeground() {
        val p = _player.value ?: return
        val req = currentRequest ?: return
        if (req.isLive && p.playbackState == Player.STATE_IDLE) {
            p.prepare()
            p.seekToDefaultPosition()
            p.play()
        }
    }

    fun retry() {
        val req = currentRequest ?: return
        play(req.copy(startPositionMs = currentPositionMs()))
    }

    fun setAspectMode(mode: AspectMode) {
        _state.update { it.copy(aspectMode = mode) }
        scope.launch { settings.setAspectMode(mode) }
    }

    fun cycleAspectMode() {
        val modes = AspectMode.entries
        setAspectMode(modes[(modes.indexOf(_state.value.aspectMode) + 1) % modes.size])
    }

    /** Manual TS/HLS toggle from the OSD. */
    fun switchFormat(target: StreamFormat) {
        val url = currentUrl ?: return
        val newUrl = StreamUrlResolver.toFormat(url, target) ?: return
        currentUrl = newUrl
        currentFormat = target
        recovery.reset(newUrl, target)
        _state.update { it.copy(format = target, error = null, recovery = null, isBuffering = true) }
        prepare(newUrl, target, if (_state.value.isLive) 0 else currentPositionMs())
        scope.launch { settings.setPreferredFormat(target) }
    }

    fun selectAudio(trackId: String) = applyOverride(C.TRACK_TYPE_AUDIO, trackId)

    fun selectSubtitle(trackId: String?) {
        val p = _player.value ?: return
        if (trackId == null) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            applyOverride(C.TRACK_TYPE_TEXT, trackId)
        }
    }

    fun release() {
        recoveryJob?.cancel()
        progressJob?.cancel()
        stabilityJob?.cancel()
        _player.value?.release()
        _player.value = null
    }

    // ---------------------------------------------------------------- internals

    private fun ensurePlayer(): ExoPlayer {
        _player.value?.let { return it }

        val selector = DefaultTrackSelector(appContext).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(Locale.getDefault().language)
                .setPreferredTextLanguage(Locale.getDefault().language)
                .setSelectUndeterminedTextLanguage(false)
                .setTunnelingEnabled(false)
                .build()
        }
        trackSelector = selector

        val codecSelector = if (preferSoftwareDecoders) {
            MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                    .sortedBy { it.hardwareAccelerated }
            }
        } else {
            MediaCodecSelector.DEFAULT
        }

        val renderers = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(codecSelector)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(appContext, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)
        _player.value = player
        return player
    }

    private fun dataSourceFactory(client: OkHttpClient, request: PlaybackRequest): HttpDataSource.Factory {
        val props = buildMap {
            // UserAgentInterceptor honours this header and replaces the global User-Agent with it.
            put(NetworkModule.HEADER_UA_OVERRIDE, request.userAgent ?: network.userAgent.value)
            request.referrer?.let { put("Referer", it) }
        }
        return OkHttpDataSource.Factory(client).setDefaultRequestProperties(props)
    }

    private fun prepare(url: String, format: StreamFormat?, startPositionMs: Long) {
        val request = currentRequest ?: return
        val player = ensurePlayer()

        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
            .setConstantBitrateSeekingEnabled(true)

        val mediaItem = MediaItem.Builder()
            .setMediaId(request.mediaId)
            .setUri(Uri.parse(url))
            .setMimeType(
                when (format ?: StreamUrlResolver.detectFormat(url)) {
                    StreamFormat.HLS -> MimeTypes.APPLICATION_M3U8
                    StreamFormat.TS -> MimeTypes.VIDEO_MP2T
                    null -> null
                },
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title)
                    .apply { request.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                    .build(),
            )
            .setSubtitleConfigurations(
                request.subtitles.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                        .setMimeType(subtitleMime(sub.url))
                        .setLanguage(sub.language)
                        .setLabel(sub.label)
                        .build()
                },
            )
            .apply {
                if (request.isLive) {
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(8_000)
                            .setMinPlaybackSpeed(0.97f)
                            .setMaxPlaybackSpeed(1.03f)
                            .build(),
                    )
                }
            }
            .build()

        val mediaSource = DefaultMediaSourceFactory(dataSourceFactory(network.streamingClient, request), extractors)
            .createMediaSource(mediaItem)

        if (request.isLive || startPositionMs <= 0) {
            player.setMediaSource(mediaSource, true)
        } else {
            player.setMediaSource(mediaSource, startPositionMs)
        }
        player.prepare()
        player.playWhenReady = true
        startProgressUpdates()
    }

    private fun subtitleMime(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ssa") || path.endsWith(".ass") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".xml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun applyOverride(trackType: Int, trackId: String) {
        val p = _player.value ?: return
        val (groupIndex, trackIndex) = trackId.split(':').let { it[0].toInt() to it[1].toInt() }
        val group = p.currentTracks.groups.getOrNull(groupIndex) ?: return
        if (group.type != trackType) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    private fun publishTracks(tracks: Tracks) {
        val audio = mutableListOf<TrackOption>()
        val text = mutableListOf<TrackOption>()
        tracks.groups.forEachIndexed { gi, group ->
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val format = group.getTrackFormat(ti)
                val target = if (group.type == C.TRACK_TYPE_AUDIO) audio else text
                target += TrackOption(
                    id = "$gi:$ti",
                    label = trackLabel(format, group.type, target.size + 1),
                    language = format.language,
                    selected = group.isTrackSelected(ti),
                )
            }
        }
        val textDisabled = _player.value?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        _state.update {
            it.copy(
                audioTracks = audio,
                subtitleTracks = text,
                subtitlesEnabled = !textDisabled && text.any { t -> t.selected },
            )
        }
    }

    private fun trackLabel(format: Format, type: Int, ordinal: Int): String {
        val language = format.language
            ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }
        val base = format.label ?: language ?: "Track $ordinal"
        val extra = buildList {
            if (type == C.TRACK_TYPE_AUDIO) {
                when (format.channelCount) {
                    1 -> add("Mono")
                    2 -> add("Stereo")
                    6 -> add("5.1")
                    8 -> add("7.1")
                }
                codecName(format.sampleMimeType)?.let(::add)
            } else {
                if (format.sampleMimeType == MimeTypes.APPLICATION_CEA608 || format.sampleMimeType == MimeTypes.APPLICATION_CEA708) add("CC")
            }
        }
        return if (extra.isEmpty()) base else "$base (${extra.joinToString(" · ")})"
    }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2/3"
        else -> null
    }

    private fun startProgressUpdates() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                publishProgress()
                delay(500)
            }
        }
    }

    private fun publishProgress() {
        val p = _player.value ?: return
        _state.update {
            it.copy(
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0,
                bufferedMs = p.bufferedPosition.coerceAtLeast(0),
            )
        }
    }

    // ----------------------------------------------------------------- recovery

    private fun classify(error: PlaybackException): FailureKind = when (error.errorCode) {
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> FailureKind.BEHIND_LIVE_WINDOW
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
            when (code) {
                401, 403 -> FailureKind.HTTP_FORBIDDEN
                404, 410 -> FailureKind.HTTP_NOT_FOUND
                in 500..599 -> FailureKind.HTTP_SERVER
                else -> FailureKind.UNKNOWN
            }
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> FailureKind.NETWORK
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> FailureKind.TIMEOUT
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        -> FailureKind.PARSING
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> FailureKind.DECODER
        else -> FailureKind.UNKNOWN
    }

    private fun handleError(error: PlaybackException) {
        val kind = classify(error)
        Log.w(TAG, "Playback error ${error.errorCodeName} -> $kind (stage ${recovery.currentStage})", error)
        val url = currentUrl ?: return
        if (!autoRecovery && kind != FailureKind.BEHIND_LIVE_WINDOW) {
            _state.update { it.copy(error = describe(kind), isBuffering = false, recovery = null) }
            return
        }
        val resumeAt = if (_state.value.isLive) 0 else currentPositionMs()
        when (val action = recovery.onFailure(kind, url, currentFormat)) {
            RecoveryAction.SeekToLiveEdge -> {
                _player.value?.let {
                    it.seekToDefaultPosition()
                    it.prepare()
                }
            }
            is RecoveryAction.Reconnect -> scheduleRecovery(action.delayMs, action.stage, "Reconnecting…") {
                prepare(action.url, currentFormat, resumeAt)
            }
            is RecoveryAction.SwitchFormat -> scheduleRecovery(action.delayMs, action.stage, "Switching to ${action.format.name}…") {
                currentUrl = action.url
                currentFormat = action.format
                _state.update { it.copy(format = action.format) }
                prepare(action.url, action.format, resumeAt)
            }
            is RecoveryAction.DecoderFallback -> scheduleRecovery(action.delayMs, action.stage, "Trying software decoding…") {
                preferSoftwareDecoders = true
                rebuildPlayer()
                currentUrl = action.url
                currentFormat = action.format
                _state.update { it.copy(format = action.format, softwareDecoding = true) }
                prepare(action.url, action.format, resumeAt)
            }
            is RecoveryAction.GiveUp -> _state.update {
                it.copy(error = describe(action.kind), recovery = null, isBuffering = false, isPlaying = false)
            }
        }
    }

    private fun scheduleRecovery(delayMs: Long, stage: Int, description: String, block: () -> Unit) {
        recoveryJob?.cancel()
        _state.update { it.copy(recovery = RecoveryStatus(stage, description), isBuffering = true, error = null) }
        recoveryJob = scope.launch {
            delay(delayMs)
            block()
        }
    }

    private fun rebuildPlayer() {
        _player.value?.let {
            it.removeListener(listener)
            it.release()
        }
        _player.value = null
        ensurePlayer()
    }

    private fun describe(kind: FailureKind): String = when (kind) {
        FailureKind.HTTP_FORBIDDEN -> "Access denied by the provider (HTTP 403). Check your subscription or User-Agent."
        FailureKind.HTTP_NOT_FOUND -> "Stream not found (HTTP 404)."
        FailureKind.HTTP_SERVER -> "The provider server is not responding (5xx)."
        FailureKind.NETWORK -> "Network connection failed."
        FailureKind.TIMEOUT -> "The stream timed out."
        FailureKind.PARSING -> "Unsupported or corrupt stream format."
        FailureKind.DECODER -> "This device cannot decode the stream."
        FailureKind.BEHIND_LIVE_WINDOW, FailureKind.UNKNOWN -> "Playback failed."
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_READY) {
                _state.update { it.copy(error = null) }
            }
            publishProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            stabilityJob?.cancel()
            if (isPlaying) {
                stabilityJob = scope.launch {
                    delay(STABLE_PLAYBACK_MS)
                    recovery.onPlaybackStable()
                    _state.update { it.copy(recovery = null) }
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) = publishTracks(tracks)

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    private companion object {
        const val TAG = "Media3PlayerAdapter"
        const val STABLE_PLAYBACK_MS = 15_000L
    }
}
