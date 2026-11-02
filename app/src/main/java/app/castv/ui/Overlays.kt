package app.castv.ui

import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.castv.data.Channel
import app.castv.data.Group
import app.castv.data.GroupKind
import app.castv.data.GroupRef
import app.castv.data.ProgrammeEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Key reading for list-style overlays: one focus target, selection kept in state (fast with thousands of rows). */
private class Keys(
    val onUp: () -> Unit = {}, val onDown: () -> Unit = {}, val onLeft: () -> Unit = {}, val onRight: () -> Unit = {},
    val onOk: () -> Unit = {}, val onLongOk: () -> Unit = {}, val onBack: () -> Unit = {}, val onPageUp: () -> Unit = {},
    val onPageDown: () -> Unit = {}, val onMenu: () -> Unit = {}, val onDigit: (Int) -> Unit = {}, val onLongLeft: () -> Unit = {},
    val onFf: () -> Unit = {}, val onRw: () -> Unit = {}, val onInfo: () -> Unit = {},
)

private fun Modifier.keys(k: Keys, press: KeyPress): Modifier = onPreviewKeyEvent { ev ->
    val n = ev.nativeKeyEvent
    val code = n.keyCode
    val down = ev.type == KeyEventType.KeyDown
    val up = ev.type == KeyEventType.KeyUp
    when (code) {
        AKeyEvent.KEYCODE_DPAD_UP -> { if (down) k.onUp(); true }
        AKeyEvent.KEYCODE_DPAD_DOWN -> { if (down) k.onDown(); true }
        AKeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) k.onRight(); true }
        AKeyEvent.KEYCODE_DPAD_LEFT -> {
            when (press.handle(code, down, up, n.repeatCount, true)) { 1 -> k.onLeft(); 2 -> k.onLongLeft() }
            true
        }
        AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_NUMPAD_ENTER -> {
            when (press.handle(code, down, up, n.repeatCount, true)) { 1 -> k.onOk(); 2 -> k.onLongOk() }
            true
        }
        AKeyEvent.KEYCODE_BACK, AKeyEvent.KEYCODE_ESCAPE -> {
            if (press.handle(code, down, up, n.repeatCount, false) == 1) k.onBack()
            true
        }
        AKeyEvent.KEYCODE_CHANNEL_UP, AKeyEvent.KEYCODE_PAGE_UP -> { if (down) k.onPageUp(); true }
        AKeyEvent.KEYCODE_CHANNEL_DOWN, AKeyEvent.KEYCODE_PAGE_DOWN -> { if (down) k.onPageDown(); true }
        AKeyEvent.KEYCODE_MENU -> { if (down) k.onMenu(); true }
        AKeyEvent.KEYCODE_INFO -> { if (down) k.onInfo(); true }
        AKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) k.onFf(); true }
        AKeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) k.onRw(); true }
        in AKeyEvent.KEYCODE_0..AKeyEvent.KEYCODE_9 -> { if (down) k.onDigit(code - AKeyEvent.KEYCODE_0); true }
        else -> false
    }
}

private suspend fun LazyListState.keepVisible(index: Int) {
    val info = layoutInfo.visibleItemsInfo
    if (info.isEmpty()) { scrollToItem(index); return }
    val first = info.first().index
    val last = info.last().index
    if (index <= first) scrollToItem((index - 1).coerceAtLeast(0))
    else if (index >= last) scrollToItem((index - info.size + 3).coerceAtLeast(0))
}

@Composable
fun ChannelListOverlay(vm: MainViewModel, startWithGroups: Boolean) {
    val ui = LocalUi.current
    val s = vm.settings
    val catalog by vm.catalog.collectAsState()
    val currentGroup by vm.group.collectAsState()
    val current by vm.current.collectAsState()
    var groupsOpen by remember { mutableStateOf(startWithGroups) }
    val groups = catalog.groups
    var gIndex by remember { mutableIntStateOf(groups.indexOfFirst { it.ref.id == currentGroup.id }.coerceAtLeast(0)) }
    val shownGroup = if (groupsOpen) groups.getOrNull(gIndex)?.ref ?: currentGroup else currentGroup
    val channels = catalog.channelsIn(shownGroup)
    var cIndex by remember(shownGroup.id) { mutableIntStateOf(channels.indexOfFirst { it.uid == current?.uid }.coerceAtLeast(0)) }
    val gState = rememberLazyListState(gIndex.coerceAtLeast(0))
    val cState = rememberLazyListState(cIndex.coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    val press = remember { KeyPress() }
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    var groupMenu by remember { mutableStateOf<Group?>(null) }
    val scope = rememberCoroutineScope()

    fun close() { vm.overlay.value = Overlay.NONE }
    fun selectGroup(g: Group) {
        vm.withPin("Enter PIN to open ${g.title}", g.blocked) { vm.setGroup(g.ref); groupsOpen = false }
    }

    val keys = Keys(
        onUp = { if (groupsOpen) gIndex = (gIndex - 1).coerceAtLeast(0) else if (channels.isNotEmpty()) cIndex = (cIndex - 1 + channels.size) % channels.size },
        onDown = { if (groupsOpen) gIndex = (gIndex + 1).coerceAtMost(groups.lastIndex) else if (channels.isNotEmpty()) cIndex = (cIndex + 1) % channels.size },
        onPageUp = { if (groupsOpen) gIndex = (gIndex - 8).coerceAtLeast(0) else cIndex = (cIndex - 8).coerceAtLeast(0) },
        onPageDown = { if (groupsOpen) gIndex = (gIndex + 8).coerceAtMost(groups.lastIndex) else cIndex = (cIndex + 8).coerceAtMost(channels.lastIndex.coerceAtLeast(0)) },
        onLeft = { if (!groupsOpen) { groupsOpen = true; gIndex = groups.indexOfFirst { it.ref.id == currentGroup.id }.coerceAtLeast(0) } },
        onRight = {
            if (groupsOpen) groups.getOrNull(gIndex)?.let { selectGroup(it) }
            else vm.overlay.value = Overlay.GUIDE
        },
        onOk = {
            if (groupsOpen) groups.getOrNull(gIndex)?.let { selectGroup(it) }
            else channels.getOrNull(cIndex)?.let { c ->
                if (c.uid == current?.uid && vm.mode.value == Mode.Live) close()
                else { vm.play(c, showInfo = false); if (!s.listStayOnSwitch) close() }
            }
        },
        onLongOk = { if (groupsOpen) groupMenu = groups.getOrNull(gIndex) else menuFor = channels.getOrNull(cIndex) },
        onMenu = { if (groupsOpen) groupMenu = groups.getOrNull(gIndex) else menuFor = channels.getOrNull(cIndex) },
        onBack = { if (groupsOpen && !startWithGroups) groupsOpen = false else close() },
        onDigit = { vm.digit(it) },
    )

    Box(Modifier.fillMaxSize().focusRequester(focus).keys(keys, press).focusable()) {
        Row(Modifier.fillMaxHeight().background(Brush.horizontalGradient(listOf(ui.panelStrong, ui.panelStrong, ui.panel, Color.Transparent)))) {
            if (groupsOpen) {
                Column(Modifier.width(280.dp).fillMaxHeight().padding(vertical = 24.dp)) {
                    Label("Groups", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), size = 18, bold = true, color = ui.accent)
                    LazyColumn(Modifier.weight(1f), state = gState) {
                        itemsIndexed(groups, key = { _, g -> g.ref.id }) { i, g ->
                            GroupRow(g, selected = i == gIndex, current = g.ref.id == currentGroup.id,
                                dividerBelow = (g.favorite || g.ref.kind == GroupKind.FAVORITES) && groups.getOrNull(i + 1)?.let { !it.favorite } == true)
                        }
                    }
                    Dim("Hold OK: favorite, rename, hide…", Modifier.padding(horizontal = 24.dp, vertical = 6.dp), size = 12)
                }
            }
            Column(Modifier.width(470.dp).fillMaxHeight().padding(vertical = 24.dp)) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(vm.groupTitle(shownGroup), Modifier.weight(1f), size = 18, bold = true)
                    Dim("${channels.size}")
                }
                if (channels.isEmpty()) Dim(if (shownGroup.kind == GroupKind.FAVORITES) "No favorites yet. Long-press OK on a channel to add it." else "No channels", Modifier.padding(24.dp), maxLines = 3)
                LazyColumn(state = cState) {
                    itemsIndexed(channels, key = { _, c -> c.uid }) { i, c ->
                        ChannelRow(vm, c, selected = !groupsOpen && i == cIndex, playing = c.uid == current?.uid)
                    }
                }
            }
            channels.getOrNull(cIndex)?.takeIf { !groupsOpen }?.let { ChannelDetails(vm, it, Modifier.width(360.dp).fillMaxHeight().padding(24.dp)) }
        }
    }

    LaunchedEffect(cIndex, shownGroup.id) { if (!groupsOpen && channels.isNotEmpty()) cState.keepVisible(cIndex) }
    LaunchedEffect(gIndex, groupsOpen) { if (groupsOpen && groups.isNotEmpty()) gState.keepVisible(gIndex) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { close() }

    menuFor?.let { c -> ChannelMenu(vm, c, onDismiss = { menuFor = null; scope.launch { delay(50); runCatching { focus.requestFocus() } } }) }
    groupMenu?.let { g -> GroupMenu(vm, g, onDismiss = { groupMenu = null; scope.launch { delay(50); runCatching { focus.requestFocus() } } }) }
}

@Composable
private fun ChannelRow(vm: MainViewModel, c: Channel, selected: Boolean, playing: Boolean) {
    val ui = LocalUi.current
    val s = vm.settings
    val minute by produceState(System.currentTimeMillis() / 60000) { while (true) { delay(60_000); value = System.currentTimeMillis() / 60000 } }
    val now by produceState<ProgrammeEntity?>(null, c.uid, minute) { value = if (s.showCurrentPrograms) vm.repo.nowNext(c).now else null }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) ui.focus else if (playing && s.highlightCurrentChannel) ui.selection else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (s.showChannelNumbers) Label(c.number.toString(), Modifier.width(44.dp), size = 15, color = if (selected) ui.text else ui.dim)
        if (s.showChannelLogos) Logo(c.logo, c.name)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.showChannelNames) Label(c.name, Modifier.weight(1f, fill = false), size = 16, maxLines = if (s.twoLineChannelNames) 2 else 1)
                if (c.favorite) Label(" ★", size = 13, color = if (selected) ui.text else ui.accent)
                if (c.blocked) Dim(" 🔒")
                if (c.hasCatchup && s.showCatchupIcon) Dim(" ⟲")
            }
            now?.let { p ->
                Dim(p.title, size = 13)
                Bar((System.currentTimeMillis() / 1000 - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1), Modifier.fillMaxWidth().padding(top = 3.dp), 2.dp,
                    if (selected) Color.White else ui.accent)
            }
        }
    }
}

@Composable
private fun ChannelDetails(vm: MainViewModel, c: Channel, modifier: Modifier) {
    val nn by produceState(app.castv.data.NowNext(null, null), c.uid) { value = vm.repo.nowNext(c) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(40.dp))
        Label(c.name, size = 20, bold = true, maxLines = 2)
        Dim(c.groupTitle)
        nn.now?.let { p ->
            Spacer(Modifier.height(6.dp))
            Label(p.title, size = 17, maxLines = 2)
            Dim("${hm(p.start)} – ${hm(p.stop)}${if (p.episode.isNotEmpty()) "  ·  " + p.episode else ""}")
            if (p.description.isNotEmpty()) Dim(p.description, size = 14, maxLines = 8)
        } ?: Dim("No program information")
        nn.next?.let { Dim("Next  ${hm(it.start)}  ${it.title}", size = 14, maxLines = 2) }
    }
}

// --------------------------------------------------------------------------------------------- guide

@Composable
fun GuideOverlay(vm: MainViewModel, fullScreen: Boolean = false) {
    val ui = LocalUi.current
    val s = vm.settings
    val catalog by vm.catalog.collectAsState()
    val currentGroup by vm.group.collectAsState()
    val current by vm.current.collectAsState()
    val channels = catalog.channelsIn(currentGroup)
    val groups = catalog.groups
    val nowSec = System.currentTimeMillis() / 1000
    val slot = 1800L
    val span = s.guideHoursVisible.coerceIn(1, 4) * 3600L
    var windowStart by remember { mutableLongStateOf(nowSec / slot * slot) }
    var selTime by remember { mutableLongStateOf(nowSec) }
    var row by remember(currentGroup.id) { mutableIntStateOf(channels.indexOfFirst { it.uid == current?.uid }.coerceAtLeast(0)) }
    var groupsOpen by remember { mutableStateOf(false) }
    var gIndex by remember { mutableIntStateOf(0) }
    val state = rememberLazyListState(row)
    val focus = remember { FocusRequester() }
    val press = remember { KeyPress() }
    val cache = remember { mutableStateMapOf<String, List<ProgrammeEntity>>() }
    var menu by remember { mutableStateOf<Pair<Channel, ProgrammeEntity?>?>(null) }
    var details by remember { mutableStateOf<Pair<Channel, ProgrammeEntity>?>(null) }
    var groupMenu by remember { mutableStateOf<Group?>(null) }
    val scope = rememberCoroutineScope()
    val day = windowStart / 86400

    fun progs(c: Channel) = cache["${c.uid}@$day"].orEmpty()
    fun selected(): ProgrammeEntity? = channels.getOrNull(row)?.let { c -> progs(c).firstOrNull { selTime >= it.start && selTime < it.stop } }
    fun ensureVisible() {
        while (selTime < windowStart) windowStart -= slot
        while (selTime >= windowStart + span - slot / 2) windowStart += slot
    }
    fun close() { if (fullScreen) vm.back() else vm.overlay.value = Overlay.NONE }

    val keys = Keys(
        onUp = { if (groupsOpen) gIndex = (gIndex - 1).coerceAtLeast(0) else if (channels.isNotEmpty()) row = (row - 1 + channels.size) % channels.size },
        onDown = { if (groupsOpen) gIndex = (gIndex + 1).coerceAtMost(groups.lastIndex) else if (channels.isNotEmpty()) row = (row + 1) % channels.size },
        onPageUp = { row = (row - 8).coerceAtLeast(0) },
        onPageDown = { row = (row + 8).coerceAtMost(channels.lastIndex.coerceAtLeast(0)) },
        onLeft = left@{
            if (groupsOpen) return@left
            val p = selected()
            val isNowProgram = p != null && p.start <= nowSec && p.stop > nowSec
            if ((isNowProgram || (p == null && selTime <= nowSec)) && windowStart <= nowSec) {
                groupsOpen = true; gIndex = groups.indexOfFirst { it.ref.id == currentGroup.id }.coerceAtLeast(0)
            } else {
                val c = channels.getOrNull(row)
                val prev = c?.let { ch -> progs(ch).lastOrNull { it.stop <= (p?.start ?: selTime) } }
                selTime = prev?.start?.coerceAtLeast(windowStart - span) ?: (selTime - slot)
                ensureVisible()
            }
        },
        onLongLeft = {
            val p = selected()
            val c = channels.getOrNull(row)
            val prev = c?.let { ch -> progs(ch).lastOrNull { it.stop <= (p?.start ?: selTime) } }
            selTime = prev?.start ?: (selTime - slot); ensureVisible()
        },
        onRight = right@{
            if (groupsOpen) { groups.getOrNull(gIndex)?.let { g -> vm.withPin("Enter PIN", g.blocked) { vm.setGroup(g.ref); groupsOpen = false } }; return@right }
            val p = selected()
            val c = channels.getOrNull(row)
            val next = c?.let { ch -> progs(ch).firstOrNull { it.start >= (p?.stop ?: (selTime + 1)) } }
            selTime = next?.start ?: ((selTime / slot + 1) * slot)
            ensureVisible()
        },
        onOk = ok@{
            if (groupsOpen) { groups.getOrNull(gIndex)?.let { g -> vm.withPin("Enter PIN", g.blocked) { vm.setGroup(g.ref); groupsOpen = false } }; return@ok }
            val c = channels.getOrNull(row) ?: return@ok
            val p = selected()
            when {
                p == null -> { vm.play(c, false); if (!s.guideStayOnSwitch) { if (fullScreen) vm.home() else close() } }
                p.start > nowSec -> menu = c to p
                p.stop > nowSec -> { vm.play(c, false); if (!s.guideStayOnSwitch) { if (fullScreen) vm.home() else close() } }
                else -> { vm.playProgramme(c, p); if (fullScreen) vm.home() else close() }
            }
        },
        onLongOk = { if (groupsOpen) groupMenu = groups.getOrNull(gIndex) else channels.getOrNull(row)?.let { menu = it to selected() } },
        onMenu = { if (groupsOpen) groupMenu = groups.getOrNull(gIndex) else channels.getOrNull(row)?.let { menu = it to selected() } },
        onInfo = { val c = channels.getOrNull(row); val p = selected(); if (c != null && p != null) details = c to p },
        onFf = { selTime += 86400; windowStart += 86400 },
        onRw = { selTime -= 86400; windowStart -= 86400 },
        onBack = {
            when {
                groupsOpen -> groupsOpen = false
                windowStart != nowSec / slot * slot -> { windowStart = nowSec / slot * slot; selTime = nowSec }
                else -> close()
            }
        },
        onDigit = { d ->
            vm.numberEntry.value = (vm.numberEntry.value + d).takeLast(4)
            val n = vm.numberEntry.value.toIntOrNull()
            val i = channels.indexOfFirst { it.number == n }
            if (i >= 0) row = i
            scope.launch { delay(1500); vm.numberEntry.value = "" }
        },
    )

    // Load programmes for the rows on screen (and a few around them).
    LaunchedEffect(channels, day) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index } }.collect { visible ->
            val from = visible.minOrNull() ?: 0
            val to = (visible.maxOrNull() ?: 10) + 6
            for (i in (from - 3).coerceAtLeast(0)..to.coerceAtMost(channels.lastIndex)) {
                val c = channels[i]
                val k = "${c.uid}@$day"
                if (!cache.containsKey(k)) cache[k] = vm.repo.programmes(c, day * 86400 - 6 * 3600, day * 86400 + 30 * 3600)
            }
        }
    }

    val bg = if (fullScreen) Color(0xFF0B0D11) else ui.panelStrong
    Column(Modifier.fillMaxSize().background(if (fullScreen) bg else Color.Transparent).focusRequester(focus).keys(keys, press).focusable()) {
        // Top: details of the selected programme; the video stays visible on the right in preview mode.
        Row(Modifier.fillMaxWidth().height(if (fullScreen) 170.dp else 210.dp).background(Brush.horizontalGradient(listOf(bg, bg.copy(alpha = 0.7f), Color.Transparent)))) {
            Column(Modifier.width(620.dp).padding(start = 32.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val c = channels.getOrNull(row)
                val p = selected()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(vm.groupTitle(currentGroup), size = 14, color = ui.accent)
                    Spacer(Modifier.width(12.dp))
                    Dim(dayLabel(selTime))
                }
                Label(p?.title ?: c?.name.orEmpty(), size = 22, bold = true)
                if (p != null) Dim("${c?.name}  ·  ${hm(p.start)} – ${hm(p.stop)}${if (p.episode.isNotEmpty()) "  ·  " + p.episode else ""}${if (p.category.isNotEmpty()) "  ·  " + p.category else ""}")
                if (p != null && p.description.isNotEmpty()) Dim(p.description, size = 14, maxLines = 3)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.padding(24.dp)) { Clock(s.showDateOnClock) }
        }
        Row(Modifier.fillMaxSize().background(bg)) {
            if (groupsOpen) {
                LazyColumn(Modifier.width(260.dp).fillMaxHeight().padding(vertical = 8.dp)) {
                    itemsIndexed(groups) { i, g ->
                        GroupRow(g, selected = i == gIndex, current = g.ref.id == currentGroup.id,
                            dividerBelow = (g.favorite || g.ref.kind == GroupKind.FAVORITES) && groups.getOrNull(i + 1)?.let { !it.favorite } == true)
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val channelCol = 250.dp
                val gridWidth = maxWidth - channelCol
                val dpPerSec = gridWidth / span.toFloat()
                Column {
                    // Time ruler.
                    Box(Modifier.fillMaxWidth().height(28.dp)) {
                        var t = windowStart
                        while (t < windowStart + span) {
                            Dim(hm(t), Modifier.offset(x = channelCol + dpPerSec * (t - windowStart).toFloat()).padding(start = 4.dp))
                            t += slot
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = state) {
                            itemsIndexed(channels, key = { _, c -> c.uid }) { i, c ->
                                GuideRow(vm, c, progs(c), i == row && !groupsOpen, selTime, windowStart, span, dpPerSec, channelCol, nowSec, c.uid == current?.uid)
                            }
                        }
                        if (nowSec in windowStart until windowStart + span) {
                            Box(
                                Modifier.offset(x = channelCol + dpPerSec * (nowSec - windowStart).toFloat()).width(2.dp)
                                    .then(if (s.currentTimeFullHeight) Modifier.fillMaxHeight() else Modifier.height(10.dp)).background(ui.accent)
                            )
                        }
                    }
                }
                if (channels.isEmpty()) Dim("No channels in this group", Modifier.padding(32.dp))
            }
        }
    }

    LaunchedEffect(row, currentGroup.id) { if (channels.isNotEmpty()) state.keepVisible(row) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    if (!fullScreen) BackHandler { close() }

    menu?.let { (c, p) ->
        if (p != null) ProgrammeMenu(vm, c, p, onDismiss = { menu = null; scope.launch { delay(50); runCatching { focus.requestFocus() } } }, onDetails = { details = c to p }, onPlayed = { if (fullScreen) vm.home() else close() })
        else ChannelMenu(vm, c, onDismiss = { menu = null; scope.launch { delay(50); runCatching { focus.requestFocus() } } })
    }
    details?.let { (c, p) -> ProgrammeDetails(vm, c, p) { details = null; scope.launch { delay(50); runCatching { focus.requestFocus() } } } }
    groupMenu?.let { g -> GroupMenu(vm, g, onDismiss = { groupMenu = null; scope.launch { delay(50); runCatching { focus.requestFocus() } } }) }
}

/** One entry of the groups panel: favorites carry a star and are separated from the rest by a line. */
@Composable
private fun GroupRow(g: Group, selected: Boolean, current: Boolean, dividerBelow: Boolean) {
    val ui = LocalUi.current
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(8.dp))
                .background(if (selected) ui.focus else if (current) ui.selection else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                g.ref.kind == GroupKind.FAVORITES -> Label("♥ ", color = if (selected) ui.text else ui.accent)
                g.favorite -> Label("★ ", color = if (selected) ui.text else ui.accent)
                g.ref.kind == GroupKind.PLAYLIST -> Label("▸ ")
            }
            Label(g.title, Modifier.weight(1f), bold = g.ref.kind == GroupKind.PLAYLIST || g.ref.kind == GroupKind.FAVORITES)
            if (g.blocked) Dim("🔒 ")
            if (g.isNew) Dim("new ")
            Dim(g.count.toString())
        }
        if (dividerBelow) Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).height(1.dp).background(Color(0x33FFFFFF)))
    }
}

@Composable
private fun GuideRow(
    vm: MainViewModel, c: Channel, progs: List<ProgrammeEntity>, selectedRow: Boolean, selTime: Long, windowStart: Long, span: Long,
    dpPerSec: androidx.compose.ui.unit.Dp, channelCol: androidx.compose.ui.unit.Dp, nowSec: Long, playing: Boolean,
) {
    val ui = LocalUi.current
    val s = vm.settings
    val windowEnd = windowStart + span
    val reminders by vm.reminders.collectAsState()
    val recordings by vm.recordings.collectAsState()
    Row(Modifier.fillMaxWidth().height(54.dp).padding(vertical = 2.dp)) {
        Row(
            Modifier.width(channelCol).fillMaxHeight().padding(horizontal = 6.dp).clip(RoundedCornerShape(6.dp))
                .background(if (selectedRow) ui.selection else if (playing && s.highlightCurrentChannel) Color(0x22FFFFFF) else Color.Transparent)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (s.showChannelNumbers) Dim(c.number.toString(), Modifier.width(36.dp), size = 14)
            if (s.showChannelLogos) Logo(c.logo, c.name, Modifier.size(width = 48.dp, height = 32.dp))
            Label(c.name, Modifier.weight(1f), size = 14, maxLines = if (s.twoLineChannelNames) 2 else 1)
        }
        Box(Modifier.fillMaxSize()) {
            val visible = progs.filter { it.stop > windowStart && it.start < windowEnd }
            if (visible.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 1.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (selectedRow) ui.focus else Color(0x18FFFFFF)).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Dim(if (selectedRow) c.name else "No information", size = 13) }
            }
            for (p in visible) {
                val start = maxOf(p.start, windowStart)
                val end = minOf(p.stop, windowEnd)
                val sel = selectedRow && selTime >= p.start && selTime < p.stop
                val isNow = p.start <= nowSec && p.stop > nowSec
                val past = p.stop <= nowSec
                val catchup = past && c.hasCatchup && p.start > nowSec - c.e.catchupDays * 86400L
                val rem = reminders.any { it.channelKey == c.key && it.start == p.start }
                val rec = recordings.any { it.channelKey == c.key && it.start <= p.start && it.stop >= p.stop && it.status in setOf("scheduled", "recording") }
                Box(
                    Modifier.offset(x = dpPerSec * (start - windowStart).toFloat()).width((dpPerSec * (end - start).toFloat() - 2.dp).coerceAtLeast(2.dp))
                        .fillMaxHeight().clip(RoundedCornerShape(5.dp))
                        .background(
                            when {
                                sel -> ui.focus
                                isNow && s.highlightCurrentPrograms -> ui.accent.copy(alpha = 0.22f)
                                else -> Color(0x1CFFFFFF)
                            }
                        ).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (rec) Label("● ", size = 12, color = Color(0xFFE53935))
                            if (rem) Label("⏰ ", size = 12)
                            if (catchup && s.showCatchupIcon) Label("⟲ ", size = 12, color = if (sel) ui.text else ui.dim)
                            Label(p.title, size = 14, color = if (past && s.dimPastPrograms && !sel) ui.dim else ui.text, maxLines = if (s.twoLineProgramTitles) 2 else 1)
                        }
                        if (sel || !s.twoLineProgramTitles) Dim("${hm(p.start)} – ${hm(p.stop)}", size = 11)
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------------- menus

@Composable
fun SideMenu(vm: MainViewModel) {
    val ui = LocalUi.current
    val status by vm.status.collectAsState()
    val first = remember { FocusRequester() }
    var confirmExit by remember { mutableStateOf(false) }
    val items = listOf<Pair<String, () -> Unit>>(
        "TV channels" to { vm.overlay.value = Overlay.CHANNELS },
        // Opens the groups overview on Favorites: favorite channels first, then the favorite groups.
        "Favorites" to { vm.setGroup(GroupRef.FAVORITES); vm.overlay.value = Overlay.GROUPS },
        "TV guide" to { vm.open(Screen.Guide) },
        "Movies" to { vm.open(Screen.Vod("movie")) },
        "Shows" to { vm.open(Screen.Vod("series")) },
        "Search" to { vm.open(Screen.Search) },
        "Recordings" to { vm.open(Screen.Recordings) },
        "Reminders" to { vm.open(Screen.Reminders) },
        "Multiview" to { vm.open(Screen.Multiview) },
        "Settings" to { vm.withPin("Enter PIN", vm.settingsNeedPin()) { vm.open(Screen.Settings()) } },
        "Exit" to { if (vm.settings.confirmExit) confirmExit = true else vm.effects.tryEmit(Effect.Finish) },
    )
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(ui.panelStrong, ui.panel, Color.Transparent)))) {
        Column(Modifier.width(320.dp).fillMaxHeight().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("CasTV", Modifier.weight(1f), size = 24, bold = true, color = ui.accent)
                Clock(false)
            }
            if (status.isNotEmpty()) Dim(status, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            // Scrolls with the focus, so the last items (Exit) stay reachable on any screen size.
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(items) { i, (label, run) ->
                    FocusRow(focusRequester = if (i == 0) first else null, onClick = run) { Label(label, size = 17) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler { vm.overlay.value = Overlay.NONE }
    if (confirmExit) ConfirmDialog("Exit CasTV?", "", "Exit", onDismiss = { confirmExit = false }) { vm.effects.tryEmit(Effect.Finish) }
}

@Composable
fun ChannelMenu(vm: MainViewModel, c: Channel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sub by remember { mutableStateOf<String?>(null) }
    val catalog = vm.catalog.value
    val customGroups = catalog.groups.filter { it.ref.kind == GroupKind.CUSTOM }
    when (sub) {
        null -> ActionsDialog(c.name, buildList<Pair<String, () -> Unit>> {
            add("Watch" to { vm.play(c) })
            add((if (c.favorite) "Remove from Favorites" else "Add to Favorites") to { vm.toggleFavorite(c) })
            add("Add to a group…" to { sub = "group" })
            add("Rename" to { sub = "rename" })
            add("Choose guide channel…" to { sub = "epg" })
            add((if (vm.activeRecording(c) != null) "Stop recording" else "Record now") to { vm.toggleRecording(c) })
            add("Open in external player" to { scope.launch { vm.effects.emit(Effect.External(vm.resolver.live(c).url, c.name)) } })
            add("Hide channel" to { scope.launch { vm.repo.setHidden(c, true); vm.say("Hidden. Show it again in Settings › Playlists › Manage groups.") } })
            add((if (c.pref?.blocked == true) "Unblock channel" else "Block channel") to {
                if (vm.settings.pinHash.isEmpty()) vm.say("Set a PIN first in Settings › Parental controls")
                else vm.withPin("Enter PIN", true) { scope.launch { vm.repo.setBlocked(c, c.pref?.blocked != true) } }
            })
            add("Manage favorites" to { vm.open(Screen.Manage("favorites", GroupRef.FAVORITES.id)) })
            add("Reorder channels in this group" to { vm.open(Screen.Manage("reorder", vm.group.value.id)) })
            if (c.pref?.customName?.isNotEmpty() == true) add("Restore original name" to { scope.launch { vm.repo.rename(c, "") } })
        }, onDismiss = { if (sub == null) onDismiss() })
        "rename" -> TextInputDialog("Rename channel", c.name, onDismiss = { onDismiss() }) { n -> scope.launch { vm.repo.rename(c, n) } }
        "group" -> ActionsDialog("Add ${c.name} to", customGroups.map { g -> g.title to { scope.launch { vm.repo.addToCustomGroup(g.ref.customId, listOf(c)); vm.say("Added to ${g.title}") }; Unit } } +
            listOf("New group…" to { sub = "newgroup" }), onDismiss = { if (sub == "group") onDismiss() })
        "newgroup" -> TextInputDialog("Group name", "", onDismiss = { onDismiss() }) { n -> if (n.isNotEmpty()) scope.launch { vm.repo.createCustomGroup(n, listOf(c)); vm.say("Group created") } }
        "epg" -> EpgPickDialog(vm, c, onDismiss)
    }
}

@Composable
fun EpgPickDialog(vm: MainViewModel, c: Channel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val list by produceState<List<app.castv.data.EpgChannelEntity>?>(null) { value = vm.repo.epgChannelsForPlaylist(c.playlistId) }
    val l = list
    if (l == null) TvDialog("Guide channels", onDismiss) { Dim("Loading…") }
    else ChoiceDialog(
        "Guide for ${c.name}",
        listOf("" to "Automatic") + l.map { "${it.sourceId}|${it.xmltvId}" to "${it.displayName}  (${it.xmltvId})" },
        c.pref?.epgOverride.orEmpty(), onDismiss,
    ) { v ->
        scope.launch {
            if (v.isEmpty()) vm.repo.setEpgOverride(c, 0, "") else vm.repo.setEpgOverride(c, v.substringBefore('|').toLong(), v.substringAfter('|'))
            vm.refreshNowNext()
        }
        onDismiss()
    }
}

@Composable
fun GroupMenu(vm: MainViewModel, g: Group, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sub by remember { mutableStateOf<String?>(null) }
    val real = g.ref.kind == GroupKind.GROUP
    val custom = g.ref.kind == GroupKind.CUSTOM
    val isFavChannels = g.ref.kind == GroupKind.FAVORITES
    val renamed = g.title != g.originalTitle
    when (sub) {
        null -> ActionsDialog(g.title, buildList<Pair<String, () -> Unit>> {
            add("Open" to { vm.setGroup(g.ref) })
            if (!isFavChannels) {
                add((if (g.favorite) "Remove from favorite groups" else "Add to favorite groups") to {
                    val on = vm.repo.toggleFavoriteGroup(g.ref)
                    vm.say(if (on) "${g.title} is now a favorite group" else "${g.title} removed from favorite groups")
                })
                if (g.favorite) {
                    add("Move up in favorites" to { vm.repo.moveFavoriteGroup(g.ref, -1) })
                    add("Move down in favorites" to { vm.repo.moveFavoriteGroup(g.ref, 1) })
                }
                add("Add all its channels to Favorites" to {
                    scope.launch { val n = vm.repo.addGroupToFavorites(g.ref); vm.say(if (n == 0) "All channels are already favorites" else "$n channels added to Favorites") }
                })
            }
            add("Rename" to { sub = "rename" })
            if (renamed && !real) add("Restore name \"${g.originalTitle}\"" to { scope.launch { vm.repo.renameAnyGroup(g.ref, "") } })
            if (real) {
                add("Sort channels…" to { sub = "sort" })
                add("Hide group" to { scope.launch { vm.repo.setGroupHidden(g.ref, true) } })
                add((if (g.blocked) "Unblock group" else "Block group") to {
                    if (vm.settings.pinHash.isEmpty()) vm.say("Set a PIN first in Settings › Parental controls")
                    else vm.withPin("Enter PIN", true) { scope.launch { vm.repo.setGroupBlocked(g.ref, !g.blocked) } }
                })
            }
            if (custom) add("Delete group" to { scope.launch { vm.repo.deleteCustomGroup(g.ref.customId); vm.setGroup(GroupRef.ALL) } })
            if (g.ref.kind == GroupKind.HISTORY) add("Clear recent channels" to { scope.launch { vm.repo.clearHistory() } })
            add("Reorder channels" to { vm.open(Screen.Manage("reorder", g.ref.id)) })
            add("Manage favorites" to { vm.open(Screen.Manage("favorites", g.ref.id)) })
            add("Manage visibility" to { vm.open(Screen.Manage("hidden", g.ref.id)) })
            add("Manage blocking" to { vm.open(Screen.Manage("blocked", g.ref.id)) })
            add("Copy channels to a group" to { vm.open(Screen.Manage("copy", g.ref.id)) })
            add("Reset positions" to { scope.launch { vm.repo.resetPositions(vm.catalog.value.channelsIn(g.ref)); vm.say("Positions reset") } })
            add("Create group" to { sub = "create" })
        }, onDismiss = { if (sub == null) onDismiss() })
        "rename" -> TextInputDialog("Rename group", g.title, "Leave empty to restore \"${g.originalTitle}\"", onDismiss = onDismiss) { n ->
            scope.launch { if (custom && n.isNotEmpty()) vm.repo.renameCustomGroup(g.ref.customId, n) else vm.repo.renameAnyGroup(g.ref, n) }
        }
        "create" -> TextInputDialog("New group", "", onDismiss = onDismiss) { n -> if (n.isNotEmpty()) scope.launch { vm.repo.createCustomGroup(n) } }
        "sort" -> ChoiceDialog("Sort channels", sortOptions, "", onDismiss) { v -> scope.launch { vm.repo.setGroupSort(g.ref, v) }; onDismiss() }
    }
}

val sortOptions = listOf("" to "Use global setting", "playlist" to "Playlist order", "name" to "Name", "number" to "Number", "watch" to "Most watched", "added" to "Recently added", "manual" to "Manual")

@Composable
fun ProgrammeMenu(vm: MainViewModel, c: Channel, p: ProgrammeEntity, onDismiss: () -> Unit, onDetails: () -> Unit, onPlayed: () -> Unit) {
    val now = System.currentTimeMillis() / 1000
    val reminder = vm.reminderFor(c, p)
    val rec = vm.recordingFor(c, p)
    val inList = vm.repo.myList.value.any { it.key == "p|${c.key}|${p.start}" }
    val scope = rememberCoroutineScope()
    ActionsDialog(p.title, buildList<Pair<String, () -> Unit>> {
        if (p.start <= now) add("Watch" to { vm.playProgramme(c, p); onPlayed() })
        if (p.start <= now && c.hasCatchup) add("Watch from start" to { vm.playProgramme(c, p, fromStart = true); onPlayed() })
        if (p.start > now) add((if (reminder != null) "Delete reminder" else "Remind me") to { if (reminder != null) vm.deleteReminder(reminder.id) else vm.addReminder(c, p) })
        if (p.stop > now) add((if (rec != null) "Cancel recording" else "Record") to {
            if (rec != null) vm.cancelRecording(rec, delete = rec.status == "scheduled")
            else if (p.start <= now) vm.toggleRecording(c)
            else vm.scheduleRecording(c, p.title, p.start, p.stop)
        })
        add((if (inList) "Remove from My list" else "Watch later (My list)") to {
            scope.launch { vm.repo.toggleMyList(c.playlistId, "p|${c.key}|${p.start}", "program", "${p.title} · ${c.name} ${hm(p.start)}", c.logo) }
        })
        add("Program description" to onDetails)
        add("Watch channel" to { vm.play(c); onPlayed() })
    }, onDismiss)
}

@Composable
fun ProgrammeDetails(vm: MainViewModel, c: Channel, p: ProgrammeEntity, onDismiss: () -> Unit) {
    TvDialog(p.title, onDismiss, width = 720.dp) {
        Dim("${c.name}  ·  ${dayLabel(p.start)} ${hm(p.start)} – ${hm(p.stop)}  ·  ${(p.stop - p.start) / 60} min", size = 15)
        listOf(p.subTitle, p.episode, p.category, p.year, p.rating.let { if (it.isNotEmpty()) "Rating $it" else "" }).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { Dim(it.joinToString("  ·  "), size = 14) }
        Label(p.description.ifEmpty { "No description" }, size = 15, maxLines = 14)
        val first = remember { FocusRequester() }
        FocusRow(focusRequester = first, onClick = onDismiss) { Label("Close") }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
    @Suppress("UNUSED_EXPRESSION") vm
}
