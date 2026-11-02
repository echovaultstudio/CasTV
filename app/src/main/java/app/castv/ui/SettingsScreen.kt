package app.castv.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.castv.BuildConfig
import app.castv.CasApp
import app.castv.core.RemoteAction
import app.castv.core.RemoteButton
import app.castv.core.StreamUrls
import app.castv.data.EpgSourceEntity
import app.castv.data.GroupRef
import app.castv.data.GroupKind
import app.castv.data.PlaylistEntity
import app.castv.data.PlaylistEpgEntity
import app.castv.data.PlaylistType
import app.castv.system.WakeService
import coil3.SingletonImageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Files and folders picked with the system picker come back here: (request, uri). */
val pickResult = MutableStateFlow<Pair<String, Uri>?>(null)

sealed class Item(val title: String) {
    class Header(title: String) : Item(title)
    class Toggle(title: String, val summary: String = "", val get: () -> Boolean, val set: (Boolean) -> Unit) : Item(title)
    class Choice<T>(title: String, val options: List<Pair<T, String>>, val get: () -> T, val set: (T) -> Unit) : Item(title)
    class Action(title: String, val summary: () -> String = { "" }, val run: () -> Unit) : Item(title)
    class Text(title: String, val hint: String = "", val keyboard: KeyboardType = KeyboardType.Text, val password: Boolean = false, val get: () -> String, val set: (String) -> Unit) : Item(title)
    class Number(title: String, val range: IntRange, val step: Int = 1, val suffix: String = "", val get: () -> Int, val set: (Int) -> Unit) : Item(title)
}

@Composable
fun SettingsList(title: String, items: List<Item>, vm: MainViewModel, subtitle: String = "") {
    val ui = LocalUi.current
    vm.settingsVersion.collectAsState().value
    var open by remember { mutableStateOf<Item?>(null) }
    val first = remember { FocusRequester() }
    Row(Modifier.fillMaxSize().background(Color(0xFF0B0D11))) {
        Column(Modifier.width(320.dp).fillMaxHeight().background(ui.panelStrong).padding(28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Dim("Settings")
            Label(title, size = 26, bold = true, color = ui.accent, maxLines = 2)
            if (subtitle.isNotEmpty()) Dim(subtitle, maxLines = 6)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
            val firstFocusable = items.indexOfFirst { it !is Item.Header }
            itemsIndexed(items) { i, item ->
                if (item is Item.Header) Label(item.title, Modifier.padding(start = 14.dp, top = 18.dp, bottom = 6.dp), size = 15, bold = true, color = ui.accent)
                else FocusRow(focusRequester = if (i == firstFocusable) first else null, onClick = {
                    when (item) {
                        is Item.Toggle -> item.set(!item.get())
                        is Item.Action -> item.run()
                        else -> open = item
                    }
                }) {
                    Column(Modifier.weight(1f)) {
                        Label(item.title, maxLines = 2)
                        val sub = when (item) {
                            is Item.Toggle -> item.summary
                            is Item.Action -> item.summary()
                            else -> ""
                        }
                        if (sub.isNotEmpty()) Dim(sub, maxLines = 3)
                    }
                    when (item) {
                        is Item.Toggle -> Switch(item.get())
                        is Item.Choice<*> -> Dim(choiceLabel(item))
                        is Item.Text -> Dim(if (item.password) "••••" else item.get().ifEmpty { "Not set" }.take(40))
                        is Item.Number -> Dim("${item.get()}${item.suffix}")
                        is Item.Action -> Dim("›")
                        else -> Unit
                    }
                }
            }
        }
    }
    LaunchedEffect(title) { runCatching { first.requestFocus() } }
    BackHandler { vm.back() }

    when (val item = open) {
        is Item.Choice<*> -> {
            @Suppress("UNCHECKED_CAST")
            ChoiceSetting(item as Item.Choice<Any?>) { open = null }
        }
        is Item.Text -> TextInputDialog(item.title, item.get(), item.hint, item.keyboard, item.password, onDismiss = { open = null }) { item.set(it) }
        is Item.Number -> NumberDialog(item.title, item.get(), item.range, item.step, item.suffix, onDismiss = { open = null }) { item.set(it); open = null }
        else -> Unit
    }
}

private fun choiceLabel(item: Item.Choice<*>): String {
    val v = item.get()
    return item.options.firstOrNull { it.first == v }?.second ?: v.toString()
}

@Composable
private fun <T> ChoiceSetting(item: Item.Choice<T>, onDismiss: () -> Unit) {
    ChoiceDialog(item.title, item.options, item.get(), onDismiss) { item.set(it); onDismiss() }
}

@Composable
fun SettingsScreen(vm: MainViewModel, page: String, arg: Long) {
    when (page) {
        "playlists" -> PlaylistsSettings(vm)
        "playlist" -> PlaylistSettings(vm, arg)
        "groups" -> GroupsSettings(vm, arg)
        "epg" -> EpgSettings(vm)
        "epgsource" -> EpgSourceSettings(vm, arg)
        "appearance" -> AppearanceSettings(vm)
        "playback" -> PlaybackSettings(vm)
        "remote" -> RemoteSettings(vm)
        "parental" -> ParentalSettings(vm)
        "recordings" -> RecordingSettings(vm)
        "search" -> SearchSettings(vm)
        "general" -> GeneralSettings(vm)
        "backup" -> BackupSettings(vm)
        "about" -> AboutSettings(vm)
        else -> RootSettings(vm)
    }
}

@Composable
private fun RootSettings(vm: MainViewModel) {
    val open: (String) -> () -> Unit = { p -> { vm.open(Screen.Settings(p)) } }
    SettingsList("Settings", listOf(
        Item.Action("Playlists", { "Add, update and organise playlists" }, open("playlists")),
        Item.Action("TV guide (EPG)", { "Guide sources and updates" }, open("epg")),
        Item.Action("Appearance", { "Colors, panels, logos, clock" }, open("appearance")),
        Item.Action("Playback", { "Decoders, buffer, frame rate, seeking" }, open("playback")),
        Item.Action("Remote control", { "What each button does" }, open("remote")),
        Item.Action("Parental controls", { "PIN and blocked channels" }, open("parental")),
        Item.Action("Reminders and recordings", run = open("recordings")),
        Item.Action("Search", run = open("search")),
        Item.Action("General", { "Start-up, sorting, history" }, open("general")),
        Item.Action("Backup and restore", run = open("backup")),
        Item.Action("About", run = open("about")),
    ), vm)
}

private val df = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

@Composable
private fun PlaylistsSettings(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val playlists by vm.repo.db.playlists().observeAll().collectAsState(emptyList())
    val s = vm.settings
    SettingsList("Playlists", buildList<Item> {
        add(Item.Action("Add playlist") { vm.open(Screen.Wizard()) })
        add(Item.Action("Update all playlists", { "Downloads every playlist again" }) {
            scope.launch {
                vm.say("Updating playlists…")
                val errors = vm.repo.updateAllPlaylists()
                vm.say(if (errors.isEmpty()) "Playlists updated" else "Some playlists failed: ${errors.joinToString()}")
            }
        })
        add(Item.Header("Your playlists"))
        playlists.forEach { p ->
            add(Item.Action(p.name, { playlistSummary(p) }) { vm.open(Screen.Settings("playlist", p.id)) })
        }
        add(Item.Header("Options"))
        add(Item.Choice("Playlists order", listOf("manual" to "Manual", "name" to "Name"), { s.playlistSort }, { s.playlistSort = it }))
        add(Item.Toggle("Update on app start", "Only playlists older than the update interval", { s.playlistUpdateOnStart }, { s.playlistUpdateOnStart = it }))
        add(Item.Choice("Update interval", listOf(0 to "Never", 6 to "6 hours", 12 to "12 hours", 24 to "1 day", 48 to "2 days", 168 to "1 week"), { s.playlistUpdateHours }, { s.playlistUpdateHours = it }))
        add(Item.Toggle("A category for each playlist", "Lists every playlist by name above its groups", { s.showAllPlaylistsCategory }, { s.showAllPlaylistsCategory = it }))
        add(Item.Toggle("Group channels by playlist in All channels", "", { s.groupByPlaylistInAll }, { s.groupByPlaylistInAll = it }))
    }, vm, "Channels, movies and shows come from your own playlists.")
}

private fun playlistSummary(p: PlaylistEntity): String = buildString {
    append(when (p.type) { PlaylistType.XTREAM -> "Xtream Codes"; PlaylistType.STALKER -> "Stalker portal"; else -> "M3U" })
    if (!p.enabled) append(" · off")
    append(" · ${p.channelCount} channels")
    if (p.movieCount > 0) append(" · ${p.movieCount} movies")
    if (p.seriesCount > 0) append(" · ${p.seriesCount} shows")
    if (p.lastUpdated > 0) append(" · updated ${df.format(Date(p.lastUpdated))}")
    if (p.lastError.isNotEmpty()) append(" · error: ${p.lastError}")
}

@Composable
private fun PlaylistSettings(vm: MainViewModel, id: Long) {
    val scope = rememberCoroutineScope()
    val playlists by vm.repo.db.playlists().observeAll().collectAsState(emptyList())
    val sources by vm.repo.db.epg().observeSources().collectAsState(emptyList())
    val assignments by vm.repo.db.epg().observeAssignments().collectAsState(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    val p = playlists.firstOrNull { it.id == id } ?: return
    fun save(f: (PlaylistEntity) -> PlaylistEntity) { scope.launch { vm.repo.savePlaylist(f(p)) } }
    val mine = assignments.filter { it.playlistId == id }
    SettingsList(p.name, buildList<Item> {
        add(Item.Action("Update now", { playlistSummary(p) }) {
            scope.launch { runCatching { vm.repo.updatePlaylist(id) }.onSuccess { vm.say("Playlist updated") }.onFailure { vm.say("Update failed: ${it.message}") } }
        })
        add(Item.Toggle("Enabled", "", { p.enabled }, { v -> save { it.copy(enabled = v) } }))
        if (playlists.size > 1) {
            val pos = playlists.indexOfFirst { it.id == id }
            add(Item.Action("Move up", { "Position ${pos + 1} of ${playlists.size}" }) {
                scope.launch { vm.settings.playlistSort = "manual"; vm.repo.movePlaylist(id, -1) }
            })
            add(Item.Action("Move down") { scope.launch { vm.settings.playlistSort = "manual"; vm.repo.movePlaylist(id, 1) } })
        }
        add(Item.Text("Name", get = { p.name }, set = { v -> save { it.copy(name = v) } }))
        when (p.type) {
            PlaylistType.XTREAM -> {
                add(Item.Text("Server", get = { p.url }, set = { v -> save { it.copy(url = v) } }))
                add(Item.Text("Username", get = { p.username }, set = { v -> save { it.copy(username = v) } }))
                add(Item.Text("Password", password = true, get = { p.password }, set = { v -> save { it.copy(password = v) } }))
                add(Item.Choice("Stream format", listOf("ts" to "MPEG-TS", "m3u8" to "HLS"), { p.outputFormat }, { v -> save { it.copy(outputFormat = v) } }))
                if (p.expiresAt > 0) add(Item.Action("Account", { "Expires ${SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(p.expiresAt * 1000))} · ${p.maxConnections} connections" }) {})
            }
            PlaylistType.STALKER -> {
                add(Item.Text("Portal", get = { p.url }, set = { v -> save { it.copy(url = v) } }))
                add(Item.Text("MAC address", get = { p.mac }, set = { v -> save { it.copy(mac = v.uppercase()) } }))
                add(Item.Text("Serial number", "Optional", get = { p.serial }, set = { v -> save { it.copy(serial = v) } }))
                add(Item.Text("Device ID", "Optional", get = { p.deviceId }, set = { v -> save { it.copy(deviceId = v) } }))
            }
            else -> add(Item.Text("Playlist address", "You can edit the address if it keeps the same format", KeyboardType.Uri, get = { p.url }, set = { v -> save { it.copy(url = v) } }))
        }
        add(Item.Text("User-Agent", "Leave empty for the default", get = { p.userAgent }, set = { v -> save { it.copy(userAgent = v) } }))
        if (p.type != PlaylistType.STALKER) {
            add(Item.Toggle("Load movies and shows", "", { p.includeVod }, { v -> save { it.copy(includeVod = v) } }))
            add(Item.Toggle("Movies and shows only (no TV channels)", "", { p.vodOnly }, { v -> save { it.copy(vodOnly = v) } }))
        }
        add(Item.Choice("Update interval", listOf(-1 to "Use global setting", 0 to "Never", 6 to "6 hours", 12 to "12 hours", 24 to "1 day", 168 to "1 week"), { p.updateIntervalHours }, { v -> save { it.copy(updateIntervalHours = v) } }))
        add(Item.Header("Guide"))
        sources.forEach { src ->
            val on = mine.any { it.sourceId == src.id }
            add(Item.Toggle(src.name, if (on) "Used by this playlist (priority ${mine.indexOfFirst { it.sourceId == src.id } + 1})" else "Not used", { on }, { v ->
                scope.launch {
                    if (v) vm.repo.db.epg().assign(PlaylistEpgEntity(id, src.id, mine.size)) else vm.repo.db.epg().unassign(id, src.id)
                    vm.repo.resolveEpg(id)
                }
            }))
        }
        if (mine.size > 1) add(Item.Action("Change guide priority", { "Move the last guide to the top" }) {
            scope.launch {
                val order = listOf(mine.last()) + mine.dropLast(1)
                order.forEachIndexed { i, a -> vm.repo.db.epg().assign(a.copy(priority = i)) }
                vm.repo.resolveEpg(id)
            }
        })
        add(Item.Header("Channels"))
        add(Item.Action("Manage groups", { "Hide, show, rename and reorder groups" }) { vm.open(Screen.Settings("groups", id)) })
        add(Item.Choice("Groups order", listOf("" to "Use global setting", "playlist" to "Playlist order", "name" to "Name", "manual" to "Manual"), { p.groupSort }, { v -> save { it.copy(groupSort = v) } }))
        add(Item.Choice("Logos", listOf("" to "Use global setting", "playlist" to "Prefer playlist logos", "epg" to "Prefer guide logos", "folder" to "Prefer logos folder"), { p.logoPriority }, { v -> save { it.copy(logoPriority = v) } }))
        add(Item.Action("Delete playlist") { confirmDelete = true })
    }, vm)
    if (confirmDelete) ConfirmDialog("Delete ${p.name}?", "All channels from this playlist will be removed.", "Delete", onDismiss = { confirmDelete = false }) {
        scope.launch { vm.repo.deletePlaylist(id); vm.say("Playlist deleted"); vm.back() }
    }
}

@Composable
private fun GroupsSettings(vm: MainViewModel, playlistId: Long) {
    val scope = rememberCoroutineScope()
    val catalog by vm.catalog.collectAsState()
    val visible = catalog.groups.filter { it.ref.kind == GroupKind.GROUP && it.ref.playlistId == playlistId }
    val hidden = catalog.hiddenGroups.filter { it.ref.playlistId == playlistId }
    var rename by remember { mutableStateOf<GroupRef?>(null) }
    SettingsList("Groups", buildList<Item> {
        add(Item.Action("Show all groups") { scope.launch { hidden.forEach { vm.repo.setGroupHidden(it.ref, false) } } })
        add(Item.Action("Hide all groups") { scope.launch { visible.forEach { vm.repo.setGroupHidden(it.ref, true) } } })
        add(Item.Action("Reorder: move the last group to the top") {
            scope.launch {
                val names = visible.map { it.ref.name }
                if (names.size > 1) vm.repo.setGroupsOrder(playlistId, listOf(names.last()) + names.dropLast(1) + hidden.map { it.ref.name })
                vm.repo.db.playlists().get(playlistId)?.let { vm.repo.savePlaylist(it.copy(groupSort = "manual")) }
            }
        })
        add(Item.Header("Groups (OK: show or hide)"))
        (visible + hidden).sortedBy { it.title.lowercase() }.forEach { g ->
            add(Item.Toggle(g.title, "${g.count} channels${if (g.isNew) " · new" else ""}", { !g.hidden }, { v -> scope.launch { vm.repo.setGroupHidden(g.ref, !v) } }))
        }
        add(Item.Header("Rename"))
        visible.forEach { g -> add(Item.Action("Rename ${g.title}") { rename = g.ref }) }
    }, vm, "Hidden groups keep their channels out of every list. Up and Down on a group in the channel list also offer these options.")
    rename?.let { ref -> TextInputDialog("Group name", vm.groupTitle(ref), onDismiss = { rename = null }) { n -> scope.launch { vm.repo.renameGroup(ref, n) } } }
    LaunchedEffect(Unit) { vm.repo.markGroupsSeen() }
}

@Composable
private fun EpgSettings(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val s = vm.settings
    val sources by vm.repo.db.epg().observeSources().collectAsState(emptyList())
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    SettingsList("TV guide", buildList<Item> {
        add(Item.Action("Add guide source", { "XMLTV address (.xml or .xml.gz)" }) { adding = true })
        add(Item.Action("Update guide now") {
            scope.launch {
                vm.say("Updating the guide…")
                val errors = vm.repo.updateAllEpg()
                vm.say(if (errors.isEmpty()) "Guide updated" else "Guide update failed: ${errors.joinToString()}")
                vm.refreshNowNext()
            }
        })
        add(Item.Header("Sources"))
        sources.forEach { src ->
            add(Item.Action(src.name, {
                buildString {
                    append(if (src.enabled) "" else "off · ")
                    append("${src.channelCount} channels, ${src.programmeCount} programs")
                    if (src.lastUpdated > 0) append(" · ${df.format(Date(src.lastUpdated))}")
                    if (src.lastError.isNotEmpty()) append(" · error: ${src.lastError}")
                }
            }) { vm.open(Screen.Settings("epgsource", src.id)) })
        }
        add(Item.Header("Options"))
        add(Item.Toggle("Update on app start", "", { s.epgUpdateOnStart }, { s.epgUpdateOnStart = it }))
        add(Item.Toggle("Update when a playlist changes", "", { s.epgUpdateOnPlaylistChange }, { s.epgUpdateOnPlaylistChange = it }))
        add(Item.Choice("Update interval", listOf(0 to "Never", 6 to "6 hours", 12 to "12 hours", 24 to "1 day", 48 to "2 days", 72 to "3 days"), { s.epgUpdateHours }, { s.epgUpdateHours = it }))
        add(Item.Number("Days of past programs to keep", 0..14, suffix = " days", get = { s.epgPastDays }, set = { s.epgPastDays = it }))
        add(Item.Number("Days of future programs", 1..14, suffix = " days", get = { s.epgFutureDays }, set = { s.epgFutureDays = it }))
        add(Item.Toggle("Store program descriptions", "Turn off to save space on small devices", { s.epgStoreDescriptions }, { s.epgStoreDescriptions = it }))
        add(Item.Choice("Guide time offset", (-12..12).flatMap { h -> listOf(h * 3600L, h * 3600L + if (h < 0) -1800L else 1800L) }.distinct().sorted().map { it to StreamUrls.formatHourMinutes(it) },
            { s.epgOffsetSec }, { s.epgOffsetSec = it }))
        add(Item.Action("Clear guide data") { confirmClear = true })
    }, vm, "Guide sources must be turned on for a playlist (Settings › Playlists › your playlist).")
    if (adding) {
        var url by remember { mutableStateOf<String?>(null) }
        val u = url
        if (u == null) TextInputDialog("Guide address", "", "XMLTV format only", KeyboardType.Uri, onDismiss = { adding = false }) { url = it }
        else TextInputDialog("Guide name", u.substringAfter("://").substringBefore('/'), onDismiss = { adding = false }) { name ->
            scope.launch {
                runCatching { val id = vm.repo.addEpgSource(name, u); vm.say("Updating the new guide…"); vm.repo.updateEpgSource(id) }
                    .onSuccess { vm.say("Guide added") }.onFailure { vm.say(it.message ?: "Failed to add the guide") }
            }
            adding = false
        }
    }
    if (confirmClear) ConfirmDialog("Clear guide data?", "Programs are downloaded again on the next update.", "Clear", { confirmClear = false }) { scope.launch { vm.repo.clearEpg(); vm.say("Guide cleared") } }
}

@Composable
private fun EpgSourceSettings(vm: MainViewModel, id: Long) {
    val scope = rememberCoroutineScope()
    val sources by vm.repo.db.epg().observeSources().collectAsState(emptyList())
    val src = sources.firstOrNull { it.id == id } ?: return
    var confirm by remember { mutableStateOf(false) }
    fun save(f: (EpgSourceEntity) -> EpgSourceEntity) { scope.launch { vm.repo.db.epg().upsertSource(f(src)) } }
    SettingsList(src.name, listOf(
        Item.Action("Update now", { "${src.channelCount} channels, ${src.programmeCount} programs" }) {
            scope.launch { runCatching { vm.repo.updateEpgSource(id) }.onSuccess { vm.say("Guide updated") }.onFailure { vm.say("Failed: ${it.message}") } }
        },
        Item.Toggle("Enabled", "", { src.enabled }, { v -> save { it.copy(enabled = v) } }),
        Item.Text("Name", get = { src.name }, set = { v -> save { it.copy(name = v) } }),
        Item.Text("Address", keyboard = KeyboardType.Uri, get = { src.url }, set = { v -> save { it.copy(url = v) } }),
        Item.Choice("Time offset for this source", (-24..24).map { it * 1800L }.map { it to StreamUrls.formatHourMinutes(it) }, { src.shiftSeconds }, { v -> save { it.copy(shiftSeconds = v) } }),
        Item.Action("Delete source") { confirm = true },
    ), vm)
    if (confirm) ConfirmDialog("Delete ${src.name}?", "The guide will no longer update from this source.", "Delete", { confirm = false }) {
        scope.launch { vm.repo.deleteEpgSource(id); vm.back() }
    }
}

@Composable
private fun AppearanceSettings(vm: MainViewModel) {
    val s = vm.settings
    val ctx = LocalContext.current
    SettingsList("Appearance", listOf(
        Item.Choice("Accent color", accents.keys.map { it to it.replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }, { s.accent }, { s.accent = it }),
        Item.Choice("Panel transparency", listOf(0, 10, 20, 30, 40, 50, 60).map { it to "$it%" }, { s.uiTransparency }, { s.uiTransparency = it }),
        Item.Choice("Text size", listOf("small" to "Small", "medium" to "Medium", "large" to "Large"), { s.fontSize }, { s.fontSize = it }),
        Item.Number("Panels hide after", 2..30, suffix = " s", get = { s.panelsTimeoutSec }, set = { s.panelsTimeoutSec = it }),
        Item.Header("Clock"),
        Item.Toggle("Show clock", "", { s.showClock }, { s.showClock = it }),
        Item.Choice("Clock position", listOf("top_right" to "Top right", "top_left" to "Top left", "bottom_right" to "Bottom right", "bottom_left" to "Bottom left"), { s.clockPosition }, { s.clockPosition = it }),
        Item.Choice("Clock size", listOf("small" to "Small", "medium" to "Medium", "large" to "Large"), { s.clockSize }, { s.clockSize = it }),
        Item.Toggle("Show date", "", { s.showDateOnClock }, { s.showDateOnClock = it }),
        Item.Header("Channels"),
        Item.Toggle("Channel numbers", "", { s.showChannelNumbers }, { s.showChannelNumbers = it }),
        Item.Toggle("Channel logos", "", { s.showChannelLogos }, { s.showChannelLogos = it }),
        Item.Toggle("Channel names", "", { s.showChannelNames }, { s.showChannelNames = it }),
        Item.Toggle("Two-line channel names", "", { s.twoLineChannelNames }, { s.twoLineChannelNames = it }),
        Item.Toggle("Current programs in the channel list", "", { s.showCurrentPrograms }, { s.showCurrentPrograms = it }),
        Item.Toggle("Highlight the playing channel", "", { s.highlightCurrentChannel }, { s.highlightCurrentChannel = it }),
        Item.Toggle("Catch-up icon", "", { s.showCatchupIcon }, { s.showCatchupIcon = it }),
        Item.Toggle("Show \"All channels\"", "", { s.showAllChannelsCategory }, { s.showAllChannelsCategory = it }),
        Item.Toggle("Show \"Favorites\"", "", { s.showFavoritesCategory }, { s.showFavoritesCategory = it }),
        Item.Toggle("Show \"Recent\"", "", { s.historyButton }, { s.historyButton = it }),
        Item.Header("TV guide"),
        Item.Number("Hours visible", 1..4, suffix = " h", get = { s.guideHoursVisible }, set = { s.guideHoursVisible = it }),
        Item.Toggle("Two-line program titles", "", { s.twoLineProgramTitles }, { s.twoLineProgramTitles = it }),
        Item.Toggle("Dim past programs", "", { s.dimPastPrograms }, { s.dimPastPrograms = it }),
        Item.Toggle("Highlight current programs", "", { s.highlightCurrentPrograms }, { s.highlightCurrentPrograms = it }),
        Item.Toggle("Current time line at full height", "", { s.currentTimeFullHeight }, { s.currentTimeFullHeight = it }),
        Item.Toggle("Stay in the guide when switching channels", "", { s.guideStayOnSwitch }, { s.guideStayOnSwitch = it }),
        Item.Toggle("Stay in the list when switching channels", "", { s.listStayOnSwitch }, { s.listStayOnSwitch = it }),
        Item.Header("Info panel"),
        Item.Toggle("Show when switching channels", "", { s.infoOnSwitch }, { s.infoOnSwitch = it }),
        Item.Toggle("Program description", "", { s.showProgramDescription }, { s.showProgramDescription = it }),
        Item.Toggle("Media properties", "Resolution, frame rate, codecs", { s.showMediaProperties }, { s.showMediaProperties = it }),
        Item.Toggle("Resolution instead of HD/FHD labels", "", { s.resolutionInsteadOfLabels }, { s.resolutionInsteadOfLabels = it }),
        Item.Toggle("Playlist and group name", "", { s.showPlaylistAndGroup }, { s.showPlaylistAndGroup = it }),
        Item.Header("Movies and shows"),
        Item.Choice("Cards", listOf("poster" to "Posters", "wide" to "Wide"), { s.cardStyle }, { s.cardStyle = it }),
        Item.Header("Logos"),
        Item.Choice("Logo source", listOf("playlist" to "Prefer playlist logos", "epg" to "Prefer guide logos", "folder" to "Prefer logos folder"), { s.logoPriority }, { s.logoPriority = it }),
        Item.Action("Logos folder", { s.logosFolder.ifEmpty { "Not set" } }) { vm.effects.tryEmit(Effect.Pick("logos_folder")) },
        Item.Toggle("Loose matching of logo file names", "", { s.inexactLogoMatching }, { s.inexactLogoMatching = it }),
        Item.Action("Clear logos cache") {
            SingletonImageLoader.get(ctx).let { it.memoryCache?.clear(); it.diskCache?.clear() }
            vm.say("Logos cache cleared")
        },
    ), vm)
}

@Composable
private fun PlaybackSettings(vm: MainViewModel) {
    val s = vm.settings
    val steps = listOf(5, 10, 15, 20, 30, 60, 120, 300, 600).map { it to if (it >= 60) "${it / 60} min" else "$it s" }
    SettingsList("Playback", listOf(
        Item.Choice("Buffer size", listOf("small" to "Small (fast switching)", "medium" to "Medium", "large" to "Large", "very_large" to "Very large (unstable connections)"), { s.bufferSize }, { s.bufferSize = it }),
        Item.Choice("Video decoder", listOf("hardware" to "Hardware", "software" to "Software"), { s.videoDecoder }, { s.videoDecoder = it }),
        Item.Choice("Audio decoder", listOf("hardware" to "Hardware", "software" to "Software (FFmpeg)"), { s.audioDecoder }, { s.audioDecoder = it }),
        Item.Toggle("Audio passthrough", "Send Dolby/DTS to the receiver", { s.audioPassthrough }, { s.audioPassthrough = it }),
        Item.Toggle("Prefer surround audio", "", { s.preferSurround }, { s.preferSurround = it }),
        Item.Text("Preferred audio language", "Two-letter code, e.g. en, fi, de", get = { s.preferredAudioLanguage }, set = { s.preferredAudioLanguage = it.lowercase() }),
        Item.Text("Preferred subtitle language", "Two-letter code", get = { s.preferredSubtitleLanguage }, set = { s.preferredSubtitleLanguage = it.lowercase() }),
        Item.Toggle("Subtitles on all channels when available", "", { s.subtitlesAllChannels }, { s.subtitlesAllChannels = it }),
        Item.Choice("Default display mode", listOf("fit" to "Normal", "fill" to "Full (stretch)", "zoom" to "Zoom (crop)", "reduced" to "Reduced"), { s.displayMode }, { s.displayMode = it }),
        Item.Choice("Default audio delay", (-2000..2000 step 50).map { it to "$it ms" }, { s.audioOffsetMs }, { s.audioOffsetMs = it }),
        Item.Header("Picture"),
        Item.Toggle("Auto frame rate", "Match the TV's refresh rate to the video. Not every device supports it.", { s.autoFrameRate }, { s.autoFrameRate = it }),
        Item.Toggle("Only for 50/60 fps video", "", { s.afrOnly5060 }, { s.afrOnly5060 = it }),
        Item.Toggle("Switch screen resolution too", "", { s.switchResolution }, { s.switchResolution = it }),
        Item.Toggle("Tunneled playback", "Smoother video on some TVs", { s.tunneled }, { s.tunneled = it }),
        Item.Toggle("Amlogic fix", "Try this on Amlogic boxes with stuttering video", { s.amlogicFix }, { s.amlogicFix = it }),
        Item.Toggle("Black screen when switching channels", "", { s.blackOnSwitch }, { s.blackOnSwitch = it }),
        Item.Header("Seeking"),
        Item.Choice("Rewind step", steps, { s.rwStepSec }, { s.rwStepSec = it }),
        Item.Choice("Fast-forward step", steps, { s.ffStepSec }, { s.ffStepSec = it }),
        Item.Choice("Left step (time bar)", steps, { s.timebarBackSec }, { s.timebarBackSec = it }),
        Item.Choice("Right step (time bar)", steps, { s.timebarFwdSec }, { s.timebarFwdSec = it }),
        Item.Toggle("Left/Right seek in catch-up", "", { s.seekLeftRightCatchup }, { s.seekLeftRightCatchup = it }),
        Item.Toggle("Up/Down seek 10 min in catch-up", "", { s.seekUpDownCatchup }, { s.seekUpDownCatchup = it }),
        Item.Toggle("Rewind/Fast-forward in catch-up", "", { s.seekRwFfCatchup }, { s.seekRwFfCatchup = it }),
        Item.Choice("Rewind live TV with catch-up", listOf("off" to "Off", "rw" to "With the Rewind button", "left" to "With Left", "down" to "With Down"), { s.rewindLiveWithCatchup }, { s.rewindLiveWithCatchup = it }),
        Item.Header("Other"),
        Item.Toggle("Autoplay next episode", "", { s.autoplayNextEpisode }, { s.autoplayNextEpisode = it }),
        Item.Toggle("Picture-in-picture on Home", "", { s.pipOnHome }, { s.pipOnHome = it }),
        Item.Choice("External player", listOf("off" to "Off", "tv" to "For TV", "vod" to "For movies and shows", "both" to "For everything"), { s.useExternalPlayer }, { s.useExternalPlayer = it }),
        Item.Number("Reconnect attempts", 0..20, get = { s.maxReconnects }, set = { s.maxReconnects = it }),
        Item.Text("UDP proxy", "address:port of a udpxy proxy for multicast channels", get = { s.udpProxy }, set = { s.udpProxy = it }),
        Item.Text("Default User-Agent", "Leave empty for CasTV's own", get = { s.defaultUserAgent }, set = { s.defaultUserAgent = it }),
    ), vm, "Changes to decoders and the buffer apply when you leave settings.")
}

@Composable
private fun RemoteSettings(vm: MainViewModel) {
    val s = vm.settings
    var confirm by remember { mutableStateOf(false) }
    SettingsList("Remote control", buildList<Item> {
        add(Item.Action("Restore defaults") { confirm = true })
        add(Item.Header("While watching"))
        RemoteButton.entries.forEach { b ->
            add(Item.Choice(b.label, RemoteAction.entries.map { it to it.label }, { s.remote(b) }, { s.setRemote(b, it) }))
        }
    }, vm, "Number buttons always switch channels. Back opens the menu; long Back can be set.")
    if (confirm) ConfirmDialog("Restore defaults?", "Your button changes will be lost.", "Restore", { confirm = false }) { s.resetRemote() }
}

@Composable
private fun ParentalSettings(vm: MainViewModel) {
    val s = vm.settings
    var setPin by remember { mutableStateOf(0) }
    var firstPin by remember { mutableStateOf("") }
    SettingsList("Parental controls", buildList<Item> {
        add(Item.Action(if (s.pinHash.isEmpty()) "Create PIN" else "Change PIN", { if (s.pinHash.isEmpty()) "Needed to block channels and groups" else "PIN is set" }) {
            if (s.pinHash.isEmpty()) setPin = 1 else vm.withPin("Enter current PIN", true) { setPin = 1 }
        })
        if (s.pinHash.isNotEmpty()) {
            add(Item.Action("Remove PIN") { vm.withPin("Enter current PIN", true) { s.setPin(""); vm.say("PIN removed") } })
            add(Item.Choice("Ask for the PIN for", listOf("blocked" to "Blocked channels and groups", "blocked_settings" to "Blocked channels, groups and settings"), { s.pinRequiredFor }, { s.pinRequiredFor = it }))
            add(Item.Choice("After unlocking", listOf("always" to "Ask every time", "app_session" to "Don't ask again until the app restarts", "10m" to "Don't ask for 10 min", "30m" to "Don't ask for 30 min", "1h" to "Don't ask for 1 hour", "2h" to "Don't ask for 2 hours"),
                { s.pinUnlockScope }, { s.pinUnlockScope = it }))
            add(Item.Action("Manage blocked channels") { vm.withPin("Enter PIN", true) { vm.open(Screen.Manage("blocked", GroupRef.ALL.id)) } })
        }
    }, vm, "Blocked channels and groups ask for the PIN before they play.")
    when (setPin) {
        1 -> PinDialog("Enter new PIN", { setPin = 0 }) { firstPin = it; setPin = 2 }
        2 -> PinDialog("Confirm PIN", { setPin = 0 }) { if (it == firstPin) { s.setPin(it); vm.say("PIN saved") } else vm.say("PINs don't match"); setPin = 0 }
    }
}

@Composable
private fun RecordingSettings(vm: MainViewModel) {
    val s = vm.settings
    SettingsList("Reminders and recordings", listOf(
        Item.Header("Reminders"),
        Item.Number("Remind before start", 0..30, suffix = " min", get = { s.remindBeforeMin }, set = { s.remindBeforeMin = it }),
        Item.Number("Reminder shows for", 5..120, 5, " s", get = { s.reminderPopupSec }, set = { s.reminderPopupSec = it }),
        Item.Choice("When the reminder times out", listOf("watch" to "Switch to the channel", "dismiss" to "Do nothing"), { s.reminderDefaultAction }, { s.reminderDefaultAction = it }),
        Item.Toggle("Wake the device for reminders", "", { s.reminderWakeUp }, { s.reminderWakeUp = it }),
        Item.Action("Allow exact alarms", { "Needed on Android 12+ for on-time reminders" }) { vm.effects.tryEmit(Effect.ExactAlarmPermission) },
        Item.Header("Recordings"),
        Item.Action("Recordings folder", { if (s.recordingsFolder.isEmpty()) "App storage (Movies/Recordings)" else Uri.parse(s.recordingsFolder).lastPathSegment.orEmpty() }) { vm.effects.tryEmit(Effect.Pick("recordings_folder")) },
        Item.Action("Use app storage", { "Forget the chosen folder" }) { s.recordingsFolder = "" },
        Item.Number("Start recording early", 0..30, suffix = " min", get = { s.recordBeforeMin }, set = { s.recordBeforeMin = it }),
        Item.Number("Keep recording after the end", 0..60, suffix = " min", get = { s.recordAfterMin }, set = { s.recordAfterMin = it }),
    ), vm)
}

@Composable
private fun SearchSettings(vm: MainViewModel) {
    val s = vm.settings
    SettingsList("Search", listOf(
        Item.Toggle("Start with voice search", "", { s.searchPreferVoice }, { s.searchPreferVoice = it }),
        Item.Toggle("Show search history", "", { s.searchShowHistory }, { s.searchShowHistory = it }),
        Item.Toggle("Favorite channels first", "", { s.searchFavoritesFirst }, { s.searchFavoritesFirst = it }),
        Item.Toggle("Show past programs without catch-up", "", { s.searchShowPastWithoutCatchup }, { s.searchShowPastWithoutCatchup = it }),
        Item.Toggle("Stay on search when switching channels", "", { s.searchStayOnSwitch }, { s.searchStayOnSwitch = it }),
        Item.Action("Clear search history") { s.searchHistory = ""; vm.say("Search history cleared") },
    ), vm)
}

@Composable
private fun GeneralSettings(vm: MainViewModel) {
    val s = vm.settings
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<String?>(null) }
    SettingsList("General", listOf(
        Item.Header("Start-up"),
        Item.Toggle("Start on device boot", "Needs permission to open over other apps", { s.autostartOnBoot }, { v -> s.autostartOnBoot = v; if (v) vm.effects.tryEmit(Effect.OverlayPermission) }),
        Item.Toggle("Start when the device wakes up", "May not work on every device", { s.autostartOnWake }, { v ->
            s.autostartOnWake = v
            if (v) { vm.effects.tryEmit(Effect.OverlayPermission); WakeService.start(ctx) } else WakeService.stop(ctx)
        }),
        Item.Toggle("Play the last channel on start", "", { s.lastChannelOnStart }, { s.lastChannelOnStart = it }),
        Item.Toggle("Confirm exit", "", { s.confirmExit }, { s.confirmExit = it }),
        Item.Header("Sorting"),
        Item.Choice("Channels", sortOptions.drop(1), { s.channelSort }, { s.channelSort = it }),
        Item.Choice("Groups", listOf("playlist" to "Playlist order", "name" to "Name", "manual" to "Manual"), { s.groupSort }, { s.groupSort = it }),
        Item.Header("Channel names"),
        Item.Text("Prefixes to remove", "Comma separated, e.g. US:, UK:", get = { s.namePrefixes }, set = { s.namePrefixes = it }),
        Item.Text("Suffixes to remove", "Comma separated, e.g. | HD, FHD", get = { s.nameSuffixes }, set = { s.nameSuffixes = it }),
        Item.Toggle("Show newly added groups", "When off, new groups from playlist updates start hidden", { s.showNewGroups }, { s.showNewGroups = it }),
        Item.Header("History"),
        Item.Number("Recent channels", 1..50, get = { s.recentChannelCount }, set = { s.recentChannelCount = it }),
        Item.Number("Add to recent after", 0..60, suffix = " s", get = { s.recentDelaySec }, set = { s.recentDelaySec = it }),
        Item.Number("Keep history for", 1..60, suffix = " days", get = { s.historyDays }, set = { s.historyDays = it }),
        Item.Action("Clear recent channels") { scope.launch { vm.repo.clearHistory(); vm.say("History cleared") } },
        Item.Action("Reset watch time", { "Used by \"Most watched\" sorting" }) { confirm = "watch" },
        Item.Action("Clear favorites") { confirm = "favorites" },
        Item.Action("Clear My list") { confirm = "mylist" },
    ), vm)
    confirm?.let { what ->
        ConfirmDialog(when (what) { "watch" -> "Reset watch time?"; "favorites" -> "Clear favorites?"; else -> "Clear My list?" }, "", "OK", { confirm = null }) {
            scope.launch {
                when (what) { "watch" -> vm.repo.resetWatchTime(); "favorites" -> vm.repo.clearFavorites(); else -> vm.repo.db.vod().clearMyList() }
                vm.say("Done")
            }
        }
    }
}

@Composable
private fun BackupSettings(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val app = CasApp.get(ctx)
    val picked by pickResult.collectAsState()
    var url by remember { mutableStateOf(false) }
    var restoreFrom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(picked) {
        val (what, uri) = picked ?: return@LaunchedEffect
        pickResult.value = null
        when (what) {
            "backup_create" -> runCatching { app.backup.writeTo(uri) }.onSuccess { vm.say("Backup saved") }.onFailure { vm.say("Backup failed: ${it.message}") }
            "backup_open" -> restoreFrom = uri.toString()
        }
    }
    SettingsList("Backup and restore", listOf(
        Item.Action("Create backup", { "Playlists, guides, favorites, groups, settings" }) { vm.effects.tryEmit(Effect.Pick("backup_create")) },
        Item.Action("Restore from a file") { vm.effects.tryEmit(Effect.Pick("backup_open")) },
        Item.Action("Restore from an address", { "e.g. a shared link to the backup file" }) { url = true },
    ), vm, "A backup file can also be opened from a file manager to restore it.")
    if (url) TextInputDialog("Backup address", "", keyboard = KeyboardType.Uri, onDismiss = { url = false }) { restoreFrom = it }
    restoreFrom?.let { from ->
        ConfirmDialog("Restore data?", "Your current data and settings will be replaced. The app restarts afterwards.", "Restore", { restoreFrom = null }) {
            scope.launch {
                runCatching { app.backup.restore(app.backup.readFrom(from)) }
                    .onSuccess { vm.say("Data restored"); vm.effects.emit(Effect.Restart) }
                    .onFailure { vm.say("Restore failed: ${it.message}") }
            }
        }
    }
}

@Composable
private fun AboutSettings(vm: MainViewModel) {
    val counts by produceState("") {
        val c = vm.catalog.value
        value = "${c.everything.size} channels · ${vm.repo.movies.value.size} movies · ${vm.repo.series.value.size} shows"
    }
    SettingsList("About", listOf(
        Item.Action("CasTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", { "IPTV player for Android TV" }) {},
        Item.Action("Library", { counts }) {},
        Item.Action("Device", { "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" }) {},
        Item.Action("Content", { "CasTV doesn't provide any channels or content. Use playlists you are entitled to." }) {},
        Item.Action("Open source", { "Media3 (ExoPlayer), Jellyfin FFmpeg decoder, Jetpack Compose, Room, OkHttp, Coil, kotlinx" }) {},
    ), vm)
}

@Suppress("unused")
@Composable
private fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

@Suppress("unused")
private val unusedAlign = Alignment.Center
