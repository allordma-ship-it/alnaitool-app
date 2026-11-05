package com.aurastream.tv.data.repository

import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.remote.M3uEntry

/**
 * Pure (Android-free) rules used while importing M3U playlists. Kept separate so they are
 * covered by plain JVM unit tests.
 */
object PlaylistImportRules {

    private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "flv", "mpg", "mpeg")

    /** "Show Name S01 E02", "Show.Name.s1e2", "Show - S01E02 - Title" */
    val SERIES_PATTERN = Regex("""^(.*?)[\s._\-|:]*[Ss](\d{1,2})[\s._\-]*[Ee](\d{1,3})""")

    /** Classifies an M3U entry as live, movie or series from its URL path and title. */
    fun classify(e: M3uEntry): String {
        val path = e.url.substringBefore('?').lowercase()
        if ("/series/" in path) return StreamType.SERIES
        val isVodPath = "/movie/" in path || "/movies/" in path || "/vod/" in path
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (isVodPath || ext in VOD_EXTENSIONS) {
            return if (SERIES_PATTERN.containsMatchIn(e.title)) StreamType.SERIES else StreamType.MOVIE
        }
        return StreamType.LIVE
    }

    /** 64-bit FNV-1a hash as hex: deterministic ids keep favourites stable across re-imports. */
    fun stableId(input: String): String {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325 (FNV offset basis)
        for (ch in input) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L // FNV prime
        }
        return java.lang.Long.toHexString(hash)
    }
}
