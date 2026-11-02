package app.castv.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TimeZone

/**
 * Client for Stalker / Ministra middleware portals, identified by a MAC address. Channels are listed once; their
 * stream addresses are fetched just before playback ("create_link"), because portals hand out short-lived links.
 */
class StalkerClient(private val context: Context, portal: String, mac: String, private val serial: String = "", private val deviceId: String = "") {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mac = mac.trim().uppercase()
    private val endpoint: String = resolveEndpoint(portal)
    private var token: String = ""

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        const val SCHEME = "stalker://"

        fun resolveEndpoint(portal: String): String {
            var p = portal.trim().trimEnd('/')
            if (!p.startsWith("http")) p = "http://$p"
            return when {
                p.endsWith(".php") -> p
                p.endsWith("/c") -> p.removeSuffix("/c") + "/server/load.php"
                p.endsWith("/stalker_portal") -> "$p/server/load.php"
                else -> "$p/portal.php"
            }
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02X".format(it) }

    private fun call(params: String): JsonObject? {
        val url = "$endpoint?$params&JsHttpRequest=1-xml"
        val tz = TimeZone.getDefault().id
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Cookie", "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=en; timezone=${URLEncoder.encode(tz, "UTF-8")}")
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Portal answered HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            return runCatching { json.parseToJsonElement(body).obj()?.get("js").obj() }.getOrNull()
        }
    }

    fun connect() {
        val hs = call("type=stb&action=handshake&token=&prehash=0") ?: throw IOException("Portal handshake failed")
        token = hs.str("token")
        if (token.isEmpty()) throw IOException("Portal did not return a token (check the MAC address)")
        val sn = serial.ifEmpty { sha256(mac).take(13) }
        val dev = deviceId.ifEmpty { sha256(mac) }
        call(
            "type=stb&action=get_profile&hd=1&ver=ImageDescription:%200.2.18-r23-250;%20PORTAL%20version:%205.6.1;" +
                "&num_banks=2&sn=$sn&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi" +
                "&device_id=$dev&device_id2=$dev&signature=&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0" +
                "&metrics=&hw_version_2=&timestamp=${System.currentTimeMillis() / 1000}&api_signature=262&prehash="
        )
    }

    fun accountExpiry(): String = runCatching { call("type=account_info&action=get_main_info")?.str("phone").orEmpty() }.getOrDefault("")

    fun genres(): Map<String, String> = runCatching {
        callRaw("type=itv&action=get_genres").mapNotNull { it.obj() }.associate { it.str("id") to it.str("title") }
    }.getOrDefault(emptyMap())

    private fun callRaw(params: String): List<kotlinx.serialization.json.JsonElement> {
        val url = "$endpoint?$params&JsHttpRequest=1-xml"
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=en; timezone=${URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")}")
            .header("Authorization", "Bearer $token")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val js = json.parseToJsonElement(body).obj()?.get("js")
            return js.arr()?.toList() ?: js.obj()?.get("data").arr()?.toList() ?: emptyList()
        }
    }

    /** All live channels: (name, cmd, logo, genreId, number, hasArchive, archiveDays). */
    fun channels(): List<JsonObject> = callRaw("type=itv&action=get_all_channels").mapNotNull { it.obj() }

    /** The playable address for a channel's "cmd". */
    fun createLink(cmd: String): String {
        val js = call("type=itv&action=create_link&cmd=${URLEncoder.encode(cmd, "UTF-8")}&series=&forced_storage=0&disable_ad=0&download=0")
            ?: throw IOException("Portal did not return a link")
        return cleanCmd(js.str("cmd"))
    }

    /** Address of a past programme on a channel with archive. */
    fun createArchiveLink(cmd: String, start: Long, durationMin: Long): String {
        val js = call("type=tv_archive&action=create_link&cmd=${URLEncoder.encode(cmd, "UTF-8")}&start=$start&duration=$durationMin")
        return js?.str("cmd")?.let { cleanCmd(it) }.orEmpty()
    }

    private fun cleanCmd(cmd: String) = cmd.trim().removePrefix("ffmpeg ").removePrefix("ffrt ").removePrefix("auto ").trim()
}
