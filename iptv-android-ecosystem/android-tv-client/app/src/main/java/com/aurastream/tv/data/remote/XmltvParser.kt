package com.aurastream.tv.data.remote

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class XmltvProgramme(
    val channelId: String,
    val title: String,
    val description: String?,
    val start: Long,
    val end: Long,
)

/**
 * Streaming XMLTV parser built on [XmlPullParser]. Transparently handles gzip
 * compressed guides (`.xml.gz`) by sniffing the magic bytes.
 *
 * Only programmes for [wantedChannels] that overlap [windowStart, windowEnd] are
 * emitted, which keeps multi-hundred-megabyte guides manageable on TV hardware.
 */
class XmltvParser {

    fun parse(
        input: InputStream,
        wantedChannels: Set<String>,
        windowStart: Long,
        windowEnd: Long,
        onProgramme: (XmltvProgramme) -> Unit,
    ) {
        val stream = maybeGunzip(input)
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, null)
        }

        var channel: String? = null
        var start = 0L
        var end = 0L
        var title: String? = null
        var desc: String? = null
        var inProgramme = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        end = parseTime(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                        inProgramme = channel != null && channel in wantedChannels &&
                            start > 0 && end > start && end >= windowStart && start <= windowEnd
                        if (!inProgramme) skip(parser)
                    }
                    "title" -> if (inProgramme && title == null) title = parser.nextText()
                    "desc" -> if (inProgramme && desc == null) desc = parser.nextText()
                    "channel" -> skip(parser)
                }
                XmlPullParser.END_TAG -> if (parser.name == "programme" && inProgramme) {
                    onProgramme(XmltvProgramme(channel!!, title?.trim().orEmpty(), desc?.trim(), start, end))
                    inProgramme = false
                }
            }
            event = parser.next()
        }
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024)) else buffered
    }

    // ThreadLocal.withInitial() needs API 26; minSdk is 24.
    private val formatWithZone = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    }
    private val formatUtc = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() =
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    /** XMLTV dates: `20240131203000 +0100` (zone optional, defaults to UTC). */
    internal fun parseTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        val v = value.trim()
        return runCatching {
            if (v.length > 15 && (v.contains('+') || v.contains('-'))) {
                formatWithZone.get()!!.parse(v.substring(0, 14) + " " + v.substring(14).trim())!!.time
            } else {
                formatUtc.get()!!.parse(v.take(14))!!.time
            }
        }.getOrDefault(0)
    }
}
