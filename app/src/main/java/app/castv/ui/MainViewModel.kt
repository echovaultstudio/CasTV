package app.castv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import app.castv.CasApp
import app.castv.data.Channel
import app.castv.data.GroupKind
import app.castv.data.GroupRef
import app.castv.data.NowNext
import app.castv.data.ProgrammeEntity
import app.castv.data.RecStatus
import app.castv.data.RecordingEntity
import app.castv.data.ReminderEntity
import app.castv.data.VodEntity
import app.castv.data.XtreamEpisode
import app.castv.player.AudioOffset
import app.castv.player.PlayerFactory
import app.castv.player.Stream
import app.castv.player.StreamResolver
import app.castv.recording.RecordingScheduler
import app.castv.recording.RecordingService
import app.castv.system.Reminders
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Watch : Screen
    data object Guide : Screen
    data class Vod(val kind: String) : Screen
    data class VodDetail(val id: Long) : Screen
    data object Search : Screen
    data object Recordings : Screen
    data object Reminders : Screen
    data class Settings(val page: String = "root", val arg: Long = 0) : Screen
    data class Wizard(val editId: Long = 0) : Screen
    data class Manage(val mode: String, val group: String) : Screen
    data object Multiview : Screen
}

enum class Overlay { NONE, INFO, CHANNELS, GROUPS, GUIDE, CONTROLS, MENU }

sealed interface Mode {
    data object Live : Mode
    /** [streamStart] is the epoch second the current catch-up stream begins at. */
    data class Catchup(val streamStart: Long, val end: Long, val title: String, val progStart: Long, val progStop: Long) : Mode
    data class Vod(
        val playlistId: Long, val key: String, val title: String, val seriesKey: String = "",
        val queue: List<XtreamEpisode> = emptyList(), val index: Int = -1, val url: String = "",
    ) : Mode
}

sealed interface Effect {
    data class External(val url: String, val title: String) : Effect
    data object Finish : Effect
    data object Background : Effect
    data object Pip : Effect
    data object Restart : Effect
    data class Pick(val what: String) : Effect
    data object Voice : Effect
    data object OverlayPermission : Effect
    data object ExactAlarmPermission : Effect
}

data class PinRequest(val title: String, val onOk: () -> Unit)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val cas = app as CasApp
    val repo = cas.repo
    val settings = cas.settings
    val resolver = StreamResolver(app, repo)
    val audioOffset = AudioOffset()

    var player: ExoPlayer = PlayerFactory.create(app, settings, audioOffset)
        private set
    val playerVersion = MutableStateFlow(0)

    val catalog = repo.catalog
    val current = MutableStateFlow<Channel?>(null)
    val mode = MutableStateFlow<Mode>(Mode.Live)
    val group = MutableStateFlow(GroupRef.ALL)
    val overlay = MutableStateFlow(Overlay.NONE)
    val screens = MutableStateFlow<List<Screen>>(listOf(Screen.Watch))
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val effects = MutableSharedFlow<Effect>(extraBufferCapacity = 8)
    val nowNext = MutableStateFlow(NowNext(null, null))
    val preview = MutableStateFlow<Channel?>(null)
    val previewNowNext = MutableStateFlow(NowNext(null, null))
    val numberEntry = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val mediaInfo = MutableStateFlow("")
    val videoFormat = MutableStateFlow<Format?>(null)
    val displayMode = MutableStateFlow(settings.displayMode)
    val sleepAt = MutableStateFlow(0L)
    val pin = MutableStateFlow<PinRequest?>(null)
    val reminderPopup = MutableStateFlow<ReminderEntity?>(null)
    val inPip = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val buffering = MutableStateFlow(false)
    val speed = MutableStateFlow(1f)
    val status: StateFlow<String> = repo.status
    val settingsVersion = settings.version
    val recordings = repo.db.activity().observeRecordings().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val reminders = repo.db.activity().observeReminders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hasPlaylists = repo.db.playlists().observeAll().map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private var previous: Channel? = null
    private var playJob: Job? = null
    private var historyJob: Job? = null
    private var retries = 0
    private var unlockedUntil = 0L
    private var sessionUnlocked = false
    private var numberJob: Job? = null
    private var sleepJob: Job? = null
    private var lastWatchTick = 0L
    private var started = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering.value = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) { retries = 0; error.value = null }
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onIsPlayingChanged(playing: Boolean) { isPlaying.value = playing }

        override fun onPlayerError(e: PlaybackException) {
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition(); player.prepare(); return
            }
            val m = mode.value
            if (retries < settings.maxReconnects && (m is Mode.Live || m is Mode.Catchup)) {
                retries++
                error.value = "Reconnecting… ($retries)"
                viewModelScope.launch { delay(1000L * retries); if (m == mode.value) replay() }
            } else {
                error.value = errorText(e)
            }
        }

        override fun onTracksChanged(tracks: Tracks) = updateMediaInfo()
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = updateMediaInfo()
    }

    init {
        player.addListener(listener)
        viewModelScope.launch { cas.events.collect { if (it is CasApp.Event.Reminder) reminderPopup.value = repo.db.activity().reminder(it.id) } }
        viewModelScope.launch {
            while (isActive) {
                refreshNowNext()
                tickWatchTime()
                delay(30_000)
            }
        }
    }

    // ---------------------------------------------------------------- start-up

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val cat = catalog.first { it.ready }
            group.value = GroupRef.parse(settings.lastGroup)?.takeIf { cat.byGroup.containsKey(it.id) } ?: GroupRef.ALL
            if (settings.lastChannelOnStart) {
                cat.find(settings.lastPlaylistId, settings.lastChannelKey)?.let { play(it, showInfo = true) }
            }
            if (current.value == null) cat.channels.firstOrNull()?.let { if (settings.lastChannelOnStart) play(it) }
            launch {
                if (settings.playlistUpdateOnStart) runCatching { repo.updateAllPlaylists(onlyStale = true) }
                if (settings.epgUpdateOnStart) runCatching { repo.updateAllEpg(onlyStale = true) }
            }
            launch { Reminders.rescheduleAll(getApplication()); RecordingScheduler.rescheduleAll(getApplication()) }
        }
    }

    // ---------------------------------------------------------------- navigation

    val screen: Screen get() = screens.value.last()
    fun open(s: Screen) { overlay.value = Overlay.NONE; screens.value = screens.value + s }
    fun back(): Boolean {
        if (screens.value.size <= 1) return false
        val leaving = screens.value.last()
        screens.value = screens.value.dropLast(1)
        if (leaving is Screen.Settings && leaving.page == "root") rebuildPlayerIfNeeded()
        return true
    }
    fun home() { screens.value = listOf(Screen.Watch) }
    fun replaceTop(s: Screen) { screens.value = screens.value.dropLast(1) + s }

    fun say(text: String) { toast.tryEmit(text) }

    // ---------------------------------------------------------------- parental controls

    private fun unlocked(): Boolean = when (settings.pinUnlockScope) {
        "app_session" -> sessionUnlocked
        "always" -> false
        else -> System.currentTimeMillis() < unlockedUntil
    }

    fun withPin(title: String, required: Boolean, action: () -> Unit) {
        if (!required || settings.pinHash.isEmpty() || unlocked()) { action(); return }
        pin.value = PinRequest(title) { action() }
    }

    fun submitPin(p: String): Boolean {
        val req = pin.value ?: return false
        if (System.currentTimeMillis() < settings.pinLockedUntil) { say("Too many wrong PINs. Try again later."); return false }
        return if (settings.checkPin(p)) {
            settings.pinFailedCount = 0
            sessionUnlocked = true
            unlockedUntil = System.currentTimeMillis() + when (settings.pinUnlockScope) {
                "10m" -> 600_000L; "30m" -> 1_800_000L; "1h" -> 3_600_000L; "2h" -> 7_200_000L; else -> 0L
            }
            pin.value = null
            req.onOk()
            true
        } else {
            settings.pinFailedCount += 1
            if (settings.pinFailedCount >= 5) { settings.pinLockedUntil = System.currentTimeMillis() + 60_000; settings.pinFailedCount = 0; pin.value = null }
            say("Wrong PIN")
            false
        }
    }

    fun settingsNeedPin() = settings.pinRequiredFor == "blocked_settings"

    // ---------------------------------------------------------------- live TV

    fun play(c: Channel, showInfo: Boolean = true) {
        withPin("Enter PIN to watch ${c.name}", c.blocked) { playUnlocked(c, showInfo) }
    }

    private fun playUnlocked(c: Channel, showInfo: Boolean) {
        val was = current.value
        if (was != null && was.uid != c.uid) { tickWatchTime(); previous = was }
        lastWatchTick = System.currentTimeMillis()
        current.value = c
        mode.value = Mode.Live
        preview.value = null
        settings.lastPlaylistId = c.playlistId
        settings.lastChannelKey = c.key
        val ownOffset = c.pref?.audioOffsetMs ?: 0
        audioOffset.us = (if (ownOffset != 0) ownOffset else settings.audioOffsetMs) * 1000L
        displayMode.value = c.pref?.displayMode?.ifEmpty { null } ?: settings.displayMode
        if (showInfo && settings.infoOnSwitch && overlay.value == Overlay.NONE) overlay.value = Overlay.INFO
        retries = 0
        startLive(c)
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            delay(settings.recentDelaySec.coerceAtLeast(0) * 1000L)
            repo.addHistory(c)
        }
        viewModelScope.launch { refreshNowNext() }
    }

    private fun startLive(c: Channel) {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            try {
                if (settings.blackOnSwitch) player.stop()
                val s = resolver.live(c)
                if (settings.useExternalPlayer == "tv" || settings.useExternalPlayer == "both") {
                    effects.emit(Effect.External(s.url, c.name)); return@launch
                }
                playStream(s)
            } catch (e: Exception) {
                error.value = e.message ?: "Can't open the channel"
            }
        }
    }

    private fun playStream(s: Stream, startMs: Long = 0) {
        error.value = null
        player.setMediaSource(StreamResolver.mediaSource(getApplication(), s), startMs)
        player.playbackParameters = PlaybackParameters(if (s.live) 1f else speed.value)
        player.prepare()
        player.playWhenReady = true
    }

    private fun replay() {
        val c = current.value
        when (val m = mode.value) {
            is Mode.Live -> c?.let { startLive(it) }
            is Mode.Catchup -> c?.let { startCatchup(it, positionEpoch().coerceAtLeast(m.streamStart), m.end, m.title, m.progStart, m.progStop) }
            is Mode.Vod -> { player.prepare(); player.play() }
        }
    }

    fun list(): List<Channel> {
        val cat = catalog.value
        return cat.channelsIn(group.value).ifEmpty { cat.channels }
    }

    fun step(delta: Int) {
        val l = list()
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.uid == current.value?.uid }
        val next = l[((if (i < 0) 0 else i + delta) % l.size + l.size) % l.size]
        play(next)
    }

    fun previewStep(delta: Int) {
        val l = list()
        if (l.isEmpty()) return
        val base = preview.value ?: current.value
        val i = l.indexOfFirst { it.uid == base?.uid }
        val c = l[((if (i < 0) 0 else i + delta) % l.size + l.size) % l.size]
        preview.value = c
        overlay.value = Overlay.INFO
        viewModelScope.launch { previewNowNext.value = repo.nowNext(c) }
    }

    fun playPreview() { preview.value?.let { play(it) } }

    fun lastChannel() { previous?.let { play(it) } }

    fun setGroup(ref: GroupRef) {
        group.value = ref
        settings.lastGroup = ref.id
    }

    fun digit(d: Int) {
        numberEntry.value = (numberEntry.value + d).takeLast(5)
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(1800)
            val n = numberEntry.value.toIntOrNull()
            numberEntry.value = ""
            if (n != null) catalog.value.channels.firstOrNull { it.number == n }?.let { play(it) } ?: say("No channel $n")
        }
    }

    suspend fun refreshNowNext() {
        val c = current.value ?: return
        nowNext.value = repo.nowNext(c)
    }

    private fun tickWatchTime() {
        val c = current.value ?: return
        if (!isPlaying.value || mode.value !is Mode.Live) { lastWatchTick = System.currentTimeMillis(); return }
        val now = System.currentTimeMillis()
        val secs = (now - lastWatchTick) / 1000
        lastWatchTick = now
        if (secs in 1..3600) viewModelScope.launch { repo.addWatchTime(c, secs) }
    }

    // ---------------------------------------------------------------- catch-up

    fun nowEpoch() = System.currentTimeMillis() / 1000

    fun positionEpoch(): Long = when (val m = mode.value) {
        is Mode.Catchup -> m.streamStart + player.currentPosition / 1000
        else -> nowEpoch()
    }

    fun playProgramme(c: Channel, p: ProgrammeEntity, fromStart: Boolean = false) {
        val now = nowEpoch()
        when {
            p.start > now -> say("This program hasn't started yet")
            p.stop > now && !fromStart -> { if (current.value?.uid != c.uid || mode.value != Mode.Live) play(c) }
            !c.hasCatchup -> say("No catch-up for this channel")
            p.start < now - c.e.catchupDays * 86400L -> say("This program is no longer available")
            else -> withPin("Enter PIN", c.blocked) {
                if (current.value?.uid != c.uid) { previous = current.value; current.value = c }
                startCatchup(c, p.start, p.stop, p.title, p.start, p.stop)
            }
        }
    }

    private fun startCatchup(c: Channel, from: Long, end: Long, title: String, progStart: Long, progStop: Long) {
        playJob?.cancel()
        mode.value = Mode.Catchup(from, end, title, progStart, progStop)
        playJob = viewModelScope.launch {
            val s = resolver.catchup(c, from, end.coerceAtLeast(from + 60))
            if (s == null) { say("Catch-up isn't available"); mode.value = Mode.Live; startLive(c); return@launch }
            playStream(s)
        }
    }

    /** Seeks in catch-up (or starts it from live) by [deltaSec]. */
    fun seekBy(deltaSec: Long) {
        val c = current.value ?: return
        when (val m = mode.value) {
            is Mode.Vod -> player.seekTo((player.currentPosition + deltaSec * 1000).coerceIn(0, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
            is Mode.Catchup -> {
                val target = positionEpoch() + deltaSec
                val now = nowEpoch()
                if (target >= now - 5) { goLive(); return }
                val offsetMs = (target - m.streamStart) * 1000
                val dur = player.duration
                if (player.isCurrentMediaItemSeekable && dur != C.TIME_UNSET && offsetMs in 0..dur) player.seekTo(offsetMs)
                else startCatchup(c, target.coerceAtLeast(m.progStart - 3600), maxOf(m.end, target + 3600).coerceAtMost(now), m.title, m.progStart, m.progStop)
            }
            is Mode.Live -> {
                if (deltaSec >= 0) return
                if (!c.hasCatchup) { say("No catch-up for this channel"); return }
                val now = nowEpoch()
                val p = nowNext.value.now
                startCatchup(c, now + deltaSec, p?.stop ?: (now + 3600), p?.title ?: c.name, p?.start ?: (now + deltaSec), p?.stop ?: (now + 3600))
            }
        }
    }

    fun restartProgram() {
        val c = current.value ?: return
        when (val m = mode.value) {
            is Mode.Vod -> player.seekTo(0)
            is Mode.Catchup -> startCatchup(c, m.progStart, m.progStop, m.title, m.progStart, m.progStop)
            is Mode.Live -> nowNext.value.now?.let { playProgramme(c, it, fromStart = true) } ?: say("No program information")
        }
    }

    fun goLive() {
        val c = current.value ?: return
        if (mode.value is Mode.Catchup) { mode.value = Mode.Live; startLive(c) }
    }

    fun togglePause() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun stopPlayback() {
        saveVodProgress()
        player.stop()
        mode.value = Mode.Live
    }

    // ---------------------------------------------------------------- VOD

    fun playMovie(v: VodEntity, fromStart: Boolean) {
        viewModelScope.launch {
            val resumeAt = if (fromStart) 0 else repo.progress(v.playlistId, v.key)?.takeIf { it.positionMs < it.durationMs - 60_000 }?.positionMs ?: 0
            playVod(Mode.Vod(v.playlistId, v.key, v.name, url = v.url), resumeAt)
        }
    }

    fun playEpisode(series: VodEntity, episodes: List<XtreamEpisode>, index: Int, fromStart: Boolean) {
        viewModelScope.launch {
            val ep = episodes[index]
            val key = "${series.key}/${ep.id}"
            val resumeAt = if (fromStart) 0 else repo.progress(series.playlistId, key)?.takeIf { it.positionMs < it.durationMs - 60_000 }?.positionMs ?: 0
            playVod(
                Mode.Vod(series.playlistId, key, "${series.name} · S${ep.season} E${ep.number} ${ep.title}".trim(), series.key, episodes, index, ep.url),
                resumeAt,
            )
        }
    }

    private suspend fun playVod(m: Mode.Vod, startMs: Long) {
        saveVodProgress()
        playJob?.cancel()
        val s = resolver.vod(m.playlistId, m.url, m.title)
        if (settings.useExternalPlayer == "vod" || settings.useExternalPlayer == "both") { effects.emit(Effect.External(s.url, m.title)); return }
        mode.value = m
        audioOffset.us = settings.audioOffsetMs * 1000L
        displayMode.value = settings.displayMode
        open(Screen.Watch)
        overlay.value = Overlay.CONTROLS
        playStream(s, startMs)
    }

    fun saveVodProgress() {
        val m = mode.value as? Mode.Vod ?: return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        viewModelScope.launch { repo.saveProgress(m.playlistId, m.key, pos, dur, m.title, m.seriesKey) }
    }

    private fun onEnded() {
        when (val m = mode.value) {
            is Mode.Vod -> {
                saveVodProgress()
                val next = m.index + 1
                if (settings.autoplayNextEpisode && m.queue.isNotEmpty() && next < m.queue.size) {
                    viewModelScope.launch {
                        val series = repo.db.vod().byKey(m.playlistId, m.seriesKey) ?: return@launch
                        playEpisode(series, m.queue, next, fromStart = true)
                    }
                } else leaveVod()
            }
            is Mode.Catchup -> {
                // Continue with the next program, or return to live when caught up.
                val c = current.value ?: return
                if (m.progStop >= nowEpoch() - 30) goLive()
                else viewModelScope.launch {
                    val next = repo.programmes(c, m.progStop, m.progStop + 1).firstOrNull { it.start >= m.progStop - 1 }
                    if (next != null) playProgramme(c, next, fromStart = true) else goLive()
                }
            }
            else -> Unit
        }
    }

    fun leaveVod() {
        saveVodProgress()
        player.stop()
        mode.value = Mode.Live
        if (screens.value.size > 1 && screen == Screen.Watch) screens.value = screens.value.dropLast(1)
        current.value?.let { if (settings.lastChannelOnStart && screen == Screen.Watch) startLive(it) }
    }

    fun setSpeed(v: Float) {
        speed.value = v
        if (mode.value is Mode.Vod) player.playbackParameters = PlaybackParameters(v)
    }

    // ---------------------------------------------------------------- tracks and picture

    data class TrackOption(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

    fun tracks(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += TrackOption(g, i, describe(g.getTrackFormat(i), type), g.isTrackSelected(i))
            }
        }
        return out
    }

    fun selectTrack(type: Int, option: TrackOption?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (option == null) b.setTrackTypeDisabled(type, true)
        else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
        player.trackSelectionParameters = b.build()
        if (type == C.TRACK_TYPE_AUDIO && option != null) {
            val lang = option.group.getTrackFormat(option.index).language.orEmpty()
            current.value?.let { c -> if (lang.isNotEmpty()) viewModelScope.launch { repo.setAudioLanguage(c, lang) } }
        }
    }

    fun autoTrack(type: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, false).build()
    }

    private fun describe(f: Format, type: Int): String {
        val lang = f.language?.let { java.util.Locale(it).displayLanguage.ifEmpty { it } }
        return when (type) {
            C.TRACK_TYPE_VIDEO -> listOfNotNull("${f.width}×${f.height}", f.frameRate.takeIf { it > 0 }?.let { "%.0f fps".format(it) }, codecName(f.sampleMimeType), f.bitrate.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1e6) }).joinToString(" · ")
            C.TRACK_TYPE_AUDIO -> listOfNotNull(f.label ?: lang ?: "Audio", codecName(f.sampleMimeType), channelsText(f.channelCount)).joinToString(" · ")
            else -> listOfNotNull(f.label ?: lang ?: "Subtitles", if (f.roleFlags and C.ROLE_FLAG_CAPTION != 0) "CC" else null).joinToString(" · ")
        }
    }

    private fun channelsText(n: Int) = when (n) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (n > 0) "${n}ch" else null }

    private fun codecName(mime: String?) = when (mime) {
        MimeTypes.VIDEO_H264 -> "H.264"; MimeTypes.VIDEO_H265 -> "HEVC"; MimeTypes.VIDEO_MPEG2 -> "MPEG-2"; MimeTypes.VIDEO_AV1 -> "AV1"; MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_AC3 -> "AC-3"; MimeTypes.AUDIO_E_AC3 -> "E-AC-3"; MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2/MP3"
        MimeTypes.AUDIO_DTS -> "DTS"; MimeTypes.AUDIO_OPUS -> "Opus"; MimeTypes.AUDIO_AC4 -> "AC-4"
        else -> mime?.substringAfter('/')?.uppercase()
    }

    private fun updateMediaInfo() {
        val v = player.videoFormat
        val a = player.audioFormat
        videoFormat.value = v
        val res = v?.let { f ->
            if (settings.resolutionInsteadOfLabels) "${f.width}×${f.height}" else when {
                f.height >= 2000 -> "4K"; f.height >= 1000 -> "FHD"; f.height >= 700 -> "HD"; f.height > 0 -> "SD"; else -> null
            }
        }
        mediaInfo.value = listOfNotNull(
            res, v?.frameRate?.takeIf { it > 0 }?.let { "%.0f fps".format(it) }, codecName(v?.sampleMimeType),
            a?.let { listOfNotNull(codecName(it.sampleMimeType), channelsText(it.channelCount)).joinToString(" ") },
        ).joinToString("  ·  ")
    }

    fun cycleDisplayMode() {
        val modes = listOf("fit", "fill", "zoom", "reduced")
        val next = modes[(modes.indexOf(displayMode.value) + 1) % modes.size]
        displayMode.value = next
        say("Display mode: " + displayModeName(next))
    }

    fun setDisplayMode(m: String, allChannels: Boolean) {
        displayMode.value = m
        if (allChannels) settings.displayMode = m else current.value?.let { c -> viewModelScope.launch { repo.setDisplayMode(c, m) } }
    }

    fun setAudioOffset(ms: Int, allChannels: Boolean) {
        audioOffset.us = ms * 1000L
        if (allChannels) settings.audioOffsetMs = ms else current.value?.let { c -> viewModelScope.launch { repo.setAudioOffset(c, ms) } }
    }

    fun displayModeName(m: String) = when (m) { "fill" -> "Full"; "zoom" -> "Zoom"; "reduced" -> "Reduced"; else -> "Normal" }

    // ---------------------------------------------------------------- favorites, sleep, external

    fun toggleFavorite(c: Channel? = current.value) {
        c ?: return
        viewModelScope.launch {
            repo.toggleFavorite(c)
            say(if (c.favorite) "Removed from Favorites" else "Added to Favorites")
        }
    }

    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { sleepAt.value = 0; say("Sleep timer is off"); return }
        sleepAt.value = System.currentTimeMillis() + minutes * 60_000L
        say("Sleep timer: $minutes min")
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            sleepAt.value = 0
            stopPlayback()
            effects.emit(Effect.Background)
        }
    }

    fun openExternal() {
        val c = current.value ?: return
        viewModelScope.launch {
            val s = when (val m = mode.value) {
                is Mode.Catchup -> resolver.catchup(c, positionEpoch(), m.end) ?: resolver.live(c)
                is Mode.Vod -> resolver.vod(m.playlistId, m.url, m.title)
                else -> resolver.live(c)
            }
            effects.emit(Effect.External(s.url, s.title))
        }
    }

    // ---------------------------------------------------------------- reminders and recordings

    fun addReminder(c: Channel, p: ProgrammeEntity) {
        viewModelScope.launch {
            if (reminders.value.any { it.channelKey == c.key && it.start == p.start }) { say("Reminder already set"); return@launch }
            val r = ReminderEntity(playlistId = c.playlistId, channelKey = c.key, channelName = c.name, title = p.title, start = p.start, stop = p.stop)
            val id = repo.db.activity().insertReminder(r)
            Reminders.schedule(getApplication(), r.copy(id = id))
            say("Reminder added")
        }
    }

    fun deleteReminder(id: Long) {
        viewModelScope.launch { repo.db.activity().deleteReminder(id); Reminders.cancel(getApplication(), id); say("Reminder deleted") }
    }

    fun reminderFor(c: Channel, p: ProgrammeEntity) = reminders.value.firstOrNull { it.playlistId == c.playlistId && it.channelKey == c.key && it.start == p.start }

    fun activeRecording(c: Channel?) = c?.let { ch -> recordings.value.firstOrNull { it.status == RecStatus.RECORDING && it.playlistId == ch.playlistId && it.channelKey == ch.key } }

    fun toggleRecording(c: Channel? = current.value) {
        c ?: return
        val active = activeRecording(c)
        if (active != null) { RecordingScheduler.cancel(getApplication(), active.id); say("Recording stopped"); return }
        viewModelScope.launch {
            val now = nowEpoch()
            val p = repo.nowNext(c).now
            val r = RecordingEntity(
                playlistId = c.playlistId, channelKey = c.key, channelName = c.name,
                title = p?.title ?: c.name, start = now, stop = p?.stop ?: (now + 3600), status = RecStatus.SCHEDULED,
            )
            val id = repo.db.activity().insertRecording(r)
            RecordingService.start(getApplication(), id)
            say(if (p != null) "Recording until the program ends" else "Recording for 1 hour")
        }
    }

    fun scheduleRecording(c: Channel, title: String, start: Long, stop: Long, repeat: String = "") {
        viewModelScope.launch {
            val r = RecordingEntity(playlistId = c.playlistId, channelKey = c.key, channelName = c.name, title = title, start = start, stop = stop, repeat = repeat)
            val id = repo.db.activity().insertRecording(r)
            RecordingScheduler.schedule(getApplication(), r.copy(id = id))
            say("Recording scheduled")
        }
    }

    fun recordingFor(c: Channel, p: ProgrammeEntity) =
        recordings.value.firstOrNull { it.playlistId == c.playlistId && it.channelKey == c.key && it.start <= p.start && it.stop >= p.stop && (it.status == RecStatus.SCHEDULED || it.status == RecStatus.RECORDING) }

    fun cancelRecording(r: RecordingEntity, delete: Boolean) {
        RecordingScheduler.cancel(getApplication(), r.id)
        viewModelScope.launch {
            if (delete) {
                runCatching {
                    if (r.location.startsWith("content://")) androidx.documentfile.provider.DocumentFile.fromSingleUri(getApplication(), android.net.Uri.parse(r.location))?.delete()
                    else if (r.location.isNotEmpty()) java.io.File(r.location).delete()
                }
                repo.db.activity().deleteRecording(r.id)
            } else if (r.status == RecStatus.SCHEDULED) {
                repo.db.activity().upsertRecording(r.copy(status = RecStatus.CANCELLED))
            }
        }
    }

    fun playRecording(r: RecordingEntity) {
        mode.value = Mode.Vod(r.playlistId, "rec:${r.id}", r.title, url = r.location)
        open(Screen.Watch)
        overlay.value = Overlay.CONTROLS
        val uri = if (r.location.startsWith("/")) "file://${r.location}" else r.location
        playStream(Stream(uri, live = false, title = r.title))
    }

    fun watchReminder(r: ReminderEntity) {
        reminderPopup.value = null
        catalog.value.find(r.playlistId, r.channelKey)?.let { home(); play(it) }
    }

    // ---------------------------------------------------------------- player lifecycle

    private var builtWith = playerConfig()

    private fun playerConfig() = listOf(
        settings.bufferSize, settings.videoDecoder, settings.audioDecoder, settings.audioPassthrough, settings.tunneled,
        settings.amlogicFix, settings.autoFrameRate, settings.preferredAudioLanguage, settings.preferredSubtitleLanguage, settings.subtitlesAllChannels,
    ).joinToString("|")

    fun rebuildPlayerIfNeeded() {
        val now = playerConfig()
        if (now == builtWith) return
        builtWith = now
        player.removeListener(listener)
        player.release()
        player = PlayerFactory.create(getApplication(), settings, audioOffset)
        player.addListener(listener)
        playerVersion.value++
        replay()
    }

    fun onStop() {
        if (inPip.value) return
        saveVodProgress()
        tickWatchTime()
        player.pause()
    }

    fun onStart() {
        if (player.playbackState == Player.STATE_IDLE && current.value != null) replay()
        else if (mode.value is Mode.Live && current.value != null) {
            // A live stream paused for a while is stale: jump to live.
            player.seekToDefaultPosition(); player.play()
        }
    }

    private fun errorText(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the stream (${(e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode ?: "error"})"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't connect to the server"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Unsupported stream format"
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "The video can't be decoded on this device (try the software decoder)"
        else -> "Playback error (${e.errorCodeName})"
    }

    override fun onCleared() {
        saveVodProgress()
        tickWatchTime()
        player.release()
        super.onCleared()
    }

    fun groupTitle(ref: GroupRef): String = catalog.value.groups.firstOrNull { it.ref.id == ref.id }?.title ?: when (ref.kind) {
        GroupKind.ALL -> "All channels"
        GroupKind.FAVORITES -> "Favorites"
        GroupKind.HISTORY -> "Recent"
        else -> ref.name
    }
}
