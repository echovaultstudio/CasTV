package app.castv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.castv.data.PlaylistEntity
import app.castv.data.PlaylistType
import app.castv.data.XtreamClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Field(val key: String, val label: String, val hint: String = "", val keyboard: KeyboardType = KeyboardType.Text, val password: Boolean = false, val optional: Boolean = false)

/** Adding a playlist: type, details, name and an optional guide address, then the first download. */
@Composable
fun PlaylistWizard(vm: MainViewModel) {
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val picked by pickResult.collectAsState()
    var type by remember { mutableStateOf<String?>(null) }
    val values = remember { mutableStateOf(mapOf<String, String>()) }
    var editing by remember { mutableStateOf<Field?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    fun set(k: String, v: String) { values.value = values.value + (k to v) }
    fun get(k: String) = values.value[k].orEmpty()

    LaunchedEffect(picked) {
        val (what, uri) = picked ?: return@LaunchedEffect
        if (what == "m3u") {
            pickResult.value = null
            set("url", uri.toString())
            if (get("name").isEmpty()) set("name", uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Playlist")
        }
    }

    val fields = when (type) {
        PlaylistType.XTREAM -> listOf(
            Field("url", "Server address", "e.g. http://example.com:8080", KeyboardType.Uri),
            Field("username", "Username"), Field("password", "Password", password = true),
            Field("name", "Playlist name", optional = true), Field("epg", "Guide address (XMLTV)", "Leave empty to use the provider's guide", KeyboardType.Uri, optional = true),
        )
        PlaylistType.STALKER -> listOf(
            Field("url", "Portal address", "e.g. http://example.com/c/", KeyboardType.Uri),
            Field("mac", "MAC address", "00:1A:79:XX:XX:XX"),
            Field("serial", "Serial number", optional = true), Field("device", "Device ID", optional = true),
            Field("name", "Playlist name", optional = true), Field("epg", "Guide address (XMLTV)", keyboard = KeyboardType.Uri, optional = true),
        )
        PlaylistType.M3U -> listOf(
            Field("url", "Playlist address", "http(s)://… link to an .m3u/.m3u8 playlist, or pick a file below", KeyboardType.Uri),
            Field("name", "Playlist name", optional = true), Field("epg", "Guide address (XMLTV)", "Optional; many playlists include one", KeyboardType.Uri, optional = true),
            Field("ua", "User-Agent", optional = true),
        )
        else -> emptyList()
    }

    fun submit() {
        val t = type ?: return
        val missing = fields.firstOrNull { !it.optional && get(it.key).isBlank() }
        if (missing != null) { error = "${missing.label} is required"; return }
        var url = get("url").trim()
        var user = get("username")
        var pass = get("password")
        var finalType = t
        // A pasted get.php address is really an Xtream account.
        if (t == PlaylistType.M3U) XtreamClient.fromM3uUrl(url)?.let { (server, u, p) -> finalType = PlaylistType.XTREAM; url = server; user = u; pass = p }
        val name = get("name").ifBlank {
            when (finalType) {
                PlaylistType.M3U -> url.substringAfter("://").substringBefore('/').ifEmpty { "Playlist" }
                else -> url.substringAfter("://").substringBefore('/').substringBefore(':')
            }
        }
        val p = PlaylistEntity(
            name = name, type = finalType, url = url, username = user, password = pass, mac = get("mac").uppercase(),
            serial = get("serial"), deviceId = get("device"), userAgent = get("ua"),
        )
        error = null
        busy = "Processing…"
        scope.launch {
            runCatching {
                val id = vm.repo.addPlaylist(p) { busy = it }
                val epg = get("epg").trim()
                if (epg.isNotEmpty()) {
                    busy = "Adding the guide…"
                    val sid = vm.repo.db.epg().sourceByUrl(epg)?.id ?: vm.repo.addEpgSource("$name guide", epg)
                    vm.repo.db.epg().assign(app.castv.data.PlaylistEpgEntity(id, sid, -1))
                    runCatching { vm.repo.updateEpgSource(sid) { busy = it } }
                }
                id
            }.onSuccess {
                busy = null
                vm.say("Playlist added")
                vm.home()
                vm.catalog.value.channels.firstOrNull()?.let { c -> if (vm.current.value == null) vm.play(c) }
            }.onFailure {
                busy = null
                error = "Couldn't add the playlist: ${it.message ?: it.javaClass.simpleName}. Check the details and the internet connection."
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D11)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(720.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Add playlist", size = 28, bold = true, color = ui.accent)
            val b = busy
            when {
                b != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = ui.accent)
                    Label(b, size = 17)
                }
                type == null -> {
                    Dim("Choose the type of playlist your provider gave you.", size = 15)
                    val types = listOf(
                        PlaylistType.M3U to ("M3U playlist" to "A link or a file (.m3u, .m3u8)"),
                        PlaylistType.XTREAM to ("Xtream Codes" to "Server, username and password"),
                        PlaylistType.STALKER to ("Stalker portal" to "Portal address and MAC address"),
                    )
                    types.forEachIndexed { i, (t, text) ->
                        FocusRow(focusRequester = if (i == 0) first else null, onClick = { type = t }) {
                            Column(Modifier.weight(1f)) { Label(text.first, bold = true); Dim(text.second) }
                        }
                    }
                }
                else -> {
                    error?.let { Label(it, color = Color(0xFFFF7B7B), maxLines = 3) }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(fields) { i, f ->
                            FocusRow(focusRequester = if (i == 0) first else null, onClick = { editing = f }) {
                                Column(Modifier.weight(1f)) {
                                    Label(f.label + if (f.optional) " (optional)" else "")
                                    val v = get(f.key)
                                    Dim(if (v.isEmpty()) f.hint.ifEmpty { "Not set" } else if (f.password) "••••••" else v, maxLines = 2)
                                }
                            }
                        }
                        if (type == PlaylistType.M3U) {
                            itemsIndexed(listOf(0)) { _, _ ->
                                FocusRow(onClick = { vm.effects.tryEmit(Effect.Pick("m3u")) }) { Label("Pick a local file…") }
                            }
                        }
                        itemsIndexed(listOf(0)) { _, _ ->
                            FocusRow(onClick = {
                                val text = clipboard.getText()?.text?.trim().orEmpty()
                                if (text.startsWith("http")) set("url", text) else vm.say("The clipboard has no address")
                            }) { Label("Paste address from clipboard") }
                        }
                        itemsIndexed(listOf(0)) { _, _ ->
                            FocusRow(onClick = { submit() }) { Label("Add playlist", Modifier.weight(1f), bold = true, color = ui.accent) }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(type, busy == null) { delay(100); runCatching { first.requestFocus() } }
    BackHandler { if (busy != null) Unit else if (type != null) { type = null; error = null } else vm.back() }
    editing?.let { f ->
        TextInputDialog(f.label, get(f.key), f.hint, f.keyboard, f.password, onDismiss = { editing = null }) { set(f.key, it) }
    }
    @Suppress("UNUSED_EXPRESSION") Modifier.fillMaxWidth()
}
