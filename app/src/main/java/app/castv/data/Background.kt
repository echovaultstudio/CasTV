package app.castv.data

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** One low-priority thread for playlist and guide imports, so they never compete with playback and the interface. */
val ImportDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }, "castv-import").apply { isDaemon = true }
}.asCoroutineDispatcher()
