package app.castv.system

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.castv.CasApp
import app.castv.MainActivity
import app.castv.R
import app.castv.data.ReminderEntity
import app.castv.recording.RecordingScheduler
import app.castv.ui.ReminderActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object Reminders {
    private const val ACTION = "app.castv.action.REMINDER"

    fun schedule(context: Context, r: ReminderEntity) {
        val s = CasApp.get(context).settings
        val at = (r.start - s.remindBeforeMin * 60L) * 1000
        if (at < System.currentTimeMillis() - 60_000) return
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, r.id)
        val type = if (s.reminderWakeUp) AlarmManager.RTC_WAKEUP else AlarmManager.RTC
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(type, at, pi)
        else am.setExactAndAllowWhileIdle(type, at, pi)
    }

    fun cancel(context: Context, id: Long) = context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))

    private fun pending(context: Context, id: Long) = PendingIntent.getBroadcast(
        context, id.toInt(), Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    suspend fun rescheduleAll(context: Context) {
        val dao = CasApp.get(context).repo.db.activity()
        dao.trimReminders(System.currentTimeMillis() / 1000 - 3600)
        dao.reminders().forEach { schedule(context, it) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0)
        if (id <= 0) return
        val app = CasApp.get(context)
        if (app.inForeground) {
            app.events.tryEmit(CasApp.Event.Reminder(id))
            return
        }
        val popup = Intent(context, ReminderActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val canStart = Build.VERSION.SDK_INT < 29 || AndroidSettings.canDrawOverlays(context)
        if (canStart) {
            runCatching { context.startActivity(popup) }
        } else {
            // Without permission to open over other apps, a full-screen notification is the fallback.
            val pi = PendingIntent.getActivity(context, id.toInt(), popup, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n: Notification = NotificationCompat.Builder(context, CasApp.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText("A program you wanted to watch is starting")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(id.toInt() + 10_000, n)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = CasApp.get(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.rescheduleAll(context)
                RecordingScheduler.rescheduleAll(context)
            } finally {
                pending.finish()
            }
        }
        if (app.settings.autostartOnWake) WakeService.start(context)
        if (app.settings.autostartOnBoot) launchApp(context)
    }
}

fun launchApp(context: Context) {
    val canStart = Build.VERSION.SDK_INT < 29 || AndroidSettings.canDrawOverlays(context)
    if (canStart) runCatching {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}

/** Keeps listening for the TV waking up so the app can open itself ("Auto start on wake-up"). */
class WakeService : Service() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON && CasApp.get(context).settings.autostartOnWake) launchApp(context)
        }
    }

    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, WakeService::class.java))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = NotificationCompat.Builder(this, CasApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Opens when the TV wakes up")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(this, 4202, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }
}

/** Updates stale playlists and guides in the background. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = CasApp.get(applicationContext).repo
        runCatching { repo.updateAllPlaylists(onlyStale = true) }
        runCatching { repo.updateAllEpg(onlyStale = true) }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "castv-updates", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(3, TimeUnit.HOURS).build(),
            )
        }
    }
}
