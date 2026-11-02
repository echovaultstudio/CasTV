package app.castv

import android.app.AlarmManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.RecognizerIntent
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.castv.player.DisplayModeSwitcher
import app.castv.ui.CasTheme
import app.castv.ui.ConfirmDialog
import app.castv.ui.Effect
import app.castv.ui.GuideOverlay
import app.castv.ui.Label
import app.castv.ui.LocalUi
import app.castv.ui.MainViewModel
import app.castv.ui.ManageScreen
import app.castv.ui.MultiviewScreen
import app.castv.ui.PinDialog
import app.castv.ui.PlaylistWizard
import app.castv.ui.RecordingsScreen
import app.castv.ui.RemindersScreen
import app.castv.ui.Screen
import app.castv.ui.SearchScreen
import app.castv.ui.SettingsScreen
import app.castv.ui.VodBrowser
import app.castv.ui.VodDetail
import app.castv.ui.WatchScreen
import app.castv.ui.pickResult
import app.castv.ui.uiFrom
import app.castv.ui.voiceResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private lateinit var afr: DisplayModeSwitcher
    private var pendingPick = ""

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { deliver(it, persist = true) } }
    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { deliver(it, persist = true, write = true) } }
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { deliver(it) } }
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { voiceResult.value = it }
    }

    private fun deliver(uri: Uri, persist: Boolean = false, write: Boolean = false) {
        if (persist) runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
        }
        when (pendingPick) {
            "recordings_folder" -> { vm.settings.recordingsFolder = uri.toString(); vm.say("Recordings folder set") }
            "logos_folder" -> { vm.settings.logosFolder = uri.toString(); vm.say("Logos folder set") }
            else -> pickResult.value = pendingPick to uri
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        afr = DisplayModeSwitcher(this)
        handleIntent(intent)

        lifecycleScope.launch {
            vm.effects.collect { e ->
                when (e) {
                    is Effect.External -> openExternal(e.url, e.title)
                    Effect.Finish -> finish()
                    Effect.Background -> moveTaskToBack(true)
                    Effect.Pip -> enterPip()
                    Effect.Restart -> restart()
                    is Effect.Pick -> pick(e.what)
                    Effect.Voice -> runCatching {
                        voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                    }.onFailure { vm.say("Voice search isn't available on this device") }
                    Effect.OverlayPermission -> if (Build.VERSION.SDK_INT >= 23 && !AndroidSettings.canDrawOverlays(this@MainActivity)) {
                        runCatching { startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                            .onFailure { vm.say("Allow \"display over other apps\" with: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow") }
                    }
                    Effect.ExactAlarmPermission -> if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                        runCatching { startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
                    } else vm.say("Exact alarms are already allowed")
                }
            }
        }
        lifecycleScope.launch {
            vm.videoFormat.collect { f -> if (f != null) afr.apply(f.frameRate, f.height, vm.settings) }
        }

        setContent {
            val version by vm.settingsVersion.collectAsState()
            val ui = remember(version) { uiFrom(vm.settings) }
            CasTheme(ui) { Root(vm) }
        }
        vm.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val pid = intent.getLongExtra("playlistId", 0)
        val key = intent.getStringExtra("channelKey")
        if (pid > 0 && key != null) lifecycleScope.launch {
            delay(300)
            vm.catalog.value.find(pid, key)?.let { vm.home(); vm.play(it) }
        }
    }

    private fun pick(what: String) {
        pendingPick = what
        runCatching {
            when (what) {
                "m3u" -> openDoc.launch(arrayOf("*/*"))
                "backup_open" -> openDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                "backup_create" -> createDoc.launch("castv-backup-${System.currentTimeMillis() / 1000}.json")
                "recordings_folder", "logos_folder" -> openTree.launch(null)
            }
        }.onFailure { vm.say("No file picker on this device") }
    }

    private fun openExternal(url: String, title: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", title)
        runCatching { startActivity(Intent.createChooser(i, "Open with")) }.onFailure { vm.say("No external player found for this format") }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature("android.software.picture_in_picture")) { vm.say("Picture-in-picture isn't supported here"); return }
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.settings.pipOnHome && vm.isPlaying.value) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip.value = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        vm.onStart()
    }

    override fun onStop() {
        super.onStop()
        vm.onStop()
        afr.reset()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The Guide and Search buttons work from anywhere.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_SEARCH -> { vm.open(Screen.Search); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun restart() {
        val i = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        Runtime.getRuntime().exit(0)
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val screens by vm.screens.collectAsState()
    val pin by vm.pin.collectAsState()
    val reminder by vm.reminderPopup.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.toast.collect { message = it; delay(2800); if (message == it) message = null } }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        // The player stays underneath every screen so live TV keeps running behind the guide.
        WatchScreen(vm)
        when (val s = screens.last()) {
            Screen.Watch -> Unit
            Screen.Guide -> GuideOverlay(vm, fullScreen = true)
            is Screen.Vod -> VodBrowser(vm, s.kind)
            is Screen.VodDetail -> VodDetail(vm, s.id)
            Screen.Search -> SearchScreen(vm)
            Screen.Recordings -> RecordingsScreen(vm)
            Screen.Reminders -> RemindersScreen(vm)
            is Screen.Settings -> SettingsScreen(vm, s.page, s.arg)
            is Screen.Wizard -> PlaylistWizard(vm)
            is Screen.Manage -> ManageScreen(vm, s.mode, s.group)
            Screen.Multiview -> MultiviewScreen(vm)
        }
        message?.let {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 28.dp).clip(RoundedCornerShape(10.dp)).background(LocalUi.current.panelStrong).padding(horizontal = 20.dp, vertical = 10.dp)) {
                Label(it, size = 16, maxLines = 3)
            }
        }
    }
    pin?.let { req -> PinDialog(req.title, onDismiss = { vm.pin.value = null }) { vm.submitPin(it) } }
    reminder?.let { r ->
        ConfirmDialog("Starting now: ${r.title}", "${r.channelName} · ${app.castv.ui.hm(r.start)}", "Watch", onDismiss = { vm.reminderPopup.value = null }) { vm.watchReminder(r) }
        LaunchedEffect(r.id) {
            delay(vm.settings.reminderPopupSec * 1000L)
            if (vm.reminderPopup.value?.id == r.id) {
                if (vm.settings.reminderDefaultAction == "watch") vm.watchReminder(r) else vm.reminderPopup.value = null
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") Toast.LENGTH_SHORT
}
