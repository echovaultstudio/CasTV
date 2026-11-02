package app.castv.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class CatchupType {
    NONE, DEFAULT, APPEND, SHIFT, FLUSSONIC, FLUSSONIC_TS, XC;

    companion object {
        fun parse(value: String?): CatchupType = when (value?.trim()?.lowercase()) {
            null, "" -> NONE
            "default" -> DEFAULT
            "append" -> APPEND
            "shift", "timeshift" -> SHIFT
            "flussonic", "flussonic-hls", "fs" -> FLUSSONIC
            "flussonic-ts", "fs-ts" -> FLUSSONIC_TS
            "xc", "xtream" -> XC
            else -> DEFAULT
        }
    }
}

/**
 * Builds the address of a past programme from the live address. [start] and [end] are epoch seconds of the
 * programme (or of the point to start playing from), [now] is the current time in epoch seconds.
 */
object CatchupUrl {

    fun build(
        type: CatchupType,
        liveUrl: String,
        source: String,
        start: Long,
        end: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val duration = (end - start).coerceAtLeast(60)
        return when (type) {
            CatchupType.NONE -> null
            CatchupType.DEFAULT -> when {
                source.isBlank() -> appendQuery(liveUrl, "utc=$start&lutc=$now")
                source.startsWith("http", ignoreCase = true) || source.startsWith("rtmp") ->
                    fill(source, start, end, now, zone)
                else -> fill(liveUrl + source, start, end, now, zone)
            }
            CatchupType.APPEND -> fill(liveUrl + source, start, end, now, zone)
            CatchupType.SHIFT -> if (source.isNotBlank()) fill(appendQuery(liveUrl, source.removePrefix("?").removePrefix("&")), start, end, now, zone)
                else appendQuery(liveUrl, "utc=$start&lutc=$now")
            CatchupType.FLUSSONIC, CatchupType.FLUSSONIC_TS -> flussonic(liveUrl, start, duration, type == CatchupType.FLUSSONIC_TS)
            CatchupType.XC -> xtream(liveUrl, start, duration, zone)
        }
    }

    fun appendQuery(url: String, query: String): String =
        if (query.isEmpty()) url else url + (if ('?' in url) "&" else "?") + query

    /** http://host/channel/index.m3u8 → http://host/channel/index-START-DURATION.m3u8 (or the mpegts form). */
    fun flussonic(liveUrl: String, start: Long, duration: Long, forceTs: Boolean): String {
        val query = liveUrl.substringAfter('?', "")
        val base = liveUrl.substringBefore('?')
        val slash = base.lastIndexOf('/')
        val dir = base.substring(0, slash + 1)
        val file = base.substring(slash + 1)
        val q = if (query.isEmpty()) "" else "?$query"
        return when {
            !forceTs && file.endsWith(".m3u8") -> {
                val stem = file.removeSuffix(".m3u8")
                if (stem == "mono" || stem == "index" || stem == "video") "$dir$stem-$start-$duration.m3u8$q"
                else "${dir}index-$start-$duration.m3u8$q"
            }
            file == "mpegts" || file.endsWith(".ts") || forceTs -> "${dir}timeshift_abs-$start.ts$q"
            else -> "$dir$file/index-$start-$duration.m3u8$q"
        }
    }

    /** http://host:port/live/user/pass/123.ts → http://host:port/timeshift/user/pass/MINUTES/YYYY-MM-DD:HH-MM/123.ts */
    fun xtream(liveUrl: String, start: Long, duration: Long, zone: ZoneId): String? {
        val m = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.\w+)?$""").find(liveUrl.substringBefore('?'))
            ?: return null
        val (host, user, pass, id) = m.destructured
        val ext = m.groupValues[5].ifEmpty { ".ts" }
        val minutes = (duration + 59) / 60
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").format(Instant.ofEpochSecond(start).atZone(zone))
        return "$host/timeshift/$user/$pass/$minutes/$stamp/$id$ext"
    }

    private val tokenRegex = Regex("""\$?\{([^}]*)}""")

    /** Replaces every placeholder form used by playlists ({utc}, ${start}, {Y}, {utc:Y-m-d}, ${(b)yyyyMMdd}, …). */
    fun fill(template: String, start: Long, end: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val startZ = Instant.ofEpochSecond(start).atZone(zone)
        val endZ = Instant.ofEpochSecond(end).atZone(zone)
        val nowZ = Instant.ofEpochSecond(now).atZone(zone)
        val duration = (end - start).coerceAtLeast(0)
        val offset = (now - start).coerceAtLeast(0)
        return tokenRegex.replace(template) { m ->
            val body = m.groupValues[1]
            val name = body.substringBefore(':').trim()
            val arg = if (':' in body) body.substringAfter(':') else null
            when {
                body.startsWith("(b)") -> javaFormat(body.removePrefix("(b)"), startZ)
                body.startsWith("(e)") -> javaFormat(body.removePrefix("(e)"), endZ)
                body.startsWith("(n)") -> javaFormat(body.removePrefix("(n)"), nowZ)
                else -> when (name.lowercase()) {
                    "utc", "start", "timestamp_start" -> if (arg != null) strftime(arg, startZ) else start.toString()
                    "utcend", "end", "timestamp_end" -> if (arg != null) strftime(arg, endZ) else end.toString()
                    "lutc", "now", "timestamp" -> if (arg != null) strftime(arg, nowZ) else now.toString()
                    "duration" -> (duration / (arg?.toLongOrNull()?.takeIf { it > 0 } ?: 1)).toString()
                    "offset" -> (offset / (arg?.toLongOrNull()?.takeIf { it > 0 } ?: 1)).toString()
                    else -> when (name) {
                        "Y" -> "%04d".format(startZ.year)
                        "m" -> "%02d".format(startZ.monthValue)
                        "d" -> "%02d".format(startZ.dayOfMonth)
                        "H" -> "%02d".format(startZ.hour)
                        "M" -> "%02d".format(startZ.minute)
                        "S" -> "%02d".format(startZ.second)
                        else -> m.value
                    }
                }
            }
        }
    }

    /** "Y-m-d H:M:S" letters (as used after "utc:") to a formatted time. */
    fun strftime(pattern: String, t: ZonedDateTime): String = buildString {
        for (c in pattern) append(
            when (c) {
                'Y' -> "%04d".format(t.year)
                'y' -> "%02d".format(t.year % 100)
                'm' -> "%02d".format(t.monthValue)
                'd' -> "%02d".format(t.dayOfMonth)
                'H' -> "%02d".format(t.hour)
                'M' -> "%02d".format(t.minute)
                'S' -> "%02d".format(t.second)
                else -> c.toString()
            }
        )
    }

    private fun javaFormat(pattern: String, t: ZonedDateTime): String =
        runCatching { DateTimeFormatter.ofPattern(pattern).format(t) }.getOrElse { strftime(pattern, t) }
}
