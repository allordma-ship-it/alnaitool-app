package com.aurastream.tv.player

import com.aurastream.tv.data.local.StreamFormat

/**
 * Pure URL logic for switching IPTV streams between MPEG-TS and HLS containers.
 *
 * Xtream-style panels expose the same live stream as
 *   `http://host:port/live/<user>/<pass>/<id>.ts` and `.../<id>.m3u8`
 * and many M3U exports use the extension-less short form `http://host:port/<user>/<pass>/<id>`.
 */
object StreamUrlResolver {

    private val XTREAM_LIVE = Regex("""/live/[^/]+/[^/]+/\d+(\.(ts|m3u8))?$""", RegexOption.IGNORE_CASE)
    private val XTREAM_SHORT = Regex("""^/[^/]+/[^/]+/\d+(\.(ts|m3u8))?$""", RegexOption.IGNORE_CASE)
    private val FORMAT_QUERY_KEYS = listOf("output", "extension", "type")

    /** Container format implied by the URL, or null when unknown (progressive files, opaque URLs). */
    fun detectFormat(url: String): StreamFormat? {
        val (path, query) = split(url)
        val lower = path.lowercase()
        return when {
            lower.endsWith(".m3u8") || lower.endsWith(".m3u") -> StreamFormat.HLS
            lower.endsWith(".ts") -> StreamFormat.TS
            else -> queryFormat(query)
        }
    }

    /** True for live URLs that follow the Xtream conventions (safe to rewrite proactively). */
    fun isXtreamLive(url: String): Boolean {
        val path = split(url).first.substringAfter("://").let { p -> p.indexOf('/').let { if (it >= 0) p.substring(it) else "" } }
        return XTREAM_LIVE.containsMatchIn(path) || XTREAM_SHORT.matches(path)
    }

    /**
     * Rewrites [url] to the requested container. Returns null if the URL cannot be expressed
     * in [target] (e.g. a progressive `.mp4`).
     */
    fun toFormat(url: String, target: StreamFormat): String? {
        val (path, query) = split(url)
        val ext = if (target == StreamFormat.HLS) "m3u8" else "ts"
        val lower = path.lowercase()

        // 1) Explicit format in the query string (?output=ts / &extension=m3u8)
        if (query != null && queryFormat(query) != null) {
            val rewritten = query.split('&').joinToString("&") { pair ->
                val key = pair.substringBefore('=').lowercase()
                if (key in FORMAT_QUERY_KEYS && queryFormat(pair) != null) {
                    val value = if (key == "output" && target == StreamFormat.HLS) "hls" else ext
                    "${pair.substringBefore('=')}=$value"
                } else {
                    pair
                }
            }
            return "$path?$rewritten"
        }

        // 2) Extension swap
        val newPath = when {
            lower.endsWith(".ts") -> path.dropLast(3) + ".$ext"
            lower.endsWith(".m3u8") -> path.dropLast(5) + ".$ext"
            isXtreamLive(url) -> "$path.$ext" // extension-less Xtream short form
            else -> return null
        }
        return if (query != null) "$newPath?$query" else newPath
    }

    /** Alternate container used by automatic recovery. */
    fun alternate(url: String, current: StreamFormat?): Pair<String, StreamFormat>? {
        val from = current ?: detectFormat(url) ?: StreamFormat.TS
        val target = if (from == StreamFormat.TS) StreamFormat.HLS else StreamFormat.TS
        return toFormat(url, target)?.let { it to target }
    }

    /** Applies the user's preferred container to Xtream live URLs; other URLs are left untouched. */
    fun applyPreferred(url: String, preferred: StreamFormat, isLive: Boolean): Pair<String, StreamFormat?> {
        if (!isLive || !isXtreamLive(url)) return url to detectFormat(url)
        if (detectFormat(url) == preferred) return url to preferred
        return (toFormat(url, preferred) ?: return url to detectFormat(url)) to preferred
    }

    private fun split(url: String): Pair<String, String?> {
        val noFragment = url.substringBefore('#')
        val q = noFragment.indexOf('?')
        return if (q >= 0) noFragment.substring(0, q) to noFragment.substring(q + 1) else noFragment to null
    }

    private fun queryFormat(query: String?): StreamFormat? {
        if (query.isNullOrEmpty()) return null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', "").lowercase()
            if (key !in FORMAT_QUERY_KEYS) continue
            when (value) {
                "ts", "mpegts" -> return StreamFormat.TS
                "m3u8", "hls" -> return StreamFormat.HLS
            }
        }
        return null
    }
}
