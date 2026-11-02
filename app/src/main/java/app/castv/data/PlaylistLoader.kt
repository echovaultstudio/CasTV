package app.castv.data

import android.content.Context
import androidx.room.withTransaction
import app.castv.core.M3uParser
import app.castv.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/** Downloads a playlist of any type and replaces its channels and VOD in the database. */
class PlaylistLoader(private val context: Context, private val db: AppDb, private val settings: Settings) {

    data class Result(val channels: Int, val movies: Int, val series: Int, val epgUrls: List<String>)

    suspend fun load(p: PlaylistEntity, progress: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        when (p.type) {
            PlaylistType.XTREAM -> loadXtream(p, progress)
            PlaylistType.STALKER -> loadStalker(p, progress)
            else -> loadM3u(p, progress)
        }
    }

    private fun ua(p: PlaylistEntity) = p.userAgent.ifBlank { settings.defaultUserAgent }

    private class KeyMaker {
        private val seen = HashMap<String, Int>()
        fun make(base: String): String {
            val n = seen.merge(base, 1) { a, b -> a + b } ?: 1
            return if (n == 1) base else "$base#$n"
        }
    }

    private suspend fun loadM3u(p: PlaylistEntity, progress: (String) -> Unit): Result {
        progress("Downloading playlist…")
        val parsed = Http.open(context, p.url, ua(p)).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024).useLines { M3uParser.parse(it) }
        }
        if (parsed.entries.isEmpty()) throw IllegalStateException("The playlist has no channels")
        progress("Saving ${parsed.entries.size} entries…")
        val now = System.currentTimeMillis() / 1000
        val keys = KeyMaker()
        val channels = ArrayList<ChannelEntity>()
        val vod = ArrayList<VodEntity>()
        parsed.entries.forEachIndexed { i, e ->
            val isVod = e.kind != MediaKind.LIVE || p.vodOnly
            if (isVod) {
                if (p.includeVod) vod += VodEntity(
                    playlistId = p.id, kind = "movie", key = keys.make("v|" + e.url.substringAfterLast('/').ifEmpty { e.name }),
                    name = e.name, category = e.group.ifEmpty { "Movies" }, poster = e.logo, url = e.url,
                    addedAt = now, playlistOrder = i,
                )
            } else {
                channels += ChannelEntity(
                    playlistId = p.id,
                    key = keys.make((e.tvgId.ifEmpty { e.name }) + "|" + e.group),
                    name = e.name, url = e.url, groupName = e.group.ifEmpty { "Other" }, logo = e.logo,
                    tvgId = e.tvgId, tvgName = e.tvgName, number = e.number ?: 0, playlistOrder = i,
                    catchupType = e.catchupType.ifEmpty { if (e.catchupDays > 0 || e.catchupSource.isNotEmpty()) parsed.header.catchupType.ifEmpty { "default" } else "" },
                    catchupDays = if (e.catchupDays > 0) e.catchupDays else parsed.header.catchupDays,
                    catchupSource = e.catchupSource.ifEmpty { parsed.header.catchupSource },
                    tvgShiftSeconds = ((if (e.tvgShiftHours != 0.0) e.tvgShiftHours else parsed.header.tvgShiftHours) * 3600).toLong(),
                    userAgent = e.userAgent.ifEmpty { parsed.header.userAgent }, referrer = e.referrer,
                    drmType = e.drmType, drmKey = e.drmKey, addedAt = now,
                )
            }
        }
        save(p, channels, vod)
        db.playlists().upsert(
            p.copy(
                channelCount = channels.size, movieCount = vod.size, seriesCount = 0,
                catchupType = parsed.header.catchupType, catchupDays = parsed.header.catchupDays,
                catchupSource = parsed.header.catchupSource,
                lastUpdated = System.currentTimeMillis(), lastError = "",
            )
        )
        return Result(channels.size, vod.size, 0, parsed.header.epgUrls)
    }

    private suspend fun loadXtream(p: PlaylistEntity, progress: (String) -> Unit): Result {
        val x = XtreamClient(context, p.url, p.username, p.password, ua(p))
        progress("Signing in…")
        val account = x.accountInfo()
        val userInfo = account?.get("user_info").obj()
        if (userInfo != null && userInfo.str("auth") == "0") throw IllegalStateException("Wrong username or password")
        val expires = userInfo?.long("exp_date") ?: 0
        val maxConn = userInfo?.str("max_connections")?.toIntOrNull() ?: 0
        val now = System.currentTimeMillis() / 1000
        val channels = ArrayList<ChannelEntity>()
        val vod = ArrayList<VodEntity>()
        val keys = KeyMaker()

        if (!p.vodOnly) {
            progress("Loading TV channels…")
            val cats = x.liveCategories()
            x.liveStreams().forEachIndexed { i, s ->
                val id = s.str("stream_id")
                val archive = s.str("tv_archive") == "1"
                channels += ChannelEntity(
                    playlistId = p.id, key = keys.make("x|$id"), name = s.str("name"),
                    url = x.liveUrl(id, p.outputFormat.ifEmpty { "ts" }),
                    groupName = cats[s.str("category_id")] ?: "Other", logo = s.str("stream_icon"),
                    tvgId = s.str("epg_channel_id"), tvgName = s.str("name"),
                    number = s.str("num").toIntOrNull() ?: 0, playlistOrder = i,
                    catchupType = if (archive) "xc" else "", catchupDays = if (archive) s.str("tv_archive_duration").toIntOrNull() ?: 1 else 0,
                    streamId = id, addedAt = s.long("added").takeIf { it > 0 } ?: now,
                )
            }
        }
        var movies = 0
        var series = 0
        if (p.includeVod) {
            progress("Loading movies…")
            val vcats = x.vodCategories()
            x.vodStreams().forEachIndexed { i, s ->
                val id = s.str("stream_id")
                val ext = s.str("container_extension").ifEmpty { "mp4" }
                vod += VodEntity(
                    playlistId = p.id, kind = "movie", key = "m|$id", name = s.str("name"),
                    category = vcats[s.str("category_id")] ?: "Movies", poster = s.str("stream_icon"),
                    url = x.movieUrl(id, ext), streamId = id, extension = ext,
                    rating = s.double("rating"), addedAt = s.long("added"), playlistOrder = i,
                    year = s.str("year"),
                )
                movies++
            }
            progress("Loading shows…")
            val scats = x.seriesCategories()
            x.series().forEachIndexed { i, s ->
                val id = s.str("series_id")
                vod += VodEntity(
                    playlistId = p.id, kind = "series", key = "s|$id", name = s.str("name"),
                    category = scats[s.str("category_id")] ?: "Shows", poster = s.str("cover"),
                    streamId = id, plot = s.str("plot"), cast = s.str("cast"), director = s.str("director"),
                    genre = s.str("genre"), year = s.str("releaseDate").take(4), rating = s.double("rating"),
                    addedAt = s.long("last_modified"), trailer = s.str("youtube_trailer"), playlistOrder = i,
                    backdrop = s["backdrop_path"].arr()?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
                )
                series++
            }
        }
        progress("Saving…")
        save(p, channels, vod)
        db.playlists().upsert(
            p.copy(
                channelCount = channels.size, movieCount = movies, seriesCount = series, expiresAt = expires,
                maxConnections = maxConn, lastUpdated = System.currentTimeMillis(), lastError = "",
            )
        )
        return Result(channels.size, movies, series, if (p.vodOnly) emptyList() else listOf(x.xmltvUrl()))
    }

    private suspend fun loadStalker(p: PlaylistEntity, progress: (String) -> Unit): Result {
        val s = StalkerClient(context, p.url, p.mac, p.serial, p.deviceId)
        progress("Connecting to portal…")
        s.connect()
        val genres = s.genres()
        progress("Loading channels…")
        val keys = KeyMaker()
        val now = System.currentTimeMillis() / 1000
        val channels = s.channels().mapIndexed { i, c ->
            val archive = c.str("tv_archive") == "1" || c.str("allow_pvr") == "1"
            ChannelEntity(
                playlistId = p.id, key = keys.make("s|" + c.str("id")), name = c.str("name"),
                url = StalkerClient.SCHEME + c.str("cmd"), groupName = genres[c.str("tv_genre_id")] ?: "Other",
                logo = c.str("logo"), tvgId = c.str("xmltv_id"), number = c.str("number").toIntOrNull() ?: 0,
                playlistOrder = i, catchupType = if (archive) "stalker" else "",
                catchupDays = if (archive) ((c.str("tv_archive_duration").toIntOrNull() ?: 72) / 24).coerceAtLeast(1) else 0,
                streamId = c.str("id"), addedAt = now,
            )
        }
        save(p, channels, emptyList())
        db.playlists().upsert(p.copy(channelCount = channels.size, lastUpdated = System.currentTimeMillis(), lastError = ""))
        return Result(channels.size, 0, 0, emptyList())
    }

    private suspend fun save(p: PlaylistEntity, channels: List<ChannelEntity>, vod: List<VodEntity>) {
        // Groups never seen before are marked so they can be hidden when "Show newly added groups" is off.
        val known = db.prefs().groupPrefs().filter { it.playlistId == p.id }.map { it.name }.toSet()
        val firstLoad = db.channels().forPlaylist(p.id).isEmpty() && known.isEmpty()
        db.withTransaction {
            db.channels().deleteForPlaylist(p.id)
            channels.chunked(500).forEach { db.channels().insertAll(it) }
            db.vod().deleteForPlaylist(p.id)
            vod.chunked(500).forEach { db.vod().insertAll(it) }
            if (!firstLoad) {
                val newGroups = channels.map { it.groupName }.distinct().filter { it !in known }
                db.prefs().upsertGroupPrefs(newGroups.map { GroupPrefEntity(p.id, it, hidden = !settings.showNewGroups, seen = false) })
            }
        }
    }
}
