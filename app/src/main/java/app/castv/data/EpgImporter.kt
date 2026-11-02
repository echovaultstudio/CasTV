package app.castv.data

import android.content.Context
import android.util.Xml
import app.castv.core.Names
import app.castv.core.XmltvTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** Imports an XMLTV guide into the database, keeping only the configured window of days. */
class EpgImporter(private val context: Context, private val db: AppDb, private val settings: Settings) {

    data class Result(val channels: Int, val programmes: Int)

    suspend fun import(source: EpgSourceEntity, progress: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        progress("Downloading ${source.name}…")
        val now = System.currentTimeMillis() / 1000
        val from = now - settings.epgPastDays * 86400L
        val to = now + settings.epgFutureDays.coerceAtLeast(1) * 86400L
        val keepDesc = settings.epgStoreDescriptions
        val shift = source.shiftSeconds

        db.epg().deleteProgrammes(source.id)
        db.epg().deleteChannels(source.id)

        val channels = ArrayList<EpgChannelEntity>()
        val batch = ArrayList<ProgrammeEntity>(2000)
        var total = 0

        Http.open(context, source.url).use { input ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id").orEmpty()
                            var name = ""
                            var icon = ""
                            readChildren(parser, "channel") { tag ->
                                when (tag) {
                                    "display-name" -> { val t = text(parser).trim(); if (name.isEmpty()) name = t }
                                    "icon" -> { if (icon.isEmpty()) icon = parser.getAttributeValue(null, "src").orEmpty(); skip(parser) }
                                    else -> skip(parser)
                                }
                            }
                            if (id.isNotEmpty()) channels += EpgChannelEntity(source.id, id, name.ifEmpty { id }, icon, Names.matchKey(name.ifEmpty { id }))
                        }
                        "programme" -> {
                            val start = XmltvTime.parse(parser.getAttributeValue(null, "start").orEmpty(), shift)
                            val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop").orEmpty(), shift)
                            val ch = parser.getAttributeValue(null, "channel").orEmpty()
                            if (start == null || stop == null || stop < from || start > to) {
                                skip(parser)
                            } else {
                                var title = ""; var sub = ""; var desc = ""; var cat = ""; var icon = ""; var ep = ""; var rating = ""; var year = ""
                                readChildren(parser, "programme") { tag ->
                                    when (tag) {
                                        "title" -> { val t = text(parser); if (title.isEmpty()) title = t.trim() }
                                        "sub-title" -> sub = text(parser).trim()
                                        "desc" -> { val t = text(parser); if (keepDesc && desc.isEmpty()) desc = t.trim() }
                                        "category" -> { val t = text(parser).trim(); if (cat.isEmpty()) cat = t }
                                        "icon" -> { icon = parser.getAttributeValue(null, "src").orEmpty(); skip(parser) }
                                        "episode-num" -> {
                                            val system = parser.getAttributeValue(null, "system").orEmpty()
                                            val t = text(parser).trim()
                                            if (system == "onscreen" || ep.isEmpty()) ep = if (system == "xmltv_ns") xmltvNs(t) else t
                                        }
                                        "date" -> year = text(parser).trim().take(4)
                                        "rating" -> { rating = readValue(parser, "rating") }
                                        else -> skip(parser)
                                    }
                                }
                                batch += ProgrammeEntity(sourceId = source.id, channelId = ch, start = start, stop = stop, title = title,
                                    subTitle = sub, description = desc, category = cat, icon = icon, episode = ep, rating = rating, year = year)
                                if (batch.size >= 2000) {
                                    db.epg().insertProgrammes(batch.toList()); total += batch.size; batch.clear()
                                    progress("${source.name}: $total programs")
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        }
        if (batch.isNotEmpty()) { db.epg().insertProgrammes(batch.toList()); total += batch.size }
        channels.chunked(500).forEach { db.epg().insertChannels(it) }
        db.epg().upsertSource(source.copy(lastUpdated = System.currentTimeMillis(), lastError = "", channelCount = channels.size, programmeCount = total))
        Result(channels.size, total)
    }

    private inline fun readChildren(parser: XmlPullParser, parent: String, onTag: (String) -> Unit) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val before = parser.depth
                    onTag(parser.name)
                    // onTag consumed the element (nextText/skip) unless it left us inside it.
                    if (parser.eventType == XmlPullParser.START_TAG && parser.depth == before) skip(parser)
                }
                XmlPullParser.END_TAG -> if (parser.name == parent) depth = 0
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun readValue(parser: XmlPullParser, parent: String): String {
        var value = ""
        readChildren(parser, parent) { tag -> if (tag == "value") value = text(parser).trim() else skip(parser) }
        return value
    }

    /** Element text; elements with unexpected markup inside are skipped instead of failing the import. */
    private fun text(parser: XmlPullParser): String = try {
        parser.nextText()
    } catch (e: Exception) {
        skip(parser); ""
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** "1.4.0/1" (zero-based season.episode.part) to "S02 E05". */
    private fun xmltvNs(v: String): String {
        val parts = v.split('.')
        val s = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()
        val e = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()
        return listOfNotNull(s?.let { "S%02d".format(it + 1) }, e?.let { "E%02d".format(it + 1) }).joinToString(" ")
    }
}
