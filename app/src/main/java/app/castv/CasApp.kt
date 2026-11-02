package app.castv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.castv.data.AppDb
import app.castv.data.Backup
import app.castv.data.Repo
import app.castv.data.Settings
import app.castv.system.UpdateWorker
import app.castv.system.WakeService
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

class CasApp : Application(), SingletonImageLoader.Factory {
    lateinit var settings: Settings
        private set
    lateinit var db: AppDb
        private set
    lateinit var repo: Repo
        private set
    val backup by lazy { Backup(this, db, settings) }

    @Volatile var inForeground = false
        private set

    sealed interface Event {
        data class Reminder(val id: Long) : Event
    }

    val events = MutableSharedFlow<Event>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    companion object {
        const val CHANNEL_RECORDINGS = "recordings"
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_SERVICE = "service"

        fun get(context: Context): CasApp = context.applicationContext as CasApp
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        db = AppDb.create(this)
        repo = Repo(this, db, settings)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RECORDINGS, "Recordings", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Background", NotificationManager.IMPORTANCE_MIN))

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { inForeground = true }
            override fun onStop(owner: LifecycleOwner) { inForeground = false }
        })

        UpdateWorker.schedule(this)
        if (settings.autostartOnWake) runCatching { WakeService.start(this) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { app.castv.data.Http.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(200L * 1024 * 1024).build() }
            .build()
}
