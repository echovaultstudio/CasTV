package app.castv.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.castv.data.VodEntity
import app.castv.data.XtreamSeriesInfo
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private const val CAT_ALL = "\u0000all"
private const val CAT_CONTINUE = "\u0000continue"
private const val CAT_MYLIST = "\u0000mylist"
private const val CAT_RECENT = "\u0000recent"

@Composable
fun VodBrowser(vm: MainViewModel, kind: String) {
    val ui = LocalUi.current
    val s = vm.settings
    val items by (if (kind == "movie") vm.repo.movies else vm.repo.series).collectAsState()
    val progress by vm.repo.vodProgress.collectAsState()
    val myList by vm.repo.myList.collectAsState()
    var category by remember { mutableStateOf(CAT_ALL) }
    var sort by remember { mutableStateOf(s.vodSort) }
    var sortDialog by remember { mutableStateOf(false) }
    val firstCat = remember { FocusRequester() }

    val categories = remember(items) { items.map { it.category }.distinct() }
    val continueKeys = remember(progress, kind) {
        progress.filter { it.positionMs < it.durationMs - 60_000 }.map { "${it.playlistId}:${if (kind == "series") it.seriesKey else it.key}" }.toSet()
    }
    val listKeys = remember(myList) { myList.map { "${it.playlistId}:${it.key}" }.toSet() }
    val shown = remember(items, category, sort, continueKeys, listKeys) {
        val base = when (category) {
            CAT_ALL -> items
            CAT_CONTINUE -> items.filter { "${it.playlistId}:${it.key}" in continueKeys }
            CAT_MYLIST -> items.filter { "${it.playlistId}:${it.key}" in listKeys }
            CAT_RECENT -> items.sortedByDescending { it.addedAt }.take(100)
            else -> items.filter { it.category == category }
        }
        when (sort) {
            "name" -> base.sortedBy { it.name.lowercase() }
            "rating" -> base.sortedByDescending { it.rating }
            "year" -> base.sortedByDescending { it.year }
            "added" -> base.sortedByDescending { it.addedAt }
            else -> base
        }
    }
    val cats = listOf(CAT_ALL to (if (kind == "movie") "All movies" else "All shows"), CAT_CONTINUE to "Continue watching", CAT_MYLIST to "My list", CAT_RECENT to "Recently added") +
        categories.map { it to it }

    Row(Modifier.fillMaxSize().background(Color(0xFF0B0D11))) {
        Column(Modifier.width(300.dp).fillMaxHeight().background(ui.panelStrong).padding(16.dp)) {
            Label(if (kind == "movie") "Movies" else "Shows", Modifier.padding(8.dp), size = 24, bold = true, color = ui.accent)
            FocusRow(onClick = { sortDialog = true }) { Dim("Sort: " + sortName(sort), Modifier.weight(1f)) }
            LazyColumn {
                itemsIndexed(cats) { i, (key, title) ->
                    FocusRow(selected = key == category, focusRequester = if (i == 0) firstCat else null, onFocus = { category = key }, onClick = { category = key }) {
                        Label(title, Modifier.weight(1f))
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 20.dp, end = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(cats.firstOrNull { it.first == category }?.second.orEmpty(), Modifier.weight(1f), size = 20, bold = true)
                Dim("${shown.size}")
            }
            Spacer(Modifier.height(10.dp))
            if (items.isEmpty()) Dim("Nothing here. Movies and shows come from Xtream Codes playlists (or M3U playlists with VOD links).", maxLines = 3)
            LazyVerticalGrid(GridCells.Adaptive(if (s.cardStyle == "wide") 260.dp else 150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(shown, key = { _, v -> v.id }) { _, v ->
                    val p = progress.firstOrNull { it.playlistId == v.playlistId && (it.key == v.key || it.seriesKey == v.key) }
                    PosterCard(v, wide = s.cardStyle == "wide", progress = p?.let { it.positionMs.toFloat() / it.durationMs.coerceAtLeast(1) }) { vm.open(Screen.VodDetail(v.id)) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstCat.requestFocus() } }
    BackHandler { vm.back() }
    if (sortDialog) ChoiceDialog("Sort", listOf("added" to "Recently added", "name" to "Name", "rating" to "Rating", "year" to "Year", "playlist" to "Playlist order"), sort, { sortDialog = false }) {
        sort = it; s.vodSort = it; sortDialog = false
    }
}

private fun sortName(s: String) = when (s) { "name" -> "Name"; "rating" -> "Rating"; "year" -> "Year"; "playlist" -> "Playlist order"; else -> "Recently added" }

@Composable
private fun PosterCard(v: VodEntity, wide: Boolean, progress: Float?, onClick: () -> Unit) {
    val ui = LocalUi.current
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .border(3.dp, if (focused) ui.focus else Color.Transparent, RoundedCornerShape(8.dp))
            .tvClickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f).background(Color(0xFF1C2028))) {
            Label(v.name, Modifier.align(Alignment.Center).padding(8.dp), size = 14, maxLines = 4)
            val img = if (wide) v.backdrop.ifEmpty { v.poster } else v.poster
            if (img.isNotEmpty()) AsyncImage(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (v.rating > 0) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Label("★ %.1f".format(v.rating), size = 12)
            }
            if (progress != null) Bar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), 4.dp)
        }
        Label(v.name, Modifier.padding(6.dp), size = 13, color = if (focused) ui.text else ui.dim, maxLines = 2)
    }
}

@Composable
fun VodDetail(vm: MainViewModel, id: Long) {
    val ui = LocalUi.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val base by produceState<VodEntity?>(null, id) { value = vm.repo.db.vod().get(id) }
    val v0 = base ?: run { Box(Modifier.fillMaxSize().background(Color.Black)); return }
    val v by produceState(v0, v0.id) { value = if (v0.kind == "movie") vm.repo.movieDetails(v0) else v0 }
    val series by produceState<Result<XtreamSeriesInfo>?>(null, v0.id) { if (v0.kind == "series") value = runCatching { vm.repo.seriesInfo(v0) } }
    val progressList by vm.repo.vodProgress.collectAsState()
    val myList by vm.repo.myList.collectAsState()
    val inList = myList.any { it.playlistId == v.playlistId && it.key == v.key }
    val first = remember { FocusRequester() }
    var season by remember { mutableIntStateOf(-1) }
    val info = series?.getOrNull()
    val seasons = info?.seasons.orEmpty()
    val currentSeason = if (season in seasons) season else seasons.firstOrNull() ?: 0
    val movieProgress = progressList.firstOrNull { it.playlistId == v.playlistId && it.key == v.key }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D11))) {
        val backdrop = v.backdrop.ifEmpty { info?.backdrop.orEmpty() }.ifEmpty { v.poster }
        if (backdrop.isNotEmpty()) AsyncImage(backdrop, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF00B0D11), Color(0xC00B0D11), Color(0x400B0D11)))))
        Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Box(Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C2028))) {
                if (v.poster.isNotEmpty()) AsyncImage(v.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(v.name, size = 30, bold = true, maxLines = 2)
                Dim(listOf(v.year.ifEmpty { info?.year.orEmpty() }, v.genre.ifEmpty { info?.genre.orEmpty() },
                    if (v.durationSecs > 0) "${v.durationSecs / 3600}h ${(v.durationSecs % 3600) / 60}m" else "",
                    if (v.rating > 0) "★ %.1f".format(v.rating) else "", if (v.kind == "series" && seasons.isNotEmpty()) "${seasons.size} season${if (seasons.size > 1) "s" else ""}" else "",
                ).filter { it.isNotEmpty() }.joinToString("  ·  "), size = 15)
                Label(v.plot.ifEmpty { info?.plot.orEmpty() }.ifEmpty { "No description" }, size = 15, maxLines = 5, color = ui.dim)
                v.director.ifEmpty { info?.director.orEmpty() }.takeIf { it.isNotEmpty() }?.let { Dim("Director: $it", maxLines = 1) }
                v.cast.ifEmpty { info?.cast.orEmpty() }.takeIf { it.isNotEmpty() }?.let { Dim("Cast: $it", maxLines = 2) }
                Spacer(Modifier.height(6.dp))
                val trailer = v.trailer.ifEmpty { info?.trailer.orEmpty() }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val buttons = buildList<Pair<String, () -> Unit>> {
                        if (v.kind == "movie") {
                            if (movieProgress != null && movieProgress.positionMs < movieProgress.durationMs - 60_000) {
                                add("Resume ${duration(movieProgress.positionMs)}" to { vm.playMovie(v, fromStart = false) })
                                add("From the beginning" to { vm.playMovie(v, fromStart = true) })
                            } else add("Play" to { vm.playMovie(v, fromStart = true) })
                        } else if (info != null && info.episodes.isNotEmpty()) {
                            val last = progressList.firstOrNull { it.playlistId == v.playlistId && it.seriesKey == v.key }
                            val idx = last?.let { l -> info.episodes.indexOfFirst { "${v.key}/${it.id}" == l.key } } ?: -1
                            if (idx >= 0) {
                                val done = last!!.positionMs >= last.durationMs - 60_000
                                val target = if (done) (idx + 1).coerceAtMost(info.episodes.lastIndex) else idx
                                val ep = info.episodes[target]
                                add("${if (done) "Next" else "Resume"} S${ep.season} E${ep.number}" to { vm.playEpisode(v, info.episodes, target, fromStart = done) })
                            } else add("Play S${info.episodes[0].season} E${info.episodes[0].number}" to { vm.playEpisode(v, info.episodes, 0, fromStart = true) })
                        }
                        if (trailer.isNotEmpty()) add("Trailer" to {
                            val url = if (trailer.startsWith("http")) trailer else "https://www.youtube.com/watch?v=$trailer"
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { vm.say("No app can open the trailer") }
                        })
                        add((if (inList) "Remove from My list" else "Add to My list") to { scope.launch { vm.repo.toggleMyList(v.playlistId, v.key, v.kind, v.name, v.poster) } })
                    }
                    itemsIndexed(buttons) { i, (label, run) ->
                        Box(Modifier.width(210.dp)) { FocusRow(focusRequester = if (i == 0) first else null, onClick = run) { Label(label, Modifier.weight(1f)) } }
                    }
                }
                if (v.kind == "series") {
                    when {
                        series == null -> Dim("Loading episodes…")
                        info == null -> Dim("Couldn't load episodes: ${series?.exceptionOrNull()?.message}", maxLines = 2)
                        else -> {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                itemsIndexed(seasons) { _, n ->
                                    Box(Modifier.width(130.dp)) { FocusRow(selected = n == currentSeason, onFocus = { season = n }, onClick = { season = n }) { Label("Season $n") } }
                                }
                            }
                            LazyColumn(Modifier.fillMaxWidth()) {
                                val eps = info.episodes.withIndex().filter { it.value.season == currentSeason }
                                itemsIndexed(eps) { _, (index, ep) ->
                                    val p = progressList.firstOrNull { it.playlistId == v.playlistId && it.key == "${v.key}/${ep.id}" }
                                    FocusRow(onClick = { vm.playEpisode(v, info.episodes, index, fromStart = p == null || p.positionMs >= p.durationMs - 60_000) }) {
                                        Label("E${ep.number}", Modifier.width(48.dp), color = ui.accent)
                                        Column(Modifier.weight(1f)) {
                                            Label(ep.title.ifEmpty { "Episode ${ep.number}" })
                                            if (ep.plot.isNotEmpty()) Dim(ep.plot, maxLines = 1)
                                            if (p != null) Bar(p.positionMs.toFloat() / p.durationMs.coerceAtLeast(1), Modifier.fillMaxWidth().padding(top = 4.dp))
                                        }
                                        if (ep.durationSecs > 0) Dim("${ep.durationSecs / 60} min")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(v.id, info != null) { runCatching { first.requestFocus() } }
    BackHandler { vm.back() }
}
