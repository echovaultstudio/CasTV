package app.castv

import app.castv.core.CatchupType
import app.castv.core.CatchupUrl
import app.castv.core.M3uParser
import app.castv.core.MediaKind
import app.castv.core.Names
import app.castv.core.StreamUrls
import app.castv.core.XmltvTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class CoreTest {
    private val utc = ZoneOffset.UTC

    @Test fun parsesM3uWithAttributesAndOptions() {
        val text = """
            #EXTM3U url-tvg="http://epg/a.xml.gz, http://epg/b.xml" catchup="shift" catchup-days="3"
            #EXTINF:-1 tvg-id="news.uk" tvg-name="News, Live" tvg-logo="http://l/n.png" group-title="UK" tvg-chno="7" catchup="flussonic",News, Live HD
            #EXTVLCOPT:http-user-agent=Box/1.0
            http://h/news/index.m3u8
            #EXTINF:-1,Plain
            #EXTGRP:Misc
            http://h/plain.ts|Referer=http://r/&User-Agent=X
            #EXTINF:-1 group-title="Films",A Film
            http://h:8080/movie/u/p/55.mkv
        """.trimIndent()
        val p = M3uParser.parse(text)
        assertEquals(listOf("http://epg/a.xml.gz", "http://epg/b.xml"), p.header.epgUrls)
        assertEquals("shift", p.header.catchupType)
        assertEquals(3, p.header.catchupDays)
        assertEquals(3, p.entries.size)
        val a = p.entries[0]
        assertEquals("News, Live HD", a.name)
        assertEquals("news.uk", a.tvgId)
        assertEquals("News, Live", a.tvgName)
        assertEquals("UK", a.group)
        assertEquals(7, a.number)
        assertEquals("flussonic", a.catchupType)
        assertEquals("Box/1.0", a.userAgent)
        val b = p.entries[1]
        assertEquals("Plain", b.name)
        assertEquals("Misc", b.group)
        assertEquals("http://h/plain.ts", b.url)
        assertEquals("http://r/", b.referrer)
        assertEquals("X", b.userAgent)
        assertEquals("", b.catchupType)
        assertEquals(MediaKind.MOVIE, p.entries[2].kind)
    }

    @Test fun catchupTemplates() {
        val start = 1_700_000_000L // 2023-11-14 22:13:20 UTC
        val end = start + 3600
        val now = start + 7200
        assertEquals(
            "http://h/c.m3u8?utc=$start&lutc=$now",
            CatchupUrl.build(CatchupType.SHIFT, "http://h/c.m3u8", "", start, end, now, utc),
        )
        assertEquals(
            "http://h/c.m3u8?a=1&utc=$start&lutc=$now",
            CatchupUrl.build(CatchupType.SHIFT, "http://h/c.m3u8?a=1", "", start, end, now, utc),
        )
        assertEquals(
            "http://arc/2023/11/14/22-13?d=3600&o=120",
            CatchupUrl.build(CatchupType.DEFAULT, "http://h/c", "http://arc/{Y}/{m}/{d}/{H}-{M}?d={duration}&o={offset:60}", start, end, now, utc),
        )
        assertEquals(
            "http://h/c.ts?s=20231114221320&e=$end",
            CatchupUrl.build(CatchupType.APPEND, "http://h/c.ts", "?s={utc:YmdHMS}&e=\${end}", start, end, now, utc),
        )
        assertEquals(
            "http://h/x?t=2023-11-14T22",
            CatchupUrl.fill("http://h/x?t=\${(b)yyyy-MM-dd'T'HH}", start, end, now, utc),
        )
    }

    @Test fun flussonicAndXtream() {
        val s = 1_700_000_000L
        assertEquals("http://h/ch1/index-$s-3600.m3u8?token=t",
            CatchupUrl.build(CatchupType.FLUSSONIC, "http://h/ch1/index.m3u8?token=t", "", s, s + 3600, s + 4000, utc))
        assertEquals("http://h/ch1/timeshift_abs-$s.ts",
            CatchupUrl.build(CatchupType.FLUSSONIC, "http://h/ch1/mpegts", "", s, s + 3600, s + 4000, utc))
        assertEquals("http://h:80/timeshift/u/p/60/2023-11-14:22-13/123.ts",
            CatchupUrl.build(CatchupType.XC, "http://h:80/live/u/p/123.ts", "", s, s + 3600, s + 4000, utc))
        assertEquals("http://h:80/timeshift/u/p/60/2023-11-14:22-13/123.m3u8",
            CatchupUrl.build(CatchupType.XC, "http://h:80/u/p/123.m3u8", "", s, s + 3600, s + 4000, utc))
        assertNull(CatchupUrl.build(CatchupType.NONE, "http://h/x", "", s, s + 1, s + 2, utc))
    }

    @Test fun xmltvTimes() {
        assertEquals(1_700_000_000L, XmltvTime.parse("20231114221320 +0000"))
        assertEquals(1_700_000_000L - 3600, XmltvTime.parse("20231114221320 +0100"))
        assertEquals(1_700_000_000L, XmltvTime.parse("20231114221320"))
        assertEquals(1_699_999_980L, XmltvTime.parse("202311142213 +0000"))
        assertNull(XmltvTime.parse("garbage"))
    }

    @Test fun namesAndProxy() {
        assertEquals("BBC One", Names.clean("UK: BBC One | HD", listOf("UK:", "US:"), listOf("| HD")))
        assertEquals(Names.matchKey("BBC One"), Names.matchKey("UK: BBC ONE FHD"))
        assertEquals(Names.matchKey("Das Erste"), Names.matchKey("das erste (backup)"))
        assertEquals("http://192.168.1.1:4022/udp/239.0.0.1:1234",
            StreamUrls.applyUdpProxy("udp://@239.0.0.1:1234", "192.168.1.1:4022"))
        assertEquals("udp://@239.0.0.1:1234", StreamUrls.applyUdpProxy("udp://@239.0.0.1:1234", ""))
        assertEquals(5400L, StreamUrls.parseHourMinutes("1:30"))
        assertEquals(-1800L, StreamUrls.parseHourMinutes("-0:30"))
        assertEquals("-0:30", StreamUrls.formatHourMinutes(-1800))
    }
}
