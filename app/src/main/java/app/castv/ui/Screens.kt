package app.castv.ui

import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.castv.data.Channel
import app.castv.data.GroupKind
import app.castv.data.GroupRef
import app.castv.data.ProgrammeEntity
import app.castv.data.RecStatus
import app.castv.data.RecordingEntity
import app.castv.data.Repo
import app.castv.player.AudioOffset
import app.castv.player.PlayerFactory
import app.castv.player.StreamResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Voice results arrive here from the activity. */
val voiceResult = MutableStateFlow<String?>(null)

@Composable
fun SearchScreen(vm: MainViewModel) {
    val ui = LocalUi.current
    val s = vm.settings
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Repo.SearchResult?>(null) }
    var menu by remember { mutableStateOf<Pair<Channel, ProgrammeEntity>?>(null) }
    val field = remember { FocusRequester() }
    val voice by voiceResult.collectAsState()
    val scope = rememberCoroutineScope()

    fun run(q: String) {
        query = q
        if (q.isBlank()) { result = null; return }
        s.addSearchHistory(q)
        scope.launch { result = vm.repo.search(q) }
    }

    LaunchedEffect(voice) { voice?.let { voiceResult.value = null; run(it) } }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0D11)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Search", size = 26, bold = true, color = ui.accent)
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(field),
                placeholder = { Dim("Channels, programs, movies, shows") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run(query) }),
            )
            Box(Modifier.width(150.dp)) { FocusRow(onClick = { run(query) }) { Label("Search") } }
            Box(Modifier.width(150.dp)) { FocusRow(onClick = { vm.effects.tryEmit(Effect.Voice) }) { Label("Voice") } }
        }
        val r = result
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (r == null) {
                if (s.searchShowHistory && s.searchHistoryList().isNotEmpty()) {
                    item { Row { Label("Recent searches", Modifier.weight(1f), size = 18, bold = true); Box(Modifier.width(140.dp)) { FocusRow(onClick = { s.searchHistory = ""; query = "" }) { Dim("Clear") } } } }
                    itemsIndexed(s.searchHistoryList()) { _, h -> FocusRow(onClick = { run(h) }) { Label(h) } }
                }
            } else {
                if (r.channels.isEmpty() && r.programmes.isEmpty() && r.vod.isEmpty()) item { Dim("Nothing found", size = 16) }
                if (r.channels.isNotEmpty()) item { Label("Channels", Modifier.padding(top = 10.dp), size = 18, bold = true) }
                itemsIndexed(r.channels) { _, c ->
                    FocusRow(onClick = { vm.play(c); if (!s.searchStayOnSwitch) vm.home() }) {
                        Dim(c.number.toString(), Modifier.width(48.dp)); Logo(c.logo, c.name); Label(c.name, Modifier.weight(1f)); Dim(c.groupTitle)
                    }
                }
                if (r.programmes.isNotEmpty()) item { Label("Programs", Modifier.padding(top = 10.dp), size = 18, bold = true) }
                itemsIndexed(r.programmes) { _, (c, p) ->
                    FocusRow(onClick = { menu = c to p }) {
                        Column(Modifier.weight(1f)) { Label(p.title); Dim("${c.name}  ·  ${dayLabel(p.start)} ${hm(p.start)} – ${hm(p.stop)}") }
                    }
                }
                if (r.vod.isNotEmpty()) item { Label("Movies and shows", Modifier.padding(top = 10.dp), size = 18, bold = true) }
                itemsIndexed(r.vod) { _, v ->
                    FocusRow(onClick = { vm.open(Screen.VodDetail(v.id)) }) { Label(v.name, Modifier.weight(1f)); Dim(if (v.kind == "movie") "Movie" else "Show") }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (s.searchPreferVoice) vm.effects.tryEmit(Effect.Voice)
        else runCatching { field.requestFocus() }
    }
    BackHandler { vm.back() }
    menu?.let { (c, p) -> ProgrammeMenu(vm, c, p, onDismiss = { menu = null }, onDetails = {}, onPlayed = { if (!s.searchStayOnSwitch) vm.home() }) }
}

@Composable
fun RecordingsScreen(vm: MainViewModel) {
    val ui = LocalUi.current
    val recs by vm.recordings.collectAsState()
    var tab by remember { mutableStateOf("done") }
    var menu by remember { mutableStateOf<RecordingEntity?>(null) }
    var custom by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val shown = when (tab) {
        "now" -> recs.filter { it.status == RecStatus.RECORDING }
        "scheduled" -> recs.filter { it.status == RecStatus.SCHEDULED }.sortedBy { it.start }
        else -> recs.filter { it.status == RecStatus.DONE || it.status == RecStatus.FAILED || it.status == RecStatus.CANCELLED }
    }
    Row(Modifier.fillMaxSize().background(Color(0xFF0B0D11))) {
        Column(Modifier.width(280.dp).fillMaxHeight().background(ui.panelStrong).padding(16.dp)) {
            Label("Recordings", Modifier.padding(8.dp), size = 24, bold = true, color = ui.accent)
            listOf("done" to "All recordings", "now" to "Recording now", "scheduled" to "Scheduled").forEachIndexed { i, (k, t) ->
                FocusRow(selected = tab == k, focusRequester = if (i == 0) first else null, onFocus = { tab = k }, onClick = { tab = k }) {
                    Label(t, Modifier.weight(1f)); Dim(when (k) { "now" -> recs.count { it.status == RecStatus.RECORDING }; "scheduled" -> recs.count { it.status == RecStatus.SCHEDULED }; else -> recs.count { it.status == RecStatus.DONE } }.toString())
                }
            }
            Spacer(Modifier.height(12.dp))
            FocusRow(onClick = { custom = true }) { Label("New recording…") }
            Spacer(Modifier.height(12.dp))
            Dim("Recordings may need a second connection to your provider. Change the folder in Settings › Recordings.", maxLines = 5)
        }
        LazyColumn(Modifier.fillMaxSize().padding(24.dp)) {
            if (shown.isEmpty()) item { Dim("No recordings", size = 16) }
            itemsIndexed(shown) { _, r ->
                FocusRow(onClick = { if (r.status == RecStatus.DONE || r.status == RecStatus.RECORDING) vm.playRecording(r) else menu = r }, onLongClick = { menu = r }) {
                    Column(Modifier.weight(1f)) {
                        Label(r.title)
                        Dim("${r.channelName}  ·  ${dayLabel(r.start)} ${hm(r.start)} – ${hm(r.stop)}${if (r.repeat.isNotEmpty()) "  ·  repeats ${r.repeat}" else ""}")
                        if (r.error.isNotEmpty()) Dim(r.error)
                    }
                    Dim(when (r.status) { RecStatus.FAILED -> "Failed"; RecStatus.CANCELLED -> "Cancelled"; RecStatus.RECORDING -> "● ${r.sizeBytes / 1_000_000} MB"; RecStatus.DONE -> "${r.sizeBytes / 1_000_000} MB"; else -> "" })
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler { vm.back() }
    menu?.let { r ->
        ActionsDialog(r.title, buildList<Pair<String, () -> Unit>> {
            if (r.status == RecStatus.DONE || r.status == RecStatus.RECORDING) add("Play" to { vm.playRecording(r) })
            if (r.status == RecStatus.RECORDING) add("Stop recording" to { vm.cancelRecording(r, delete = false) })
            if (r.status == RecStatus.SCHEDULED) add("Cancel recording" to { vm.cancelRecording(r, delete = true) })
            if (r.status != RecStatus.SCHEDULED) add("Delete recording" to { vm.cancelRecording(r, delete = true) })
        }) { menu = null }
    }
    if (custom) CustomRecordingDialog(vm) { custom = false }
}

@Composable
private fun CustomRecordingDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf("channel") }
    var channel by remember { mutableStateOf<Channel?>(null) }
    var startOffsetMin by remember { mutableIntStateOf(0) }
    var durationMin by remember { mutableIntStateOf(60) }
    val channels = vm.catalog.value.channels
    when (step) {
        "channel" -> ChoiceDialog("Channel", channels.map { it to "${it.number}  ${it.name}" }, null, onDismiss) { channel = it; step = "start" }
        "start" -> ChoiceDialog(
            "Start", (0..7 * 24 * 60 step 15).map { it to if (it == 0) "Now" else "${dayLabel(vm.nowEpoch() + it * 60)} ${hm((vm.nowEpoch() / 900 * 900) + it * 60)}" }, startOffsetMin, onDismiss,
        ) { startOffsetMin = it; step = "duration" }
        "duration" -> ChoiceDialog("Duration", (15..360 step 15).map { it to "%d:%02d".format(it / 60, it % 60) }, durationMin, onDismiss) { durationMin = it; step = "repeat" }
        "repeat" -> ChoiceDialog("Repeat", listOf("" to "Once", "daily" to "Every day", "weekly" to "Every week"), "", onDismiss) { rep ->
            val c = channel ?: return@ChoiceDialog
            val start = if (startOffsetMin == 0) vm.nowEpoch() else (vm.nowEpoch() / 900 * 900) + startOffsetMin * 60L
            vm.scheduleRecording(c, "${c.name} ${hm(start)}", start, start + durationMin * 60L, rep)
            onDismiss()
        }
    }
}

@Composable
fun RemindersScreen(vm: MainViewModel) {
    val ui = LocalUi.current
    val list by vm.reminders.collectAsState()
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0D11)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("Reminders", size = 26, bold = true, color = ui.accent)
        if (list.isEmpty()) {
            Dim("No reminders. Pick a future program in the TV guide and choose Remind me.", size = 16, maxLines = 2)
            FocusRow(focusRequester = first, onClick = { vm.back() }) { Label("Back") }
        }
        LazyColumn {
            itemsIndexed(list) { i, r ->
                FocusRow(focusRequester = if (i == 0) first else null, onClick = { vm.deleteReminder(r.id) }) {
                    Column(Modifier.weight(1f)) { Label(r.title); Dim("${r.channelName}  ·  ${dayLabel(r.start)} ${hm(r.start)}") }
                    Dim("OK to delete")
                }
            }
        }
    }
    LaunchedEffect(list.isEmpty()) { delay(100); runCatching { first.requestFocus() } }
    BackHandler { vm.back() }
}

/** Favorites, visibility, blocking, manual order and copying channels into own groups. */
@Composable
fun ManageScreen(vm: MainViewModel, mode: String, groupId: String) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val catalog by vm.catalog.collectAsState()
    val ref = GroupRef.parse(groupId) ?: GroupRef.ALL
    val source = when (mode) {
        "hidden" -> catalog.everything.filter { c -> ref.kind == GroupKind.ALL || (ref.kind == GroupKind.GROUP && c.playlistId == ref.playlistId && c.e.groupName == ref.name) || catalog.channelsIn(ref).any { it.uid == c.uid } }
        "favorites" -> if (ref.kind == GroupKind.FAVORITES) catalog.channels else catalog.channelsIn(ref)
        else -> catalog.channelsIn(ref)
    }
    val items = remember(groupId, mode) { mutableStateListOf<Channel>().apply { addAll(if (mode == "reorder" && ref.kind == GroupKind.FAVORITES) catalog.favorites else source) } }
    val marked = remember(groupId, mode) {
        mutableStateListOf<String>().apply {
            addAll(source.filter { when (mode) { "favorites" -> it.favorite; "hidden" -> !it.hidden; "blocked" -> it.pref?.blocked == true; else -> false } }.map { it.uid })
        }
    }
    var picked by remember { mutableStateOf<String?>(null) }
    var copyTarget by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val title = when (mode) { "favorites" -> "Manage favorites"; "hidden" -> "Manage visibility"; "blocked" -> "Manage blocking"; "reorder" -> "Reorder channels"; else -> "Copy channels" }
    val help = when (mode) {
        "reorder" -> "OK: pick up or drop a channel · Up/Down: move it"
        "copy" -> "OK: select channels · Menu: copy the selected channels to a group"
        "hidden" -> "OK: show or hide"
        "blocked" -> "OK: block or unblock (needs the PIN to open)"
        else -> "OK: add or remove"
    }

    fun save() {
        scope.launch {
            when (mode) {
                "favorites" -> for (c in source) { val on = c.uid in marked; if (on != c.favorite) vm.repo.setFavorite(c, on) }
                "hidden" -> for (c in source) { val visible = c.uid in marked; if (visible == c.hidden) vm.repo.setHidden(c, !visible) }
                "blocked" -> for (c in source) { val b = c.uid in marked; if (b != (c.pref?.blocked == true)) vm.repo.setBlocked(c, b) }
                "reorder" -> when (ref.kind) {
                    GroupKind.FAVORITES -> vm.repo.setFavoritesOrder(items)
                    GroupKind.CUSTOM -> vm.repo.setCustomGroupOrder(ref.customId, items)
                    else -> {
                        vm.repo.setChannelsOrder(items)
                        if (ref.kind == GroupKind.GROUP) vm.repo.setGroupSort(ref, "manual") else vm.settings.channelSort = "manual"
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0D11)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Label(title, size = 24, bold = true, color = ui.accent); Dim("${vm.groupTitle(ref)}  ·  $help") }
            if (mode != "reorder" && mode != "copy") {
                Box(Modifier.width(150.dp)) { FocusRow(onClick = { marked.clear(); marked.addAll(items.map { it.uid }) }) { Label("Select all") } }
                Box(Modifier.width(150.dp)) { FocusRow(onClick = { marked.clear() }) { Label("Clear all") } }
            }
            if (mode == "copy") Box(Modifier.width(200.dp)) { FocusRow(onClick = { if (marked.isNotEmpty()) copyTarget = true else vm.say("Select channels first") }) { Label("Copy ${marked.size}…") } }
            Box(Modifier.width(150.dp)) { FocusRow(onClick = { save(); vm.back() }) { Label("Done", bold = true) } }
        }
        LazyColumn(Modifier.onPreviewKeyEvent { ev ->
            if (mode == "copy" && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == AKeyEvent.KEYCODE_MENU) { if (marked.isNotEmpty()) copyTarget = true; true } else false
        }) {
            itemsIndexed(items, key = { _, c -> c.uid }) { i, c ->
                val isPicked = picked == c.uid
                Box(
                    Modifier.onPreviewKeyEvent { ev ->
                        if (mode != "reorder" || picked != c.uid || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val idx = items.indexOfFirst { it.uid == c.uid }
                        when (ev.nativeKeyEvent.keyCode) {
                            AKeyEvent.KEYCODE_DPAD_UP -> { if (idx > 0) items.add(idx - 1, items.removeAt(idx)); true }
                            AKeyEvent.KEYCODE_DPAD_DOWN -> { if (idx < items.lastIndex) items.add(idx + 1, items.removeAt(idx)); true }
                            AKeyEvent.KEYCODE_DPAD_LEFT -> { items.add(0, items.removeAt(idx)); true }
                            AKeyEvent.KEYCODE_DPAD_RIGHT -> { items.add(items.removeAt(idx)); true }
                            else -> false
                        }
                    },
                ) {
                    FocusRow(selected = isPicked, focusRequester = if (i == 0) first else null, onClick = {
                        if (mode == "reorder") picked = if (isPicked) null else c.uid
                        else if (c.uid in marked) marked.remove(c.uid) else marked.add(c.uid)
                    }) {
                        if (mode == "reorder") Label(if (isPicked) "↕" else "${i + 1}", Modifier.width(40.dp), color = ui.accent)
                        else CheckMark(c.uid in marked)
                        Dim(c.number.toString(), Modifier.width(44.dp))
                        Logo(c.logo, c.name)
                        Label(c.name, Modifier.weight(1f))
                        Dim(c.groupTitle)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
    BackHandler { save(); vm.back() }
    if (copyTarget) {
        val groups = catalog.groups.filter { it.ref.kind == GroupKind.CUSTOM }
        var naming by remember { mutableStateOf(false) }
        val chosen = items.filter { it.uid in marked }
        if (naming) TextInputDialog("New group", "", onDismiss = { copyTarget = false }) { n ->
            if (n.isNotEmpty()) scope.launch { vm.repo.createCustomGroup(n, chosen); vm.say("Channels copied") }
        } else ActionsDialog("Copy ${chosen.size} channels to", groups.map { g -> g.title to { scope.launch { vm.repo.addToCustomGroup(g.ref.customId, chosen); vm.say("Channels copied") }; Unit } } +
            listOf<Pair<String, () -> Unit>>("New group…" to { naming = true })) { if (!naming) copyTarget = false }
    }
}

/** Up to four channels at once; the focused one has sound. */
@Composable
fun MultiviewScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val start = vm.current.value
    val tiles = remember { mutableStateListOf<Channel?>().apply { add(start); add(null) } }
    val players = remember { mutableStateListOf<ExoPlayer>() }
    var focusedTile by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(-1) }
    var tileMenu by remember { mutableStateOf(-1) }
    val first = remember { FocusRequester() }

    fun ensurePlayers() { while (players.size < tiles.size) players.add(PlayerFactory.create(ctx, vm.settings, AudioOffset(), forMultiview = true)) }
    fun playTile(i: Int) {
        val c = tiles.getOrNull(i) ?: run { players.getOrNull(i)?.stop(); return }
        scope.launch {
            runCatching {
                val p = players[i]
                p.setMediaSource(StreamResolver.mediaSource(ctx, vm.resolver.live(c)))
                p.prepare(); p.playWhenReady = true
            }.onFailure { vm.say("${c.name}: ${it.message}") }
        }
    }
    fun audioTo(i: Int) { players.forEachIndexed { n, p -> p.volume = if (n == i) 1f else 0f } }

    LaunchedEffect(Unit) {
        vm.player.pause()
        ensurePlayers()
        tiles.indices.forEach { playTile(it) }
        audioTo(0)
    }
    DisposableEffect(Unit) {
        onDispose {
            players.forEach { it.release() }
            players.clear()
            vm.onStart()
        }
    }

    val cols = if (tiles.size <= 1) 1 else 2
    val rows = (tiles.size + 1) / 2
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        for (r in 0 until rows) Row(Modifier.weight(1f).fillMaxWidth()) {
            for (cIdx in 0 until cols) {
                val i = r * cols + cIdx
                if (i >= tiles.size) { Spacer(Modifier.weight(1f)); continue }
                var focused by remember { mutableStateOf(false) }
                Box(
                    Modifier.weight(1f).fillMaxHeight().padding(3.dp)
                        .border(3.dp, if (focused) ui.focus else Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { focused = it.isFocused; if (it.isFocused) { focusedTile = i; audioTo(i) } }
                        .tvClickable(onLongClick = { tileMenu = i }) { if (tiles[i] == null) picking = i else tileMenu = i },
                ) {
                    val p = players.getOrNull(i)
                    if (p != null && tiles[i] != null) AndroidView(
                        factory = { PlayerView(it).apply { useController = false; isFocusable = false; player = p; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
                        update = { if (it.player !== p) it.player = p }, modifier = Modifier.fillMaxSize(),
                    )
                    val c = tiles[i]
                    Box(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Label(c?.let { "${it.number}  ${it.name}" + if (focusedTile == i) "  🔊" else "" } ?: "OK: choose a channel", size = 13)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    BackHandler { vm.back() }

    if (picking >= 0) {
        val idx = picking
        ChoiceDialog("Channel", vm.list().map { it to "${it.number}  ${it.name}" }, tiles.getOrNull(idx), { picking = -1 }) { c ->
            vm.withPin("Enter PIN", c.blocked) { tiles[idx] = c; ensurePlayers(); playTile(idx) }
            picking = -1
        }
    }
    if (tileMenu >= 0) {
        val idx = tileMenu
        ActionsDialog(tiles.getOrNull(idx)?.name ?: "Screen ${idx + 1}", buildList<Pair<String, () -> Unit>> {
            add("Change channel" to { picking = idx })
            tiles.getOrNull(idx)?.let { c -> add("Watch full screen" to { vm.back(); vm.play(c) }) }
            if (tiles.size < 4) add("Add screen" to { tiles.add(null); ensurePlayers() })
            if (tiles.size > 1) add("Remove screen" to {
                players.getOrNull(idx)?.let { it.release(); players.removeAt(idx) }
                tiles.removeAt(idx)
            })
        }) { tileMenu = -1 }
    }
    @Suppress("UNUSED_EXPRESSION") Player.STATE_IDLE
}
