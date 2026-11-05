package com.aurastream.tv.data.remote

import java.io.BufferedReader

/** Global attributes from the `#EXTM3U` header line. */
data class M3uHeader(
    val epgUrls: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
)

data class M3uEntry(
    val title: String,
    val url: String,
    val durationSecs: Int,
    val tvgId: String?,
    val tvgName: String?,
    val tvgLogo: String?,
    val groupTitle: String?,
    val userAgent: String?,
    val referrer: String?,
    val attributes: Map<String, String>,
)

class M3uDocument(val header: M3uHeader, val entries: Sequence<M3uEntry>)

/**
 * Streaming M3U / M3U8 (extended M3U, "m3u_plus") parser.
 *
 * - Works line-by-line over a [BufferedReader]: memory stays flat regardless of
 *   playlist size (tested with > 50 000 entries), nothing is materialised up front.
 * - Attribute parsing is a hand-written single pass scanner (no regex per line),
 *   tolerant to unquoted values, single quotes, commas inside quoted titles,
 *   CRLF line endings, BOMs and missing headers.
 * - Supports `#EXTGRP`, `#EXTVLCOPT:http-user-agent|http-referrer` and
 *   `#KODIPROP`/other directives (ignored).
 *
 * The returned [M3uDocument.entries] sequence is single-use and must be consumed
 * on a background dispatcher while the reader is open.
 */
class M3uStreamParser {

    fun parse(reader: BufferedReader): M3uDocument {
        var firstLine = reader.readLine()
        while (firstLine != null && firstLine.isBlank()) firstLine = reader.readLine()
        firstLine = firstLine?.removePrefix(BOM)?.trim()

        val header: M3uHeader
        var pending: String? = null
        if (firstLine != null && firstLine.startsWith("#EXTM3U", ignoreCase = true)) {
            val attrs = parseAttributes(firstLine, 7, firstLine.length)
            val epg = sequenceOf("url-tvg", "x-tvg-url", "tvg-url")
                .mapNotNull { attrs[it] }
                .flatMap { it.split(',').asSequence() }
                .map { it.trim() }
                .filter { it.startsWith("http", ignoreCase = true) }
                .distinct()
                .toList()
            header = M3uHeader(epg, attrs)
        } else {
            header = M3uHeader()
            pending = firstLine // headerless playlist: first line is data
        }

        val entries = sequence {
            var info: String? = null
            var group: String? = null
            var userAgent: String? = null
            var referrer: String? = null

            var line: String? = pending ?: reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> Unit
                    trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                        info = trimmed
                        group = null
                        userAgent = null
                        referrer = null
                    }
                    trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> group = trimmed.substring(8).trim()
                    trimmed.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val opt = trimmed.substring(11)
                        val eq = opt.indexOf('=')
                        if (eq > 0) {
                            val key = opt.substring(0, eq).trim().lowercase()
                            val value = opt.substring(eq + 1).trim()
                            when (key) {
                                "http-user-agent" -> userAgent = value
                                "http-referrer", "http-referer" -> referrer = value
                            }
                        }
                    }
                    trimmed.startsWith("#") -> Unit // other directives / comments
                    else -> {
                        val entry = buildEntry(info, trimmed, group, userAgent, referrer)
                        if (entry != null) yield(entry)
                        info = null
                        group = null
                        userAgent = null
                        referrer = null
                    }
                }
                line = reader.readLine()
            }
        }
        return M3uDocument(header, entries)
    }

    private fun buildEntry(info: String?, url: String, extGroup: String?, ua: String?, ref: String?): M3uEntry? {
        if (!isLikelyUrl(url)) return null
        if (info == null) {
            return M3uEntry(
                title = url.substringAfterLast('/').substringBefore('?').ifBlank { url },
                url = url, durationSecs = -1, tvgId = null, tvgName = null, tvgLogo = null,
                groupTitle = extGroup, userAgent = ua, referrer = ref, attributes = emptyMap(),
            )
        }

        // "#EXTINF:<duration> key="value" ...,Title"
        val colon = info.indexOf(':')
        var pos = if (colon >= 0) colon + 1 else info.length
        val durStart = pos
        while (pos < info.length && (info[pos] == '-' || info[pos] == '.' || info[pos].isDigit())) pos++
        val duration = info.substring(durStart, pos).toDoubleOrNull()?.toInt() ?: -1

        val titleComma = findTitleComma(info, pos)
        val attrs = parseAttributes(info, pos, if (titleComma >= 0) titleComma else info.length)
        val rawTitle = if (titleComma >= 0) info.substring(titleComma + 1).trim() else ""
        val title = rawTitle.ifEmpty { attrs["tvg-name"].orEmpty() }.ifEmpty { url.substringAfterLast('/') }

        return M3uEntry(
            title = title,
            url = url,
            durationSecs = duration,
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
            tvgLogo = (attrs["tvg-logo"] ?: attrs["logo"])?.takeIf { it.isNotBlank() },
            groupTitle = (attrs["group-title"]?.takeIf { it.isNotBlank() }) ?: extGroup,
            userAgent = ua ?: attrs["user-agent"] ?: attrs["http-user-agent"],
            referrer = ref ?: attrs["referrer"] ?: attrs["http-referrer"],
            attributes = attrs,
        )
    }

    /** The title starts after the first comma that is not inside a quoted attribute value. */
    private fun findTitleComma(s: String, from: Int): Int {
        var quote: Char? = null
        for (i in from until s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                // only treat as quote when it opens an attribute value (preceded by '=')
                if (i > 0 && s[i - 1] == '=') quote = c
            } else if (c == ',') {
                return i
            }
        }
        return -1
    }

    /** Parses `key="value" key2=value2` pairs between [start] and [end]. Keys are lower-cased. */
    internal fun parseAttributes(s: String, start: Int, end: Int): Map<String, String> {
        val result = HashMap<String, String>(8)
        var i = start
        while (i < end) {
            while (i < end && s[i].isWhitespace()) i++
            val keyStart = i
            while (i < end && s[i] != '=' && !s[i].isWhitespace()) i++
            if (i >= end || s[i] != '=') {
                // bare token without value – skip it
                while (i < end && !s[i].isWhitespace()) i++
                continue
            }
            val key = s.substring(keyStart, i).lowercase()
            i++ // '='
            val value: String
            if (i < end && (s[i] == '"' || s[i] == '\'')) {
                val q = s[i]
                val vStart = ++i
                while (i < end && s[i] != q) i++
                value = s.substring(vStart, minOf(i, end))
                i++ // closing quote
            } else {
                val vStart = i
                while (i < end && !s[i].isWhitespace()) i++
                value = s.substring(vStart, i)
            }
            if (key.isNotEmpty()) result[key] = value.trim()
        }
        return result
    }

    private fun isLikelyUrl(s: String): Boolean {
        val idx = s.indexOf("://")
        return idx in 2..10 || s.startsWith("rtmp", ignoreCase = true)
    }

    private companion object {
        const val BOM = "\uFEFF"
    }
}
