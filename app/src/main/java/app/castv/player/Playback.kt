package app.castv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.castv.core.CatchupType
import app.castv.core.CatchupUrl
import app.castv.core.StreamUrls
import app.castv.data.Channel
import app.castv.data.Http
import app.castv.data.PlaylistEntity
import app.castv.data.Repo
import app.castv.data.StalkerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Stream(
    val url: String,
    val userAgent: String = "",
    val referrer: String = "",
    val drmType: String = "",
    val drmKey: String = "",
    val live: Boolean = true,
    val title: String = "",
)

/** Turns channels, past programmes and VOD into playable addresses (portal links, catch-up, UDP proxy). */
class StreamResolver(private val context: Context, private val repo: Repo) {
    private val stalkers = HashMap<Long, StalkerClient>()

    private suspend fun playlist(id: Long): PlaylistEntity? = repo.db.playlists().get(id)

    private suspend fun stalker(p: PlaylistEntity): StalkerClient = withContext(Dispatchers.IO) {
        stalkers.getOrPut(p.id) { StalkerClient(context, p.url, p.mac, p.serial, p.deviceId).also { it.connect() } }
    }

    private suspend fun stalkerLink(p: PlaylistEntity, cmd: String): String = withContext(Dispatchers.IO) {
        try {
            stalker(p).createLink(cmd)
        } catch (e: Exception) {
            // The token may have expired: connect again once.
            stalkers.remove(p.id)
            stalker(p).createLink(cmd)
        }
    }

    suspend fun live(c: Channel): Stream {
        val p = playlist(c.playlistId)
        var url = c.e.url
        if (url.startsWith(StalkerClient.SCHEME) && p != null) url = stalkerLink(p, url.removePrefix(StalkerClient.SCHEME))
        url = StreamUrls.applyUdpProxy(url, repo.settings.udpProxy)
        return Stream(
            url = url,
            userAgent = c.e.userAgent.ifEmpty { p?.userAgent.orEmpty() }.ifEmpty { repo.settings.defaultUserAgent },
            referrer = c.e.referrer, drmType = c.e.drmType, drmKey = c.e.drmKey, live = true, title = c.name,
        )
    }

    /** A past (or the current) programme from [start]; null when the channel has no catch-up. */
    suspend fun catchup(c: Channel, start: Long, end: Long): Stream? {
        if (!c.hasCatchup) return null
        val p = playlist(c.playlistId)
        val now = System.currentTimeMillis() / 1000
        val type = c.e.catchupType
        val url = if (type == "stalker" && p != null) {
            withContext(Dispatchers.IO) {
                runCatching { stalker(p).createArchiveLink(c.e.url.removePrefix(StalkerClient.SCHEME), start, (end - start) / 60) }.getOrNull()
            }
        } else {
            val live = if (c.e.url.startsWith(StalkerClient.SCHEME)) live(c).url else c.e.url
            CatchupUrl.build(CatchupType.parse(type), live, c.e.catchupSource, start, end.coerceAtMost(now), now)
        } ?: return null
        if (url.isEmpty()) return null
        return Stream(
            url = url,
            userAgent = c.e.userAgent.ifEmpty { p?.userAgent.orEmpty() }.ifEmpty { repo.settings.defaultUserAgent },
            referrer = c.e.referrer, drmType = c.e.drmType, drmKey = c.e.drmKey, live = false, title = c.name,
        )
    }

    suspend fun vod(playlistId: Long, url: String, title: String): Stream {
        val p = playlist(playlistId)
        return Stream(url, p?.userAgent.orEmpty().ifEmpty { repo.settings.defaultUserAgent }, live = false, title = title)
    }

    companion object {
        /** Builds the media source with the stream's own headers and DRM. */
        fun mediaSource(context: Context, s: Stream): MediaSource {
            val headers = buildMap {
                if (s.referrer.isNotEmpty()) { put("Referer", s.referrer); put("Origin", s.referrer.trimEnd('/')) }
            }
            val http = OkHttpDataSource.Factory(Http.client)
                .setUserAgent(s.userAgent.ifEmpty { Http.defaultUserAgent })
                .setDefaultRequestProperties(headers)
            val factory = DefaultDataSource.Factory(context, http)
            val item = MediaItem.Builder().setUri(Uri.parse(s.url)).apply {
                guessMime(s.url)?.let { setMimeType(it) }
                drm(s)?.let { setDrmConfiguration(it) }
                if (s.live) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(C.TIME_UNSET).build())
            }.build()
            return DefaultMediaSourceFactory(factory).createMediaSource(item)
        }

        fun guessMime(url: String): String? {
            val path = url.substringBefore('?').substringBefore('|').lowercase()
            return when {
                path.endsWith(".m3u8") || "/hls/" in path || "format=m3u8" in url || "type=m3u8" in url -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                path.endsWith(".ism") || path.endsWith("/manifest") -> MimeTypes.APPLICATION_SS
                else -> null
            }
        }

        private fun drm(s: Stream): MediaItem.DrmConfiguration? {
            val t = s.drmType.lowercase()
            if (t.isEmpty()) return null
            return when {
                "widevine" in t -> MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(s.drmKey.substringBefore('|')).build()
                "playready" in t -> MediaItem.DrmConfiguration.Builder(C.PLAYREADY_UUID).setLicenseUri(s.drmKey.substringBefore('|')).build()
                "clearkey" in t -> {
                    val key = s.drmKey
                    if (key.startsWith("http")) MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).setLicenseUri(key).build()
                    else MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).setLicenseUri(clearKeyDataUri(key)).build()
                }
                else -> null
            }
        }

        /** "kid:key[,kid:key]" (hex) as a data: license response the ClearKey CDM accepts. */
        private fun clearKeyDataUri(pairs: String): String {
            fun b64(hex: String): String {
                val bytes = hex.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            }
            val keys = pairs.split(',').mapNotNull {
                val kid = it.substringBefore(':').trim()
                val k = it.substringAfter(':', "").trim()
                if (kid.isEmpty() || k.isEmpty()) null else """{"kty":"oct","k":"${b64(k)}","kid":"${b64(kid)}"}"""
            }
            val jsonText = """{"keys":[${keys.joinToString(",")}],"type":"temporary"}"""
            return "data:application/json;base64," + android.util.Base64.encodeToString(jsonText.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }
}
