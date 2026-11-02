package app.castv.ui

import android.view.KeyEvent as AKeyEvent
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.castv.core.RemoteAction
import app.castv.core.RemoteButton
import kotlinx.coroutines.delay

@Composable
fun VideoSurface(vm: MainViewModel, modifier: Modifier = Modifier) {
    val version by vm.playerVersion.collectAsState()
    val mode by vm.displayMode.collectAsState()
    val reduced = mode == "reduced"
    AndroidView(
        modifier = modifier.background(Color.Black).scale(if (reduced) 0.85f else 1f),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(false)
                player = vm.player
            }
        },
        update = { view ->
            if (view.player !== vm.player) view.player = vm.player
            view.resizeMode = when (mode) {
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            @Suppress("UNUSED_EXPRESSION") version
        },
    )
}

private fun buttonFor(code: Int, long: Boolean): RemoteButton? = when (code) {
    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_NUMPAD_ENTER -> if (long) RemoteButton.OK_LONG else RemoteButton.OK
    AKeyEvent.KEYCODE_DPAD_UP -> if (long) RemoteButton.UP_LONG else RemoteButton.UP
    AKeyEvent.KEYCODE_DPAD_DOWN -> if (long) RemoteButton.DOWN_LONG else RemoteButton.DOWN
    AKeyEvent.KEYCODE_DPAD_LEFT -> if (long) RemoteButton.LEFT_LONG else RemoteButton.LEFT
    AKeyEvent.KEYCODE_DPAD_RIGHT -> if (long) RemoteButton.RIGHT_LONG else RemoteButton.RIGHT
    AKeyEvent.KEYCODE_MENU -> if (long) RemoteButton.MENU_LONG else RemoteButton.MENU
    AKeyEvent.KEYCODE_CHANNEL_UP, AKeyEvent.KEYCODE_PAGE_UP -> RemoteButton.CH_UP
    AKeyEvent.KEYCODE_CHANNEL_DOWN, AKeyEvent.KEYCODE_PAGE_DOWN -> RemoteButton.CH_DOWN
    AKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AKeyEvent.KEYCODE_MEDIA_PLAY, AKeyEvent.KEYCODE_MEDIA_PAUSE, AKeyEvent.KEYCODE_SPACE ->
        if (long) RemoteButton.PLAY_PAUSE_LONG else RemoteButton.PLAY_PAUSE
    AKeyEvent.KEYCODE_MEDIA_REWIND -> RemoteButton.REWIND
    AKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteButton.FAST_FORWARD
    AKeyEvent.KEYCODE_MEDIA_STOP -> RemoteButton.STOP
    AKeyEvent.KEYCODE_MEDIA_RECORD -> RemoteButton.RECORD
    AKeyEvent.KEYCODE_GUIDE, AKeyEvent.KEYCODE_TV_INPUT -> RemoteButton.GUIDE
    AKeyEvent.KEYCODE_INFO -> RemoteButton.INFO
    AKeyEvent.KEYCODE_PROG_RED -> RemoteButton.RED
    AKeyEvent.KEYCODE_PROG_GREEN -> RemoteButton.GREEN
    AKeyEvent.KEYCODE_PROG_YELLOW -> RemoteButton.YELLOW
    AKeyEvent.KEYCODE_PROG_BLUE -> RemoteButton.BLUE
    else -> null
}

private val longCapable = setOf(
    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_NUMPAD_ENTER, AKeyEvent.KEYCODE_DPAD_UP,
    AKeyEvent.KEYCODE_DPAD_DOWN, AKeyEvent.KEYCODE_DPAD_LEFT, AKeyEvent.KEYCODE_DPAD_RIGHT, AKeyEvent.KEYCODE_MENU,
    AKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AKeyEvent.KEYCODE_BACK,
)

@Composable
fun WatchScreen(vm: MainViewModel) {
    val overlay by vm.overlay.collectAsState()
    val mode by vm.mode.collectAsState()
    val error by vm.error.collectAsState()
    val buffering by vm.buffering.collectAsState()
    val number by vm.numberEntry.collectAsState()
    val pip by vm.inPip.collectAsState()
    val hasPlaylists by vm.hasPlaylists.collectAsState()
    val catalog by vm.catalog.collectAsState()
    val screens by vm.screens.collectAsState()
    val onTop = screens.last() == Screen.Watch
    vm.settingsVersion.collectAsState().value
    val s = vm.settings
    val focus = remember { FocusRequester() }
    var dialog by remember { mutableStateOf<String?>(null) }
    var poke by remember { mutableIntStateOf(0) }
    var backAt by remember { mutableLongStateOf(0L) }
    val press = remember { KeyPress() }

    fun act(a: RemoteAction) {
        poke++
        when (a) {
            RemoteAction.NONE -> Unit
            RemoteAction.SHOW_CHANNELS -> vm.overlay.value = Overlay.CHANNELS
            RemoteAction.SHOW_CHANNELS_GROUPS -> vm.overlay.value = Overlay.GROUPS
            RemoteAction.SHOW_GUIDE, RemoteAction.SHOW_GUIDE_GROUPS -> vm.overlay.value = Overlay.GUIDE
            RemoteAction.SHOW_INFO -> vm.overlay.value = if (overlay == Overlay.INFO) Overlay.NONE else Overlay.INFO
            RemoteAction.SHOW_CONTROLS -> vm.overlay.value = Overlay.CONTROLS
            RemoteAction.SHOW_MENU -> vm.overlay.value = Overlay.MENU
            RemoteAction.CHANNEL_UP -> vm.step(1)
            RemoteAction.CHANNEL_DOWN -> vm.step(-1)
            RemoteAction.PREVIEW_NEXT -> vm.previewStep(1)
            RemoteAction.PREVIEW_PREVIOUS -> vm.previewStep(-1)
            RemoteAction.LAST_CHANNEL -> vm.lastChannel()
            RemoteAction.TOGGLE_FAVORITE -> vm.toggleFavorite()
            RemoteAction.PLAY_PAUSE -> vm.togglePause()
            RemoteAction.STOP -> vm.stopPlayback()
            RemoteAction.REWIND -> vm.seekBy(-s.rwStepSec.toLong())
            RemoteAction.FAST_FORWARD -> vm.seekBy(s.ffStepSec.toLong())
            RemoteAction.RESTART_PROGRAM -> vm.restartProgram()
            RemoteAction.GO_LIVE -> vm.goLive()
            RemoteAction.RECORD -> vm.toggleRecording()
            RemoteAction.SEARCH -> vm.open(Screen.Search)
            RemoteAction.HISTORY -> { vm.setGroup(app.castv.data.GroupRef.HISTORY); vm.overlay.value = Overlay.CHANNELS }
            RemoteAction.MULTIVIEW -> vm.open(Screen.Multiview)
            RemoteAction.EXTERNAL_PLAYER -> vm.openExternal()
            RemoteAction.AUDIO_TRACKS -> dialog = "audio"
            RemoteAction.SUBTITLES -> dialog = "subs"
            RemoteAction.DISPLAY_MODE -> vm.cycleDisplayMode()
            RemoteAction.SLEEP_TIMER -> dialog = "sleep"
            RemoteAction.PIP -> vm.effects.tryEmit(Effect.Pip)
            RemoteAction.SETTINGS -> vm.withPin("Enter PIN", vm.settingsNeedPin()) { vm.open(Screen.Settings()) }
            RemoteAction.EXIT -> vm.effects.tryEmit(Effect.Finish)
        }
    }

    /** Catch-up and VOD replace some buttons with seeking. */
    fun seekOverride(b: RemoteButton): Boolean {
        val c = vm.current.value
        when (mode) {
            is Mode.Vod -> when (b) {
                RemoteButton.OK, RemoteButton.UP, RemoteButton.DOWN -> { vm.overlay.value = Overlay.CONTROLS; return true }
                RemoteButton.LEFT -> { vm.seekBy(-s.timebarBackSec.toLong()); vm.overlay.value = Overlay.CONTROLS; return true }
                RemoteButton.RIGHT -> { vm.seekBy(s.timebarFwdSec.toLong()); vm.overlay.value = Overlay.CONTROLS; return true }
                RemoteButton.CH_UP, RemoteButton.CH_DOWN -> return true
                else -> Unit
            }
            is Mode.Catchup -> {
                if (s.seekLeftRightCatchup && b == RemoteButton.LEFT) { vm.seekBy(-s.timebarBackSec.toLong()); vm.overlay.value = Overlay.CONTROLS; return true }
                if (s.seekLeftRightCatchup && b == RemoteButton.RIGHT) { vm.seekBy(s.timebarFwdSec.toLong()); vm.overlay.value = Overlay.CONTROLS; return true }
                if (s.seekUpDownCatchup && b == RemoteButton.UP) { vm.seekBy(600); return true }
                if (s.seekUpDownCatchup && b == RemoteButton.DOWN) { vm.seekBy(-600); return true }
                if (b == RemoteButton.OK) { vm.overlay.value = Overlay.CONTROLS; return true }
            }
            is Mode.Live -> if (c?.hasCatchup == true) {
                val rw = s.rewindLiveWithCatchup
                if (rw == "left" && b == RemoteButton.LEFT) { vm.seekBy(-s.timebarBackSec.toLong()); return true }
                if (rw == "down" && b == RemoteButton.DOWN) { vm.seekBy(-s.timebarBackSec.toLong()); return true }
            }
        }
        if (vm.preview.value != null && b == RemoteButton.OK) { vm.playPreview(); return true }
        return false
    }

    fun onBack() {
        when {
            vm.preview.value != null -> { vm.preview.value = null; vm.overlay.value = Overlay.NONE }
            overlay == Overlay.INFO -> vm.overlay.value = Overlay.NONE
            mode is Mode.Vod -> vm.leaveVod()
            mode is Mode.Catchup -> vm.goLive()
            else -> vm.overlay.value = Overlay.MENU
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { ev ->
                if (!onTop || (overlay != Overlay.NONE && overlay != Overlay.INFO)) return@onPreviewKeyEvent false
                val n = ev.nativeKeyEvent
                val code = n.keyCode
                if (code in AKeyEvent.KEYCODE_0..AKeyEvent.KEYCODE_9) {
                    if (ev.type == KeyEventType.KeyDown) vm.digit(code - AKeyEvent.KEYCODE_0)
                    return@onPreviewKeyEvent true
                }
                if (code == AKeyEvent.KEYCODE_LAST_CHANNEL) { if (ev.type == KeyEventType.KeyDown) vm.lastChannel(); return@onPreviewKeyEvent true }
                if (code == AKeyEvent.KEYCODE_BACK || code == AKeyEvent.KEYCODE_ESCAPE) {
                    if (ev.type == KeyEventType.KeyDown) {
                        if (n.repeatCount == 0) press.longFired = false
                        else if (!press.longFired) { press.longFired = true; act(s.remote(RemoteButton.BACK_LONG)) }
                    } else if (ev.type == KeyEventType.KeyUp && !press.longFired) onBack()
                    return@onPreviewKeyEvent true
                }
                if (code in longCapable) {
                    when (ev.type) {
                        KeyEventType.KeyDown -> {
                            if (n.repeatCount == 0) press.longFired = false
                            else if (!press.longFired) {
                                press.longFired = true
                                val b = buttonFor(code, true)
                                if (b != null && !seekOverride(b)) act(s.remote(b))
                            }
                        }
                        KeyEventType.KeyUp -> if (!press.longFired) {
                            val b = buttonFor(code, false)
                            if (b != null && !seekOverride(b)) act(s.remote(b))
                        }
                        else -> Unit
                    }
                    return@onPreviewKeyEvent true
                }
                val b = buttonFor(code, false) ?: return@onPreviewKeyEvent false
                if (ev.type == KeyEventType.KeyDown) {
                    if (!seekOverride(b)) {
                        if (mode !is Mode.Live && (b == RemoteButton.REWIND || b == RemoteButton.FAST_FORWARD) && !s.seekRwFfCatchup && mode is Mode.Catchup) Unit
                        else act(s.remote(b))
                    }
                }
                true
            }
            .focusable(enabled = onTop),
    ) {
        VideoSurface(vm, Modifier.fillMaxSize())

        if (!hasPlaylists && catalog.ready && onTop) Welcome(vm)

        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = LocalUi.current.accent)
        error?.let {
            Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(18.dp)) { Label(it, size = 17, maxLines = 3) }
        }
        if (number.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopEnd).padding(32.dp).clip(RoundedCornerShape(10.dp)).background(LocalUi.current.panelStrong).padding(horizontal = 22.dp, vertical = 10.dp)) {
                Label(number, size = 38, bold = true)
            }
        }
        if (!pip && onTop) {
            when (overlay) {
                Overlay.INFO -> InfoPanel(vm, Modifier.align(Alignment.BottomCenter))
                Overlay.CONTROLS -> ControlPanel(vm, Modifier.align(Alignment.BottomCenter), onDialog = { dialog = it }, onPoke = { poke++ })
                Overlay.CHANNELS -> ChannelListOverlay(vm, startWithGroups = false)
                Overlay.GROUPS -> ChannelListOverlay(vm, startWithGroups = true)
                Overlay.GUIDE -> GuideOverlay(vm)
                Overlay.MENU -> SideMenu(vm)
                Overlay.NONE -> Unit
            }
            if (s.showClock && overlay == Overlay.NONE && mode is Mode.Live && error == null) {
                val align = when (s.clockPosition) { "top_left" -> Alignment.TopStart; "bottom_left" -> Alignment.BottomStart; "bottom_right" -> Alignment.BottomEnd; else -> Alignment.TopEnd }
                Box(Modifier.align(align).padding(28.dp)) { if (s.clockPosition != "off") Clock(s.showDateOnClock, if (s.clockSize == "large") 26 else if (s.clockSize == "small") 14 else 18) }
            }
            RecordingDot(vm, Modifier.align(Alignment.TopStart).padding(24.dp))
        }
        TrackDialogs(vm, dialog) { dialog = null }
    }

    LaunchedEffect(overlay, poke) {
        if (overlay == Overlay.INFO || overlay == Overlay.CONTROLS) {
            delay(s.panelsTimeoutSec.coerceAtLeast(2) * 1000L)
            if (vm.overlay.value == overlay) { vm.overlay.value = Overlay.NONE; vm.preview.value = null }
        }
        if (onTop && (overlay == Overlay.NONE || overlay == Overlay.INFO)) runCatching { focus.requestFocus() }
    }
    LaunchedEffect(onTop) { if (onTop) { delay(50); runCatching { focus.requestFocus() } }; backAt = 0 }
}

@Composable
private fun Welcome(vm: MainViewModel) {
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D11)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Label("Welcome to CasTV", size = 30, bold = true)
            Dim("Add your own playlist to start watching. CasTV doesn't include any channels or content.", size = 16, maxLines = 3)
            Spacer(Modifier.height(8.dp))
            FocusRow(focusRequester = first, onClick = { vm.open(Screen.Wizard()) }) { Label("Add playlist", Modifier.weight(1f), bold = true) }
            FocusRow(onClick = { vm.open(Screen.Settings("backup")) }) { Label("Restore from a backup", Modifier.weight(1f)) }
        }
    }
    LaunchedEffect(Unit) { delay(300); runCatching { first.requestFocus() } }
}

@Composable
private fun RecordingDot(vm: MainViewModel, modifier: Modifier) {
    val recs by vm.recordings.collectAsState()
    val c by vm.current.collectAsState()
    val active = recs.any { it.status == app.castv.data.RecStatus.RECORDING && it.channelKey == c?.key && it.playlistId == c?.playlistId }
    if (active) Row(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE53935)))
        Spacer(Modifier.width(6.dp))
        Label("REC", size = 13, bold = true)
    }
}

@Composable
fun InfoPanel(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val s = vm.settings
    val preview by vm.preview.collectAsState()
    val current by vm.current.collectAsState()
    val nn by vm.nowNext.collectAsState()
    val pnn by vm.previewNowNext.collectAsState()
    val mode by vm.mode.collectAsState()
    val info by vm.mediaInfo.collectAsState()
    val sleepAt by vm.sleepAt.collectAsState()
    val c = preview ?: current ?: return
    val (now, next) = if (preview != null) pnn.now to pnn.next else nn.now to nn.next
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick = System.currentTimeMillis() } }
    val nowSec = tick / 1000

    Column(
        modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, ui.panelStrong, ui.panelStrong)))
            .padding(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (s.showChannelNumbers) Label(c.number.toString(), size = 28, bold = true, color = ui.accent)
            if (s.showChannelLogos) Logo(c.logo, c.name, Modifier.size(width = 72.dp, height = 46.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(c.name, size = 22, bold = true)
                    if (c.favorite) Label("★", color = ui.accent)
                    if (c.hasCatchup && s.showCatchupIcon) Dim("⟲ catch-up")
                    if (preview != null) Dim("· press OK to watch")
                }
                if (s.showPlaylistAndGroup) Dim("${c.playlistName} · ${c.groupTitle}")
            }
            Column(horizontalAlignment = Alignment.End) {
                Clock(s.showDateOnClock, 20)
                if (sleepAt > 0) Dim("Sleep in ${((sleepAt - tick) / 60000 + 1)} min")
            }
        }
        val cm = mode
        if (cm is Mode.Catchup && preview == null) {
            val pos = vm.positionEpoch()
            Label(cm.title, size = 18)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dim(hm(cm.progStart))
                Bar(((pos - cm.progStart).toFloat() / (cm.progStop - cm.progStart).coerceAtLeast(1)), Modifier.weight(1f), 4.dp)
                Dim(hm(cm.progStop))
                Dim("catch-up · ${hm(pos)}")
            }
        } else if (now != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(now.title, Modifier.weight(1f), size = 18, maxLines = if (s.twoLineProgramTitles) 2 else 1)
                Dim("${hm(now.start)} – ${hm(now.stop)}")
            }
            Bar((nowSec - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1), Modifier.fillMaxWidth(), 4.dp)
            if (s.showProgramDescription && now.description.isNotEmpty() && (!s.descriptionOnSwitchOnly || preview != null || true)) Dim(now.description, size = 14, maxLines = 2)
            next?.let { Dim("Next  ${hm(it.start)}  ${it.title}", size = 14) }
        } else {
            Dim("No program information", size = 15)
        }
        if (s.showMediaProperties && info.isNotEmpty() && preview == null) Dim(info, size = 13)
    }
}

@Composable
fun ControlPanel(vm: MainViewModel, modifier: Modifier, onDialog: (String) -> Unit, onPoke: () -> Unit) {
    val ui = LocalUi.current
    val mode by vm.mode.collectAsState()
    val playing by vm.isPlaying.collectAsState()
    val c by vm.current.collectAsState()
    val nn by vm.nowNext.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val first = remember { FocusRequester() }
    val s = vm.settings

    val buttons = buildList<Pair<String, () -> Unit>> {
        add((if (playing) "Pause" else "Play") to { vm.togglePause() })
        add("−${s.rwStepSec}s" to { vm.seekBy(-s.rwStepSec.toLong()) })
        add("+${s.ffStepSec}s" to { vm.seekBy(s.ffStepSec.toLong()) })
        if (mode !is Mode.Vod) add("From start" to { vm.restartProgram() })
        if (mode is Mode.Catchup) add("Live" to { vm.goLive() })
        if (mode is Mode.Vod) add("Speed" to { onDialog("speed") })
        add("Audio" to { onDialog("audio") })
        add("Subtitles" to { onDialog("subs") })
        add("Video" to { onDialog("video") })
        add("Picture" to { onDialog("display") })
        add("Audio delay" to { onDialog("offset") })
        if (mode !is Mode.Vod && c != null) {
            add((if (c?.favorite == true) "Unfavorite" else "Favorite") to { vm.toggleFavorite() })
            add((if (vm.activeRecording(c) != null) "Stop rec" else "Record") to { vm.toggleRecording() })
            add("Multiview" to { vm.open(Screen.Multiview) })
        }
        add("Sleep" to { onDialog("sleep") })
        add("External" to { vm.openExternal() })
        add("PiP" to { vm.effects.tryEmit(Effect.Pip) })
        if (mode is Mode.Vod) add("Stop" to { vm.leaveVod() })
    }

    Column(
        modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, ui.panelStrong)))
            .padding(horizontal = 36.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        when (val m = mode) {
            is Mode.Vod -> {
                Label(m.title, size = 20, bold = true)
                val pos = vm.player.currentPosition
                val dur = vm.player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dim(duration(pos)); Bar(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.weight(1f), 5.dp); Dim(duration(dur))
                }
            }
            is Mode.Catchup -> {
                val pos = vm.positionEpoch()
                Label("${c?.name.orEmpty()} · ${m.title}", size = 20, bold = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dim(hm(m.progStart)); Bar((pos - m.progStart).toFloat() / (m.progStop - m.progStart).coerceAtLeast(1), Modifier.weight(1f), 5.dp); Dim(hm(m.progStop))
                    Dim("at ${hm(pos)}")
                }
            }
            is Mode.Live -> {
                Label("${c?.name.orEmpty()}${nn.now?.let { " · " + it.title } ?: ""}", size = 20, bold = true)
                nn.now?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Dim(hm(p.start)); Bar((System.currentTimeMillis() / 1000 - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1), Modifier.weight(1f), 5.dp); Dim(hm(p.stop))
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(buttons) { i, (label, run) ->
                Box(Modifier.width(118.dp)) {
                    FocusRow(focusRequester = if (i == 0) first else null, onFocus = onPoke, onClick = { onPoke(); run() }) {
                        Label(label, Modifier.weight(1f), size = 14)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    androidx.activity.compose.BackHandler { vm.overlay.value = Overlay.NONE }
}

@Composable
fun TrackDialogs(vm: MainViewModel, which: String?, onDismiss: () -> Unit) {
    when (which) {
        "audio" -> {
            val tracks = remember { vm.tracks(C.TRACK_TYPE_AUDIO) }
            ChoiceDialog("Audio tracks", listOf(-1 to "Automatic") + tracks.mapIndexed { i, t -> i to t.label }, tracks.indexOfFirst { it.selected }, onDismiss) {
                if (it < 0) vm.autoTrack(C.TRACK_TYPE_AUDIO) else vm.selectTrack(C.TRACK_TYPE_AUDIO, tracks[it]); onDismiss()
            }
        }
        "subs" -> {
            val tracks = remember { vm.tracks(C.TRACK_TYPE_TEXT) }
            val sel = tracks.indexOfFirst { it.selected }.let { if (it < 0) -2 else it }
            ChoiceDialog("Subtitles", listOf(-2 to "Off", -1 to "Automatic") + tracks.mapIndexed { i, t -> i to t.label }, sel, onDismiss) {
                when { it == -2 -> vm.selectTrack(C.TRACK_TYPE_TEXT, null); it == -1 -> vm.autoTrack(C.TRACK_TYPE_TEXT); else -> vm.selectTrack(C.TRACK_TYPE_TEXT, tracks[it]) }
                onDismiss()
            }
        }
        "video" -> {
            val tracks = remember { vm.tracks(C.TRACK_TYPE_VIDEO) }
            ChoiceDialog("Video quality", listOf(-1 to "Automatic") + tracks.mapIndexed { i, t -> i to t.label }, tracks.indexOfFirst { it.selected }, onDismiss) {
                if (it < 0) vm.autoTrack(C.TRACK_TYPE_VIDEO) else vm.selectTrack(C.TRACK_TYPE_VIDEO, tracks[it]); onDismiss()
            }
        }
        "display" -> {
            var mode by remember { mutableStateOf<String?>(null) }
            val m = mode
            if (m == null) ChoiceDialog("Display mode", listOf("fit" to "Normal", "fill" to "Full (stretch)", "zoom" to "Zoom (crop)", "reduced" to "Reduced"), vm.displayMode.value, onDismiss) { mode = it }
            else ActionsDialog("Apply ${vm.displayModeName(m)}", listOf("This channel only" to { vm.setDisplayMode(m, false) }, "All channels" to { vm.setDisplayMode(m, true) }), onDismiss)
        }
        "offset" -> {
            var value by remember { mutableStateOf<Int?>(null) }
            val v = value
            if (v == null) ChoiceDialog("Audio delay", (-2000..2000 step 50).map { it to (if (it > 0) "+$it ms" else "$it ms") }, (vm.audioOffset.us / 1000).toInt(), onDismiss) { value = it }
            else ActionsDialog("Audio delay ${v} ms", listOf("This channel only" to { vm.setAudioOffset(v, false) }, "All channels" to { vm.setAudioOffset(v, true) }), onDismiss)
        }
        "sleep" -> ChoiceDialog("Sleep timer", listOf(0 to "Off", 10 to "10 min", 20 to "20 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour", 90 to "1.5 hours", 120 to "2 hours", 180 to "3 hours"), null, onDismiss) {
            vm.setSleep(it); onDismiss()
        }
        "speed" -> {
            val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            ChoiceDialog("Speed", speeds.map { it to (if (it == 1f) "Normal" else "${it}x") }, vm.speed.value, onDismiss) { vm.setSpeed(it); onDismiss() }
        }
    }
}

@Suppress("unused")
private fun keepContext() = LocalContext
