package app.castv.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import app.castv.core.Names
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Channel(
    val e: ChannelEntity,
    val pref: ChannelPrefEntity?,
    val name: String,
    val number: Int,
    val groupTitle: String,
    val groupBlocked: Boolean,
    val groupHidden: Boolean,
    val playlistName: String,
) {
    val id get() = e.id
    val playlistId get() = e.playlistId
    val key get() = e.key
    val logo get() = e.logo
    val favorite get() = (pref?.favoritePosition ?: -1) >= 0
    val hidden get() = pref?.hidden == true
    val blocked get() = pref?.blocked == true || groupBlocked
    val hasCatchup get() = e.catchupType.isNotEmpty() && e.catchupDays > 0
    val uid get() = "${e.playlistId}:${e.key}"

    /** Guide source and channel id: chosen by hand, or matched automatically. */
    val epgRef: Pair<Long, String>?
        get() {
            val o = pref?.epgOverride.orEmpty()
            if (o.isNotEmpty()) {
                val sid = o.substringBefore('|').toLongOrNull()
                if (sid != null) return sid to o.substringAfter('|')
            }
            return if (e.epgChannelId.isNotEmpty() && e.epgSourceId > 0) e.epgSourceId to e.epgChannelId else null
        }
}

enum class GroupKind { ALL, FAVORITES, HISTORY, PLAYLIST, GROUP, CUSTOM }

data class GroupRef(val kind: GroupKind, val playlistId: Long = 0, val name: String = "", val customId: Long = 0) {
    val id: String get() = "${kind.name}:$playlistId:$customId:$name"

    companion object {
        val ALL = GroupRef(GroupKind.ALL)
        val FAVORITES = GroupRef(GroupKind.FAVORITES)
        val HISTORY = GroupRef(GroupKind.HISTORY)
        fun parse(id: String): GroupRef? = runCatching {
            val parts = id.split(':', limit = 4)
            GroupRef(GroupKind.valueOf(parts[0]), parts[1].toLong(), parts[3], parts[2].toLong())
        }.getOrNull()
    }
}

data class Group(val ref: GroupRef, val title: String, val count: Int, val blocked: Boolean = false, val hidden: Boolean = false, val isNew: Boolean = false)

data class Catalog(
    val playlists: List<PlaylistEntity> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val everything: List<Channel> = emptyList(),
    val groups: List<Group> = emptyList(),
    val hiddenGroups: List<Group> = emptyList(),
    val byGroup: Map<String, List<Channel>> = emptyMap(),
    val ready: Boolean = false,
) {
    fun channelsIn(ref: GroupRef): List<Channel> = byGroup[ref.id].orEmpty()
    fun find(playlistId: Long, key: String): Channel? = everything.firstOrNull { it.playlistId == playlistId && it.key == key }
    fun byId(id: Long): Channel? = everything.firstOrNull { it.id == id }
    val favorites get() = channelsIn(GroupRef.FAVORITES)
}

data class NowNext(val now: ProgrammeEntity?, val next: ProgrammeEntity?)

class Repo(private val context: Context, val db: AppDb, val settings: Settings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loader = PlaylistLoader(context, db, settings)
    private val importer = EpgImporter(context, db, settings)
    private val updateLock = Mutex()

    /** Short status line for long jobs ("Updating EPG…"), empty when idle. */
    val status = MutableStateFlow("")

    private val inputs = combine(
        db.playlists().observeAll(),
        db.channels().observeEnabled(),
        db.prefs().observeChannelPrefs(),
        db.prefs().observeGroupPrefs(),
        db.activity().observeHistory(),
    ) { p, c, cp, gp, h -> Inputs(p, c, cp, gp, h) }

    private data class Inputs(
        val playlists: List<PlaylistEntity>, val channels: List<ChannelEntity>, val channelPrefs: List<ChannelPrefEntity>,
        val groupPrefs: List<GroupPrefEntity>, val history: List<HistoryEntity>,
    )

    val catalog: StateFlow<Catalog> = combine(
        inputs,
        db.prefs().observeCustomGroups(),
        db.prefs().observeCustomGroupChannels(),
        settings.version,
    ) { i, cg, cgc, _ -> buildCatalog(i, cg, cgc) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, Catalog())

    private fun buildCatalog(i: Inputs, customGroups: List<CustomGroupEntity>, customChannels: List<CustomGroupChannelEntity>): Catalog {
        val s = settings
        val prefixes = Names.splitList(s.namePrefixes)
        val suffixes = Names.splitList(s.nameSuffixes)
        val playlists = sortPlaylists(i.playlists)
        val enabled = playlists.filter { it.enabled }
        val playlistOrder = enabled.withIndex().associate { it.value.id to it.index }
        val prefMap = i.channelPrefs.associateBy { "${it.playlistId}:${it.key}" }
        val groupPrefMap = i.groupPrefs.associateBy { "${it.playlistId}:${it.name}" }
        val playlistNames = playlists.associate { it.id to it.name }

        val rawSorted = i.channels.filter { it.playlistId in playlistOrder }
            .sortedWith(compareBy<ChannelEntity> { playlistOrder[it.playlistId] ?: 0 }.thenBy { it.playlistOrder })
        val useProvided = rawSorted.any { it.number > 0 }
        var seq = 0
        val everything = rawSorted.map { e ->
            seq++
            val pref = prefMap["${e.playlistId}:${e.key}"]
            val gp = groupPrefMap["${e.playlistId}:${e.groupName}"]
            Channel(
                e = e, pref = pref,
                name = pref?.customName?.takeIf { it.isNotBlank() } ?: Names.clean(e.name, prefixes, suffixes),
                number = if (useProvided && e.number > 0) e.number else seq,
                groupTitle = gp?.customName?.takeIf { it.isNotBlank() } ?: e.groupName,
                groupBlocked = gp?.blocked == true, groupHidden = gp?.hidden == true,
                playlistName = playlistNames[e.playlistId].orEmpty(),
            )
        }
        val visible = everything.filter { !it.hidden && !it.groupHidden }
        val byGroup = HashMap<String, List<Channel>>()
        val groups = ArrayList<Group>()
        val hiddenGroups = ArrayList<Group>()
        val multi = enabled.size > 1

        fun sortChannels(list: List<Channel>, override: String): List<Channel> = when (override.ifEmpty { s.channelSort }) {
            "name" -> list.sortedBy { it.name.lowercase() }
            "number" -> list.sortedBy { it.number }
            "watch" -> list.sortedByDescending { it.pref?.watchSeconds ?: 0 }
            "added" -> list.sortedByDescending { it.e.addedAt }
            "manual" -> list.sortedWith(compareBy<Channel> { (it.pref?.position ?: -1).let { p -> if (p < 0) Int.MAX_VALUE else p } }.thenBy { it.e.playlistOrder })
            else -> list
        }

        val all = if (multi && s.groupByPlaylistInAll) visible else sortChannels(visible, "")
        byGroup[GroupRef.ALL.id] = all
        if (s.showAllChannelsCategory || enabled.size <= 1) groups += Group(GroupRef.ALL, "All channels", all.size)

        val favs = visible.filter { it.favorite }.sortedBy { it.pref?.favoritePosition ?: 0 }
        byGroup[GroupRef.FAVORITES.id] = favs
        if (s.showFavoritesCategory) groups += Group(GroupRef.FAVORITES, "Favorites", favs.size)

        val byUid = everything.associateBy { it.uid }
        val recent = i.history.mapNotNull { byUid["${it.playlistId}:${it.key}"] }.filter { !it.hidden }.take(s.recentChannelCount.coerceAtLeast(1))
        byGroup[GroupRef.HISTORY.id] = recent
        if (s.historyButton) groups += Group(GroupRef.HISTORY, "Recent", recent.size)

        for (cg in customGroups) {
            val ref = GroupRef(GroupKind.CUSTOM, customId = cg.id, name = cg.name)
            val members = customChannels.filter { it.groupId == cg.id }.sortedBy { it.position }.mapNotNull { byUid["${it.playlistId}:${it.key}"] }
            byGroup[ref.id] = members
            groups += Group(ref, cg.name, members.size)
        }

        for (p in enabled) {
            val mine = visible.filter { it.playlistId == p.id }
            if (multi && s.showAllPlaylistsCategory) {
                val ref = GroupRef(GroupKind.PLAYLIST, playlistId = p.id, name = p.name)
                byGroup[ref.id] = sortChannels(mine, "")
                groups += Group(ref, p.name, mine.size)
            }
            val names = everything.filter { it.playlistId == p.id }.map { it.e.groupName }.distinct()
            val sort = p.groupSort.ifEmpty { s.groupSort }
            val ordered = when (sort) {
                "name" -> names.sortedBy { (groupPrefMap["${p.id}:$it"]?.customName?.ifBlank { null } ?: it).lowercase() }
                "manual" -> names.sortedWith(compareBy { (groupPrefMap["${p.id}:$it"]?.position ?: -1).let { v -> if (v < 0) Int.MAX_VALUE else v } })
                else -> names
            }
            for (g in ordered) {
                val gp = groupPrefMap["${p.id}:$g"]
                val ref = GroupRef(GroupKind.GROUP, playlistId = p.id, name = g)
                val members = sortChannels(mine.filter { it.e.groupName == g }, gp?.sort.orEmpty())
                byGroup[ref.id] = members
                val group = Group(ref, gp?.customName?.takeIf { it.isNotBlank() } ?: g, members.size, gp?.blocked == true, gp?.hidden == true, gp?.seen == false)
                if (gp?.hidden == true) hiddenGroups += group else groups += group
            }
        }
        return Catalog(playlists, all, everything, groups, hiddenGroups, byGroup, ready = true)
    }

    private fun sortPlaylists(list: List<PlaylistEntity>) = when (settings.playlistSort) {
        "name" -> list.sortedBy { it.name.lowercase() }
        else -> list.sortedWith(compareBy({ it.position }, { it.id }))
    }

    // ---------------------------------------------------------------- playlists

    suspend fun addPlaylist(p: PlaylistEntity, progress: (String) -> Unit = {}): Long {
        val position = db.playlists().all().size
        val id = db.playlists().insert(p.copy(position = position))
        try {
            updatePlaylist(id, progress)
        } catch (e: Exception) {
            db.playlists().delete(id)
            throw e
        }
        return id
    }

    suspend fun updatePlaylist(id: Long, progress: (String) -> Unit = {}) = updateLock.withLock {
        val p = db.playlists().get(id) ?: return@withLock
        try {
            status.value = "Updating ${p.name}…"
            val r = loader.load(p) { progress(it); status.value = it }
            for ((n, url) in r.epgUrls.withIndex()) {
                val existing = db.epg().sourceByUrl(url)
                val sourceId = existing?.id ?: db.epg().insertSource(
                    EpgSourceEntity(name = if (r.epgUrls.size > 1) "${p.name} EPG ${n + 1}" else "${p.name} EPG", url = url, autoForPlaylist = id, priority = 100)
                )
                if (db.epg().assignmentsFor(id).none { it.sourceId == sourceId }) db.epg().assign(PlaylistEpgEntity(id, sourceId, n))
            }
            if (settings.epgUpdateOnPlaylistChange) {
                for (a in db.epg().assignmentsFor(id)) {
                    val src = db.epg().source(a.sourceId) ?: continue
                    if (src.enabled && src.lastUpdated == 0L) importEpgLocked(src, progress)
                }
            }
            resolveEpg(id)
        } catch (e: Exception) {
            Log.w("CasTV", "Playlist update failed", e)
            db.playlists().get(id)?.let { db.playlists().upsert(it.copy(lastError = e.message ?: e.javaClass.simpleName)) }
            throw e
        } finally {
            status.value = ""
        }
    }

    suspend fun updateAllPlaylists(onlyStale: Boolean = false): List<String> {
        val errors = ArrayList<String>()
        for (p in db.playlists().all().filter { it.enabled }) {
            val hours = if (p.updateIntervalHours >= 0) p.updateIntervalHours else settings.playlistUpdateHours
            if (onlyStale && (hours == 0 || System.currentTimeMillis() - p.lastUpdated < hours * 3_600_000L)) continue
            runCatching { updatePlaylist(p.id) }.onFailure { errors += "${p.name}: ${it.message}" }
        }
        return errors
    }

    suspend fun savePlaylist(p: PlaylistEntity) = db.playlists().upsert(p)

    suspend fun deletePlaylist(id: Long) {
        db.withTransaction {
            db.channels().deleteForPlaylist(id)
            db.vod().deleteForPlaylist(id)
            db.prefs().deleteChannelPrefs(id)
            db.prefs().deleteGroupPrefs(id)
            db.epg().unassignPlaylist(id)
            db.playlists().delete(id)
        }
        for (s in db.epg().sources().filter { it.autoForPlaylist == id }) deleteEpgSource(s.id)
    }

    suspend fun movePlaylist(id: Long, delta: Int) {
        val list = sortPlaylists(db.playlists().all()).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        list.forEachIndexed { n, p -> db.playlists().upsert(p.copy(position = n)) }
    }

    // ---------------------------------------------------------------- EPG

    suspend fun addEpgSource(name: String, url: String): Long {
        if (db.epg().sourceByUrl(url) != null) throw IllegalArgumentException("A guide with this address already exists")
        val id = db.epg().insertSource(EpgSourceEntity(name = name.ifBlank { "EPG" }, url = url, priority = db.epg().sources().size))
        // A new source is assigned to every playlist that has none, which is what people expect.
        for (p in db.playlists().all()) if (db.epg().assignmentsFor(p.id).isEmpty()) db.epg().assign(PlaylistEpgEntity(p.id, id, 0))
        return id
    }

    suspend fun deleteEpgSource(id: Long) {
        db.withTransaction {
            db.epg().deleteProgrammes(id)
            db.epg().deleteChannels(id)
            db.epg().unassignSource(id)
            db.epg().deleteSource(id)
        }
        resolveAllEpg()
    }

    suspend fun updateEpgSource(id: Long, progress: (String) -> Unit = {}) = updateLock.withLock {
        val src = db.epg().source(id) ?: return@withLock
        try {
            importEpgLocked(src, progress)
        } finally {
            status.value = ""
        }
        resolveAllEpg()
    }

    private suspend fun importEpgLocked(src: EpgSourceEntity, progress: (String) -> Unit) {
        try {
            status.value = "Updating guide ${src.name}…"
            importer.import(src) { progress(it); status.value = it }
        } catch (e: Exception) {
            Log.w("CasTV", "EPG import failed", e)
            db.epg().source(src.id)?.let { db.epg().upsertSource(it.copy(lastError = e.message ?: e.javaClass.simpleName, lastUpdated = System.currentTimeMillis())) }
            throw e
        }
    }

    suspend fun updateAllEpg(onlyStale: Boolean = false): List<String> {
        val errors = ArrayList<String>()
        val assigned = db.epg().assignments().map { it.sourceId }.toSet()
        for (s in db.epg().sources().filter { it.enabled && it.id in assigned }) {
            if (onlyStale && settings.epgUpdateHours > 0 && System.currentTimeMillis() - s.lastUpdated < settings.epgUpdateHours * 3_600_000L) continue
            runCatching {
                updateLock.withLock { try { importEpgLocked(s) {} } finally { status.value = "" } }
            }.onFailure { errors += "${s.name}: ${it.message}" }
        }
        resolveAllEpg()
        return errors
    }

    suspend fun clearEpg() {
        db.epg().deleteAllProgrammes()
        db.epg().deleteAllChannels()
        for (s in db.epg().sources()) db.epg().upsertSource(s.copy(lastUpdated = 0, channelCount = 0, programmeCount = 0))
        resolveAllEpg()
    }

    suspend fun resolveAllEpg() { for (p in db.playlists().all()) resolveEpg(p.id) }

    /** Matches each channel of a playlist with a guide channel: by tvg-id first, then by name. */
    suspend fun resolveEpg(playlistId: Long) = withContext(Dispatchers.IO) {
        val sources = db.epg().assignmentsFor(playlistId).map { it.sourceId }
        val channels = db.channels().forPlaylist(playlistId)
        if (channels.isEmpty()) return@withContext
        val epgChannels = if (sources.isEmpty()) emptyList() else db.epg().channelsFor(sources)
        val rank = sources.withIndex().associate { it.value to it.index }
        val sorted = epgChannels.sortedBy { rank[it.sourceId] ?: 99 }
        val byId = HashMap<String, EpgChannelEntity>()
        val byIdLower = HashMap<String, EpgChannelEntity>()
        val byKey = HashMap<String, EpgChannelEntity>()
        for (c in sorted) {
            byId.putIfAbsent(c.xmltvId, c)
            byIdLower.putIfAbsent(c.xmltvId.lowercase(), c)
            byKey.putIfAbsent(c.matchKey, c)
        }
        db.withTransaction {
            for (ch in channels) {
                val m = (if (ch.tvgId.isNotEmpty()) byId[ch.tvgId] ?: byIdLower[ch.tvgId.lowercase()] else null)
                    ?: (if (ch.tvgName.isNotEmpty()) byKey[Names.matchKey(ch.tvgName)] else null)
                    ?: byKey[Names.matchKey(ch.name)]
                val sid = m?.sourceId ?: 0
                val cid = m?.xmltvId.orEmpty()
                if (sid != ch.epgSourceId || cid != ch.epgChannelId) db.channels().setEpg(ch.id, sid, cid)
            }
        }
    }

    private fun shiftOf(c: Channel) = c.e.tvgShiftSeconds + settings.epgOffsetSec

    suspend fun programmes(c: Channel, from: Long, to: Long): List<ProgrammeEntity> {
        val ref = c.epgRef ?: return emptyList()
        val shift = shiftOf(c)
        return db.epg().programmes(ref.first, ref.second, from - shift, to - shift).map { if (shift == 0L) it else it.copy(start = it.start + shift, stop = it.stop + shift) }
    }

    suspend fun nowNext(c: Channel, at: Long = System.currentTimeMillis() / 1000): NowNext {
        val list = programmes(c, at, at + 6 * 3600)
        val now = list.firstOrNull { it.start <= at && it.stop > at }
        val next = list.firstOrNull { it.start >= (now?.stop ?: at) }
        return NowNext(now, next)
    }

    suspend fun epgChannelsForPlaylist(playlistId: Long): List<EpgChannelEntity> {
        val ids = db.epg().assignmentsFor(playlistId).map { it.sourceId }.ifEmpty { db.epg().sources().map { it.id } }
        return db.epg().channelsFor(ids).sortedBy { it.displayName.lowercase() }
    }

    // ---------------------------------------------------------------- channel choices

    private suspend fun editPref(c: Channel, f: (ChannelPrefEntity) -> ChannelPrefEntity) {
        val cur = db.prefs().channelPref(c.playlistId, c.key) ?: ChannelPrefEntity(c.playlistId, c.key)
        db.prefs().upsertChannelPref(f(cur))
    }

    suspend fun setFavorite(c: Channel, favorite: Boolean) {
        val next = if (favorite) (catalog.value.favorites.maxOfOrNull { it.pref?.favoritePosition ?: 0 } ?: -1) + 1 else -1
        editPref(c) { it.copy(favoritePosition = next) }
    }

    suspend fun toggleFavorite(c: Channel) = setFavorite(c, !c.favorite)

    suspend fun setFavoritesOrder(list: List<Channel>) {
        db.prefs().upsertChannelPrefs(list.mapIndexed { i, c ->
            (db.prefs().channelPref(c.playlistId, c.key) ?: ChannelPrefEntity(c.playlistId, c.key)).copy(favoritePosition = i)
        })
    }

    suspend fun setChannelsOrder(list: List<Channel>) {
        db.prefs().upsertChannelPrefs(list.mapIndexed { i, c ->
            (db.prefs().channelPref(c.playlistId, c.key) ?: ChannelPrefEntity(c.playlistId, c.key)).copy(position = i)
        })
    }

    suspend fun resetPositions(list: List<Channel>) {
        db.prefs().upsertChannelPrefs(list.map { c -> (db.prefs().channelPref(c.playlistId, c.key) ?: ChannelPrefEntity(c.playlistId, c.key)).copy(position = -1) })
    }

    suspend fun setHidden(c: Channel, hidden: Boolean) = editPref(c) { it.copy(hidden = hidden) }
    suspend fun setBlocked(c: Channel, blocked: Boolean) = editPref(c) { it.copy(blocked = blocked) }
    suspend fun rename(c: Channel, name: String) = editPref(c) { it.copy(customName = name) }
    suspend fun setEpgOverride(c: Channel, sourceId: Long, xmltvId: String) = editPref(c) { it.copy(epgOverride = if (xmltvId.isEmpty()) "" else "$sourceId|$xmltvId") }
    suspend fun setDisplayMode(c: Channel, mode: String) = editPref(c) { it.copy(displayMode = mode) }
    suspend fun setAudioOffset(c: Channel, ms: Int) = editPref(c) { it.copy(audioOffsetMs = ms) }
    suspend fun setAudioLanguage(c: Channel, lang: String) = editPref(c) { it.copy(audioLanguage = lang) }

    suspend fun addWatchTime(c: Channel, seconds: Long) = editPref(c) { it.copy(watchSeconds = it.watchSeconds + seconds, lastWatched = System.currentTimeMillis()) }
    suspend fun resetWatchTime() = db.prefs().resetWatchTime()
    suspend fun clearFavorites() = db.prefs().clearFavorites()

    private suspend fun editGroup(playlistId: Long, name: String, f: (GroupPrefEntity) -> GroupPrefEntity) {
        val cur = db.prefs().groupPrefs().firstOrNull { it.playlistId == playlistId && it.name == name } ?: GroupPrefEntity(playlistId, name)
        db.prefs().upsertGroupPref(f(cur))
    }

    suspend fun setGroupHidden(ref: GroupRef, hidden: Boolean) = editGroup(ref.playlistId, ref.name) { it.copy(hidden = hidden, seen = true) }
    suspend fun setGroupBlocked(ref: GroupRef, blocked: Boolean) = editGroup(ref.playlistId, ref.name) { it.copy(blocked = blocked) }
    suspend fun renameGroup(ref: GroupRef, name: String) = editGroup(ref.playlistId, ref.name) { it.copy(customName = name) }
    suspend fun setGroupSort(ref: GroupRef, sort: String) = editGroup(ref.playlistId, ref.name) { it.copy(sort = sort) }
    suspend fun markGroupsSeen() {
        db.prefs().upsertGroupPrefs(db.prefs().groupPrefs().filter { !it.seen }.map { it.copy(seen = true) })
    }

    suspend fun setGroupsOrder(playlistId: Long, names: List<String>) {
        val existing = db.prefs().groupPrefs().filter { it.playlistId == playlistId }.associateBy { it.name }
        db.prefs().upsertGroupPrefs(names.mapIndexed { i, n -> (existing[n] ?: GroupPrefEntity(playlistId, n)).copy(position = i) })
    }

    suspend fun createCustomGroup(name: String, channels: List<Channel> = emptyList()): Long {
        val id = db.prefs().insertCustomGroup(CustomGroupEntity(name = name, position = db.prefs().customGroups().size))
        addToCustomGroup(id, channels)
        return id
    }

    suspend fun addToCustomGroup(groupId: Long, channels: List<Channel>) {
        val start = db.prefs().customGroupChannels().count { it.groupId == groupId }
        db.prefs().upsertCustomGroupChannels(channels.mapIndexed { i, c -> CustomGroupChannelEntity(groupId, c.playlistId, c.key, start + i) })
    }

    suspend fun setCustomGroupOrder(groupId: Long, channels: List<Channel>) {
        db.prefs().clearCustomGroup(groupId)
        db.prefs().upsertCustomGroupChannels(channels.mapIndexed { i, c -> CustomGroupChannelEntity(groupId, c.playlistId, c.key, i) })
    }

    suspend fun removeFromCustomGroup(groupId: Long, c: Channel) = db.prefs().removeFromCustomGroup(groupId, c.playlistId, c.key)
    suspend fun renameCustomGroup(groupId: Long, name: String) {
        db.prefs().customGroups().firstOrNull { it.id == groupId }?.let { db.prefs().upsertCustomGroup(it.copy(name = name)) }
    }
    suspend fun deleteCustomGroup(groupId: Long) {
        db.prefs().clearCustomGroup(groupId)
        db.prefs().deleteCustomGroup(groupId)
    }

    // ---------------------------------------------------------------- history

    suspend fun addHistory(c: Channel) {
        db.activity().addHistory(HistoryEntity(c.playlistId, c.key, System.currentTimeMillis()))
        db.activity().trimHistory(System.currentTimeMillis() - settings.historyDays.coerceAtLeast(1) * 86_400_000L)
    }

    suspend fun removeHistory(c: Channel) = db.activity().removeHistory(c.playlistId, c.key)
    suspend fun clearHistory() = db.activity().clearHistory()

    // ---------------------------------------------------------------- search

    data class SearchResult(val channels: List<Channel>, val programmes: List<Pair<Channel, ProgrammeEntity>>, val vod: List<VodEntity>)

    suspend fun search(q: String): SearchResult {
        val query = q.trim()
        if (query.isEmpty()) return SearchResult(emptyList(), emptyList(), emptyList())
        val cat = catalog.value
        val key = query.lowercase()
        var channels = cat.channels.filter { key in it.name.lowercase() || key == it.number.toString() }
        if (settings.searchFavoritesFirst) channels = channels.sortedByDescending { it.favorite }
        val now = System.currentTimeMillis() / 1000
        val from = if (settings.searchShowPastWithoutCatchup) now - settings.epgPastDays * 86400L else now - 7 * 86400L
        val byEpg = HashMap<String, MutableList<Channel>>()
        for (c in cat.channels) c.epgRef?.let { byEpg.getOrPut("${it.first}|${it.second}") { ArrayList() } += c }
        val progs = db.epg().search(query, from).flatMap { p ->
            byEpg["${p.sourceId}|${p.channelId}"].orEmpty().map { it to p }
        }.filter { (c, p) ->
            p.stop > now || settings.searchShowPastWithoutCatchup || (c.hasCatchup && p.start > now - c.e.catchupDays * 86400L)
        }
        return SearchResult(channels.take(200), progs.take(200), db.vod().search(query))
    }

    // ---------------------------------------------------------------- VOD

    val movies: StateFlow<List<VodEntity>> = db.vod().observe("movie").stateIn(scope, SharingStarted.Eagerly, emptyList())
    val series: StateFlow<List<VodEntity>> = db.vod().observe("series").stateIn(scope, SharingStarted.Eagerly, emptyList())
    val vodProgress: StateFlow<List<VodProgressEntity>> = db.vod().observeProgress().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val myList: StateFlow<List<MyListEntity>> = db.vod().observeMyList().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val seriesCache = HashMap<String, XtreamSeriesInfo>()

    suspend fun seriesInfo(v: VodEntity): XtreamSeriesInfo = withContext(Dispatchers.IO) {
        seriesCache["${v.playlistId}:${v.key}"]?.let { return@withContext it }
        val p = db.playlists().get(v.playlistId) ?: throw IllegalStateException("Playlist removed")
        val info = XtreamClient(context, p.url, p.username, p.password, p.userAgent).seriesInfo(v.streamId)
        seriesCache["${v.playlistId}:${v.key}"] = info
        info
    }

    suspend fun movieDetails(v: VodEntity): VodEntity = withContext(Dispatchers.IO) {
        if (v.plot.isNotEmpty() || v.streamId.isEmpty()) return@withContext v
        val p = db.playlists().get(v.playlistId) ?: return@withContext v
        if (p.type != PlaylistType.XTREAM) return@withContext v
        runCatching {
            val info = XtreamClient(context, p.url, p.username, p.password, p.userAgent).vodInfo(v.streamId)?.get("info").obj() ?: return@runCatching v
            val updated = v.copy(
                plot = info.str("plot").ifEmpty { info.str("description") }, cast = info.str("cast").ifEmpty { info.str("actors") },
                director = info.str("director"), genre = info.str("genre"), year = info.str("releasedate").take(4).ifEmpty { v.year },
                rating = info.double("rating").takeIf { it > 0 } ?: v.rating, durationSecs = info.long("duration_secs"),
                trailer = info.str("youtube_trailer"), backdrop = info["backdrop_path"].arr()?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
            )
            db.vod().upsert(updated)
            updated
        }.getOrDefault(v)
    }

    suspend fun saveProgress(playlistId: Long, key: String, pos: Long, dur: Long, title: String, seriesKey: String = "") {
        if (dur > 0 && pos > dur - 60_000) {
            // Finished: remember it as watched (position = duration).
            db.vod().saveProgress(VodProgressEntity(playlistId, key, dur, dur, System.currentTimeMillis(), title, seriesKey))
        } else if (pos > 30_000) {
            db.vod().saveProgress(VodProgressEntity(playlistId, key, pos, dur, System.currentTimeMillis(), title, seriesKey))
        }
    }

    suspend fun progress(playlistId: Long, key: String) = db.vod().progress(playlistId, key)
    suspend fun clearProgress(playlistId: Long, key: String) = db.vod().deleteProgress(playlistId, key)

    suspend fun toggleMyList(playlistId: Long, key: String, kind: String, title: String, image: String) {
        if (myList.value.any { it.playlistId == playlistId && it.key == key }) db.vod().removeFromMyList(playlistId, key)
        else db.vod().addToMyList(MyListEntity(playlistId, key, kind, title, System.currentTimeMillis(), image))
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
}
