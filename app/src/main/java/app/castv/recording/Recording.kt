package app.castv.recording

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import app.castv.CasApp
import app.castv.R
import app.castv.data.Http
import app.castv.data.RecStatus
import app.castv.data.RecordingEntity
import app.castv.player.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object RecordingScheduler {
    private const val ACTION = "app.castv.action.RECORD"

    fun schedule(context: Context, r: RecordingEntity) {
        if (r.status != RecStatus.SCHEDULED) return
        val s = CasApp.get(context).settings
        val at = (r.start - s.recordBeforeMin * 60L) * 1000
        if (at <= System.currentTimeMillis()) {
            RecordingService.start(context, r.id)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, r.id)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))
        RecordingService.stop(context, id)
    }

    private fun pending(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, RecordingAlarmReceiver::class.java).setAction(ACTION).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    suspend fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis() / 1000
        for (r in CasApp.get(context).repo.db.activity().recordings()) {
            if (r.status == RecStatus.SCHEDULED && r.stop > now) schedule(context, r)
            if (r.status == RecStatus.RECORDING && r.stop > now) RecordingService.start(context, r.id)
        }
    }
}

class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0)
        if (id > 0) RecordingService.start(context, id)
    }
}

/** Saves live streams to files while they are being recorded. Handles plain streams and HLS (including AES-128). */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()

    companion object {
        private const val START = "start"
        private const val STOP = "stop"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context, id: Long) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(START).putExtra("id", id))
        }

        fun stop(context: Context, id: Long) {
            if (!running) return
            context.startService(Intent(context, RecordingService::class.java).setAction(STOP).putExtra("id", id))
        }

        @Volatile var running = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getLongExtra("id", 0) ?: 0
        when (intent?.action) {
            START -> if (id > 0 && jobs[id]?.isActive != true) jobs[id] = scope.launch { record(id) }
            STOP -> jobs.remove(id)?.cancel()
        }
        if (jobs.isEmpty() && intent?.action == STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val stopAll = PendingIntent.getService(
            this, 0, Intent(this, RecordingService::class.java).setAction(STOP).putExtra("id", -1L),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CasApp.CHANNEL_RECORDINGS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Recording…")
            .setOngoing(true)
            .addAction(0, "Stop", stopAll)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private suspend fun record(id: Long) {
        val app = CasApp.get(this)
        val dao = app.repo.db.activity()
        var rec = dao.recording(id) ?: return
        val s = app.settings
        val endAt = (rec.stop + s.recordAfterMin * 60L) * 1000
        if (System.currentTimeMillis() >= endAt) { dao.upsertRecording(rec.copy(status = RecStatus.FAILED, error = "Too late")); finish(id); return }
        val channel = app.repo.catalog.value.find(rec.playlistId, rec.channelKey)
            ?: app.repo.db.channels().byKey(rec.playlistId, rec.channelKey)?.let { e -> app.repo.catalog.value.byId(e.id) }
        if (channel == null) { dao.upsertRecording(rec.copy(status = RecStatus.FAILED, error = "Channel not found")); finish(id); return }

        var out: OutputStream? = null
        var written = 0L
        try {
            val stream = StreamResolver(this, app.repo).live(channel)
            val (location, os) = openOutput(rec)
            out = os
            rec = rec.copy(status = RecStatus.RECORDING, location = location, error = "")
            dao.upsertRecording(rec)
            var lastSave = 0L
            val onBytes: (Long) -> Unit = { n ->
                written += n
            }
            val isHls = StreamResolver.guessMime(stream.url)?.contains("mpegurl") == true || stream.url.contains(".m3u8")
            var attempts = 0
            while (System.currentTimeMillis() < endAt && scope.isActive && jobs.containsKey(id)) {
                try {
                    if (isHls || sniffHls(stream.url, stream.userAgent)) hls(stream.url, stream.userAgent, os, endAt, id, onBytes)
                    else raw(stream.url, stream.userAgent, os, endAt, id, onBytes)
                    attempts = 0
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    attempts++
                    if (attempts > 20) throw e
                    delay(2000)
                }
                if (System.currentTimeMillis() - lastSave > 10_000) {
                    lastSave = System.currentTimeMillis()
                    dao.upsertRecording(rec.copy(sizeBytes = written))
                }
            }
            dao.upsertRecording(rec.copy(status = RecStatus.DONE, sizeBytes = written))
            repeatIfNeeded(rec)
        } catch (e: Exception) {
            val cancelled = e is kotlinx.coroutines.CancellationException
            dao.upsertRecording(rec.copy(status = if (cancelled && written > 0) RecStatus.DONE else if (cancelled) RecStatus.CANCELLED else RecStatus.FAILED, sizeBytes = written, error = if (cancelled) "" else e.message.orEmpty()))
            if (cancelled) throw e
        } finally {
            runCatching { out?.close() }
            finish(id)
        }
    }

    private suspend fun repeatIfNeeded(rec: RecordingEntity) {
        val step = when (rec.repeat) { "daily" -> 86400L; "weekly" -> 7 * 86400L; else -> return }
        val next = rec.copy(id = 0, start = rec.start + step, stop = rec.stop + step, status = RecStatus.SCHEDULED, location = "", sizeBytes = 0)
        val newId = CasApp.get(this).repo.db.activity().insertRecording(next)
        RecordingScheduler.schedule(this, next.copy(id = newId))
    }

    private fun finish(id: Long) {
        jobs.remove(id)
        if (jobs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun openOutput(rec: RecordingEntity): Pair<String, OutputStream> {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(rec.start * 1000))
        val name = "${rec.channelName}_${rec.title}_$stamp".replace(Regex("[^\\p{L}\\p{N} ._-]"), "").take(120) + ".ts"
        val folder = CasApp.get(this).settings.recordingsFolder
        if (folder.startsWith("content://")) {
            val tree = DocumentFile.fromTreeUri(this, Uri.parse(folder))
            val file = tree?.createFile("video/mp2t", name)
            val os = file?.let { contentResolver.openOutputStream(it.uri) }
            if (file != null && os != null) return file.uri.toString() to os
        }
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir, "Recordings").apply { mkdirs() }
        val f = File(dir, name)
        return f.absolutePath to f.outputStream().buffered(256 * 1024)
    }

    private fun request(url: String, ua: String) = Request.Builder().url(url).header("User-Agent", ua.ifEmpty { Http.defaultUserAgent }).build()

    private fun sniffHls(url: String, ua: String): Boolean = runCatching {
        Http.client.newCall(request(url, ua)).execute().use { r ->
            val type = r.header("Content-Type").orEmpty().lowercase()
            if ("mpegurl" in type) return@use true
            val peek = r.peekBody(16).string()
            peek.startsWith("#EXTM3U")
        }
    }.getOrDefault(false)

    private fun raw(url: String, ua: String, out: OutputStream, endAt: Long, id: Long, onBytes: (Long) -> Unit) {
        Http.client.newCall(request(url, ua)).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val input = r.body?.byteStream() ?: error("Empty stream")
            val buf = ByteArray(128 * 1024)
            while (System.currentTimeMillis() < endAt && jobs.containsKey(id)) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                onBytes(n.toLong())
            }
        }
    }

    private suspend fun hls(url: String, ua: String, out: OutputStream, endAt: Long, id: Long, onBytes: (Long) -> Unit) {
        var playlistUrl = url
        val done = LinkedHashSet<String>()
        var wroteInit = false
        while (System.currentTimeMillis() < endAt && jobs.containsKey(id)) {
            val text = Http.client.newCall(request(playlistUrl, ua)).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                playlistUrl = r.request.url.toString()
                r.body?.string().orEmpty()
            }
            val lines = text.lines().map { it.trim() }
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                // Master playlist: take the variant with the highest bandwidth.
                var best = ""
                var bestBw = -1L
                for (i in lines.indices) if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                    if (bw > bestBw) { bestBw = bw; best = uri }
                }
                playlistUrl = resolve(playlistUrl, best)
                continue
            }
            var target = 6
            var sequence = 0L
            var key: ByteArray? = null
            var iv: ByteArray? = null
            var keyUrl = ""
            for (line in lines) when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').toIntOrNull() ?: 6
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0
                line.startsWith("#EXT-X-MAP:") && !wroteInit -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    if (uri != null) { download(resolve(playlistUrl, uri), ua)?.let { out.write(it); onBytes(it.size.toLong()) }; wroteInit = true }
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([A-Z0-9-]+)").find(line)?.groupValues?.get(1)
                    if (method == "AES-128") {
                        val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1).orEmpty()
                        val abs = resolve(playlistUrl, uri)
                        if (abs != keyUrl) { key = download(abs, ua); keyUrl = abs }
                        iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.let { hex(it) }
                    } else { key = null; iv = null }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val seg = resolve(playlistUrl, line)
                    val seqNo = sequence++
                    if (done.add(seg)) {
                        var data = download(seg, ua) ?: continue
                        val k = key
                        if (k != null) data = decrypt(data, k, iv ?: ivFor(seqNo))
                        out.write(data)
                        onBytes(data.size.toLong())
                        if (done.size > 500) done.remove(done.first())
                    }
                }
            }
            if (lines.any { it.startsWith("#EXT-X-ENDLIST") }) return
            delay((target * 500L).coerceIn(1000, 10_000))
        }
    }

    private fun download(url: String, ua: String): ByteArray? =
        Http.client.newCall(request(url, ua)).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }

    private fun resolve(base: String, ref: String): String = runCatching { java.net.URI(base).resolve(ref).toString() }.getOrDefault(ref)

    private fun hex(s: String): ByteArray = s.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ivFor(seq: Long): ByteArray = ByteArray(16).also { b -> for (i in 0 until 8) b[15 - i] = (seq shr (8 * i)).toByte() }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = runCatching {
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(data)
    }.getOrDefault(data)

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }
}
