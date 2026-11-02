package app.castv.core

import java.text.Normalizer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Names {
    /** Removes the configured prefixes ("US:, UK:") and suffixes ("| HD, FHD") from a channel name. */
    fun clean(name: String, prefixes: List<String>, suffixes: List<String>): String {
        var n = name.trim()
        for (p in prefixes) if (p.isNotBlank() && n.startsWith(p.trim(), ignoreCase = true)) n = n.substring(p.trim().length).trim()
        for (s in suffixes) if (s.isNotBlank() && n.endsWith(s.trim(), ignoreCase = true)) n = n.substring(0, n.length - s.trim().length).trim()
        return n.ifEmpty { name.trim() }
    }

    fun splitList(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private val qualityWords = Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|1080p|720p|backup|raw)\b""")

    /** A key for matching a channel with EPG names or logo files despite spacing, case and quality tags. */
    fun matchKey(name: String, inexact: Boolean = true): String {
        var s = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        if (inexact) {
            s = s.replace(Regex("""^[a-z]{2,3}\s*[:|]\s*"""), "")
            s = s.replace(Regex("""[\[(].*?[])]"""), " ")
            s = qualityWords.replace(s, " ")
        }
        return s.replace(Regex("[^a-z0-9+]"), "")
    }
}

object XmltvTime {
    private val withZone = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")

    /** "20240131203000 +0100" (zone optional, seconds optional) to epoch seconds. */
    fun parse(value: String, shiftSeconds: Long = 0): Long? {
        val v = value.trim()
        if (v.length < 12) return null
        return runCatching {
            val digits = v.takeWhile { it.isDigit() }.padEnd(14, '0').take(14)
            val zonePart = v.substring(v.takeWhile { it.isDigit() }.length).trim()
            val zone = if (zonePart.isEmpty()) "+0000" else zonePart.replace(":", "").let { if (it.length == 5) it else "+0000" }
            OffsetDateTime.parse("$digits $zone", withZone).toEpochSecond() + shiftSeconds
        }.getOrNull()
    }

    fun format(epoch: Long): String =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").format(OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(epoch), ZoneOffset.UTC))
}

object StreamUrls {
    /** Turns a multicast address into a UDP-to-HTTP proxy address when a proxy (host:port) is set. */
    fun applyUdpProxy(url: String, proxy: String): String {
        if (proxy.isBlank()) return url
        val m = Regex("""^(udp|rtp)://@?([^/?]+)(.*)$""", RegexOption.IGNORE_CASE).find(url) ?: return url
        val scheme = m.groupValues[1].lowercase()
        val p = proxy.trim().removePrefix("http://").trimEnd('/')
        return "http://$p/$scheme/${m.groupValues[2]}"
    }

    /** Minutes and seconds "h:mm" text to seconds. */
    fun parseHourMinutes(text: String): Long? {
        val neg = text.trim().startsWith("-")
        val parts = text.trim().removePrefix("-").removePrefix("+").split(':')
        val h = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val m = parts.getOrNull(1)?.toLongOrNull() ?: 0
        val s = parts.getOrNull(2)?.toLongOrNull() ?: 0
        val total = h * 3600 + m * 60 + s
        return if (neg) -total else total
    }

    fun formatHourMinutes(seconds: Long): String {
        val sign = if (seconds < 0) "-" else ""
        val a = kotlin.math.abs(seconds)
        return "%s%d:%02d".format(sign, a / 3600, (a % 3600) / 60)
    }
}
