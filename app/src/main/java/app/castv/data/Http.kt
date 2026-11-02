package app.castv.data

import android.content.Context
import android.net.Uri
import app.castv.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object Http {
    val defaultUserAgent = "CasTV/${BuildConfig.VERSION_NAME} (Linux; Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Streaming download for playlists and guides; transparently gunzips. Closes with the returned stream. */
    fun open(context: Context, url: String, userAgent: String = "", headers: Map<String, String> = emptyMap()): InputStream {
        val raw: InputStream = if (url.startsWith("content://") || url.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(url)) ?: throw IOException("Can't open $url")
        } else if (url.startsWith("/")) {
            java.io.File(url).inputStream()
        } else {
            val req = Request.Builder().url(url)
                .header("User-Agent", userAgent.ifBlank { defaultUserAgent })
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                resp.close()
                throw IOException("HTTP ${resp.code} for ${redact(url)}")
            }
            resp.body?.byteStream() ?: throw IOException("Empty response")
        }
        val buffered = BufferedInputStream(raw, 64 * 1024)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024) else buffered
    }

    fun getText(context: Context, url: String, userAgent: String = "", headers: Map<String, String> = emptyMap()): String =
        open(context, url, userAgent, headers).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Hides passwords in addresses written to logs or shown in errors. */
    fun redact(url: String): String =
        url.replace(Regex("(password|pass|pwd|token)=[^&]*", RegexOption.IGNORE_CASE), "$1=***")
            .replace(Regex("""/(live|movie|series|timeshift)/([^/]+)/([^/]+)/"""), "/$1/$2/***/")
}
