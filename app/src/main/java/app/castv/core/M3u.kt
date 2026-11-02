package app.castv.core

/** One entry of an M3U playlist, with every attribute the app understands. */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String = "",
    val tvgName: String = "",
    val logo: String = "",
    val group: String = "",
    val number: Int? = null,
    val catchupType: String = "",
    val catchupDays: Int = 0,
    val catchupSource: String = "",
    val tvgShiftHours: Double = 0.0,
    val userAgent: String = "",
    val referrer: String = "",
    val drmType: String = "",
    val drmKey: String = "",
    val kind: MediaKind = MediaKind.LIVE,
    val attributes: Map<String, String> = emptyMap(),
)

enum class MediaKind { LIVE, MOVIE, EPISODE }

/** Playlist-wide values from the #EXTM3U line. */
data class M3uHeader(
    val epgUrls: List<String> = emptyList(),
    val catchupType: String = "",
    val catchupDays: Int = 0,
    val catchupSource: String = "",
    val tvgShiftHours: Double = 0.0,
    val userAgent: String = "",
)

data class M3uPlaylist(val header: M3uHeader, val entries: List<M3uEntry>)

object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_\-:.]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s,]+))""")

    fun parseAttributes(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in attrRegex.findAll(text)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[5] }
            out[key] = value.trim()
        }
        return out
    }

    /** Splits "#EXTINF:-1 a="b",Name" into the attribute part and the name (after the first comma outside quotes). */
    fun splitExtinf(line: String): Pair<String, String> {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return line.substring(0, i) to line.substring(i + 1).trim()
        }
        return line to ""
    }

    fun extinfName(line: String): String = splitExtinf(line).second

    fun parse(text: String): M3uPlaylist = parse(text.lineSequence())

    fun parse(lines: Sequence<String>): M3uPlaylist {
        var header = M3uHeader()
        val entries = ArrayList<M3uEntry>()

        var pendingInfo: String? = null
        var pendingGroup = ""
        var pendingUa = ""
        var pendingRef = ""
        var pendingDrmType = ""
        var pendingDrmKey = ""

        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val a = parseAttributes(line.substring(7))
                    val epg = listOfNotNull(a["url-tvg"], a["x-tvg-url"], a["tvg-url"])
                        .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    header = M3uHeader(
                        epgUrls = epg,
                        catchupType = a["catchup"] ?: a["catchup-type"] ?: "",
                        catchupDays = (a["catchup-days"] ?: a["timeshift"])?.toIntOrNull() ?: 0,
                        catchupSource = a["catchup-source"] ?: "",
                        tvgShiftHours = a["tvg-shift"]?.toDoubleOrNull() ?: 0.0,
                        userAgent = a["user-agent"] ?: "",
                    )
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> pendingInfo = line
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> pendingUa = value
                        "http-referrer", "http-referer" -> pendingRef = value
                    }
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "inputstream.adaptive.license_type" -> pendingDrmType = value
                        "inputstream.adaptive.license_key" -> pendingDrmKey = value
                        "inputstream.adaptive.stream_headers" -> {
                            value.split('&').forEach { kv ->
                                val k = kv.substringBefore('=').lowercase()
                                val v = kv.substringAfter('=', "")
                                if (k == "user-agent") pendingUa = v
                                if (k == "referer" || k == "referrer") pendingRef = v
                            }
                        }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    val (urlPart, pipeHeaders) = splitPipeHeaders(line)
                    val split = info?.let { splitExtinf(it) }
                    val a = split?.let { parseAttributes(it.first.substringAfter(':')) } ?: emptyMap()
                    val name = split?.second.orEmpty()
                        .ifEmpty { a["tvg-name"].orEmpty() }
                        .ifEmpty { urlPart.substringAfterLast('/') }
                    val ua = pipeHeaders["user-agent"] ?: a["user-agent"] ?: a["http-user-agent"] ?: pendingUa
                    val ref = pipeHeaders["referer"] ?: pipeHeaders["referrer"] ?: a["http-referrer"] ?: pendingRef
                    entries += M3uEntry(
                        name = name,
                        url = urlPart,
                        tvgId = a["tvg-id"].orEmpty(),
                        tvgName = a["tvg-name"].orEmpty(),
                        logo = a["tvg-logo"] ?: a["logo"] ?: "",
                        group = a["group-title"]?.takeIf { it.isNotEmpty() } ?: pendingGroup,
                        number = (a["tvg-chno"] ?: a["channel-number"] ?: a["tvg-num"])?.toIntOrNull(),
                        catchupType = a["catchup"] ?: a["catchup-type"] ?: "",
                        catchupDays = (a["catchup-days"] ?: a["timeshift"] ?: a["tvg-rec"])?.toIntOrNull() ?: 0,
                        catchupSource = a["catchup-source"].orEmpty(),
                        tvgShiftHours = a["tvg-shift"]?.toDoubleOrNull() ?: 0.0,
                        userAgent = ua,
                        referrer = ref,
                        drmType = pendingDrmType,
                        drmKey = pendingDrmKey,
                        kind = guessKind(urlPart),
                        attributes = a,
                    )
                    pendingInfo = null; pendingGroup = ""; pendingUa = ""; pendingRef = ""
                    pendingDrmType = ""; pendingDrmKey = ""
                }
            }
        }
        return M3uPlaylist(header, entries)
    }

    /** Kodi-style "url|User-Agent=x&Referer=y". */
    fun splitPipeHeaders(line: String): Pair<String, Map<String, String>> {
        val i = line.indexOf('|')
        if (i < 0) return line to emptyMap()
        val headers = line.substring(i + 1).split('&').mapNotNull {
            val k = it.substringBefore('=').trim()
            if (k.isEmpty()) null else k.lowercase() to it.substringAfter('=', "").trim()
        }.toMap()
        return line.substring(0, i).trim() to headers
    }

    fun guessKind(url: String): MediaKind {
        val path = url.substringBefore('?').lowercase()
        return when {
            "/movie/" in path -> MediaKind.MOVIE
            "/series/" in path -> MediaKind.EPISODE
            else -> MediaKind.LIVE
        }
    }
}
