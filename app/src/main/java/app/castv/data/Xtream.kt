package app.castv.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonObject.str(key: String): String = when (val v = this[key]) {
    null, is JsonNull -> ""
    is JsonPrimitive -> v.contentOrNull.orEmpty()
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
    else -> ""
}
fun JsonObject.long(key: String): Long = str(key).toDoubleOrNull()?.toLong() ?: 0
fun JsonObject.double(key: String): Double = str(key).toDoubleOrNull() ?: 0.0

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String,
    val plot: String,
    val image: String,
    val durationSecs: Long,
    val url: String,
)

data class XtreamSeriesInfo(val seasons: List<Int>, val episodes: List<XtreamEpisode>, val plot: String, val cast: String, val director: String, val genre: String, val year: String, val backdrop: String, val trailer: String)

/** Client for the Xtream Codes player API. */
class XtreamClient(private val context: Context, server: String, private val user: String, private val pass: String, private val userAgent: String) {
    val base: String = normalizeServer(server)

    companion object {
        fun normalizeServer(s: String): String {
            var u = s.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            u = u.removeSuffix("/player_api.php").removeSuffix("/get.php").removeSuffix("/c")
            return u
        }

        /** Recognises a pasted get.php?username=…&password=… address so the wizard can fill the fields. */
        fun fromM3uUrl(url: String): Triple<String, String, String>? {
            val m = Regex("""^(https?://[^/]+)/get\.php\?(.*)$""").find(url.trim()) ?: return null
            val q = m.groupValues[2].split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val u = q["username"] ?: return null
            val p = q["password"] ?: return null
            return Triple(m.groupValues[1], u, p)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(action: String?, extra: String = ""): JsonElement {
        val url = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}" +
            (if (action != null) "&action=$action" else "") + extra
        val text = Http.getText(context, url, userAgent)
        return json.parseToJsonElement(text.ifBlank { "[]" })
    }

    fun accountInfo(): JsonObject? = api(null).obj()

    fun liveCategories(): Map<String, String> = categories("get_live_categories")
    fun vodCategories(): Map<String, String> = categories("get_vod_categories")
    fun seriesCategories(): Map<String, String> = categories("get_series_categories")

    private fun categories(action: String): Map<String, String> =
        api(action).arr().orEmpty().mapNotNull { it.obj() }.associate { it.str("category_id") to it.str("category_name") }

    fun liveStreams(): List<JsonObject> = api("get_live_streams").arr().orEmpty().mapNotNull { it.obj() }
    fun vodStreams(): List<JsonObject> = api("get_vod_streams").arr().orEmpty().mapNotNull { it.obj() }
    fun series(): List<JsonObject> = api("get_series").arr().orEmpty().mapNotNull { it.obj() }

    fun vodInfo(vodId: String): JsonObject? = api("get_vod_info", "&vod_id=${enc(vodId)}").obj()

    fun seriesInfo(seriesId: String): XtreamSeriesInfo {
        val root = api("get_series_info", "&series_id=${enc(seriesId)}").obj() ?: JsonObject(emptyMap())
        val info = root["info"].obj() ?: JsonObject(emptyMap())
        val episodes = ArrayList<XtreamEpisode>()
        val eps = root["episodes"]
        // Either {"1":[...], "2":[...]} or [[...],[...]].
        val groups: List<Pair<Int, JsonArray>> = when (eps) {
            is JsonObject -> eps.entries.mapNotNull { (k, v) -> (v as? JsonArray)?.let { (k.toIntOrNull() ?: 0) to it } }
            is JsonArray -> eps.mapIndexedNotNull { i, v -> (v as? JsonArray)?.let { (i + 1) to it } }
            else -> emptyList()
        }
        for ((season, list) in groups) for (e in list.mapNotNull { it.obj() }) {
            val epInfo = e["info"].obj() ?: JsonObject(emptyMap())
            val id = e.str("id")
            val ext = e.str("container_extension").ifEmpty { "mp4" }
            episodes += XtreamEpisode(
                id = id,
                season = e.str("season").toIntOrNull() ?: season,
                number = e.str("episode_num").toIntOrNull() ?: 0,
                title = e.str("title"),
                extension = ext,
                plot = epInfo.str("plot"),
                image = epInfo.str("movie_image"),
                durationSecs = epInfo.long("duration_secs"),
                url = "$base/series/$user/$pass/$id.$ext",
            )
        }
        episodes.sortWith(compareBy({ it.season }, { it.number }))
        return XtreamSeriesInfo(
            seasons = episodes.map { it.season }.distinct().sorted(),
            episodes = episodes,
            plot = info.str("plot"), cast = info.str("cast"), director = info.str("director"),
            genre = info.str("genre"), year = info.str("releaseDate").take(4).ifEmpty { info.str("year") },
            backdrop = info["backdrop_path"].arr()?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "",
            trailer = info.str("youtube_trailer"),
        )
    }

    fun liveUrl(streamId: String, ext: String = "ts") = "$base/live/$user/$pass/$streamId.$ext"
    fun movieUrl(streamId: String, ext: String) = "$base/movie/$user/$pass/$streamId.$ext"
    fun xmltvUrl() = "$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}"
}
