package app.castv.data

import android.content.Context
import android.content.SharedPreferences
import app.castv.core.RemoteAction
import app.castv.core.RemoteButton
import app.castv.core.RemoteDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Every user setting, stored in SharedPreferences. [version] changes whenever any setting changes. */
class Settings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("castv_settings", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _version.value++ }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun bool(def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(property.name, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = prefs.edit().putBoolean(property.name, value).apply()
    }

    private fun int(def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getInt(property.name, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = prefs.edit().putInt(property.name, value).apply()
    }

    private fun long(def: Long) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getLong(property.name, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) = prefs.edit().putLong(property.name, value).apply()
    }

    private fun str(def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getString(property.name, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = prefs.edit().putString(property.name, value).apply()
    }

    // ---- General
    var termsAccepted by bool(false)
    var autostartOnBoot by bool(false)
    var autostartOnWake by bool(false)
    var lastChannelOnStart by bool(true)
    var confirmExit by bool(true)
    var lastPlaylistId by long(0)
    var lastChannelKey by str("")
    var lastGroup by str("")
    var namePrefixes by str("")
    var nameSuffixes by str("")
    var channelSort by str("playlist")
    var groupSort by str("playlist")
    var playlistSort by str("manual")
    var showNewGroups by bool(true)
    var recentChannelCount by int(10)
    var recentDelaySec by int(5)
    var historyDays by int(7)
    var historyDelaySec by int(30)
    var playlistUpdateOnStart by bool(true)
    var playlistUpdateHours by int(24)
    var defaultUserAgent by str("")
    var udpProxy by str("")
    var showAllChannelsCategory by bool(true)
    var showFavoritesCategory by bool(true)
    var showAllPlaylistsCategory by bool(true)
    var groupByPlaylistInAll by bool(false)
    /** "all" or "favorites": what opens when a playlist name is picked in the groups list. */
    var channelsCategory by str("all")

    // ---- Appearance
    var accent by str("blue")
    var uiTransparency by int(20)
    var fontSize by str("medium")
    var showClock by bool(true)
    var clockPosition by str("top_right")
    var clockSize by str("medium")
    var showDateOnClock by bool(false)
    var showChannelNumbers by bool(true)
    var showChannelLogos by bool(true)
    var showChannelNames by bool(true)
    var twoLineChannelNames by bool(false)
    var twoLineProgramTitles by bool(false)
    var showCurrentPrograms by bool(true)
    var showProgramsInList by bool(true)
    var dimPastPrograms by bool(true)
    var highlightCurrentPrograms by bool(true)
    var highlightCurrentChannel by bool(true)
    var showCatchupIcon by bool(true)
    var currentTimeFullHeight by bool(false)
    var animatedScrolling by bool(true)
    var panelsTimeoutSec by int(6)
    var infoOnSwitch by bool(true)
    var infoAtBottomOnSwitch by bool(true)
    var descriptionOnSwitchOnly by bool(false)
    var showProgramDescription by bool(true)
    var showMediaProperties by bool(true)
    var resolutionInsteadOfLabels by bool(false)
    var showPlaylistAndGroup by bool(false)
    var cardStyle by str("poster")
    var logoPriority by str("playlist")
    var logosFolder by str("")
    var inexactLogoMatching by bool(true)
    var guideStayOnSwitch by bool(false)
    var listStayOnSwitch by bool(false)
    var guideHoursVisible by int(2)
    var previewModeDefault by bool(true)
    var autoplayInPreview by bool(true)
    var historyButton by bool(true)
    var guideButton by bool(true)

    // ---- Playback
    var bufferSize by str("medium")
    var videoDecoder by str("hardware")
    var audioDecoder by str("hardware")
    var audioPassthrough by bool(false)
    var autoFrameRate by bool(false)
    var afrOnly5060 by bool(false)
    var switchResolution by bool(false)
    var tunneled by bool(false)
    var amlogicFix by bool(false)
    var preferSurround by bool(false)
    var subtitlesAllChannels by bool(false)
    var blackOnSwitch by bool(true)
    var switchDelaySec by int(0)
    var useExternalPlayer by str("off")
    var displayMode by str("fit")
    var audioOffsetMs by int(0)
    var rwStepSec by int(10)
    var ffStepSec by int(30)
    var timebarBackSec by int(60)
    var timebarFwdSec by int(60)
    var seekLeftRightCatchup by bool(true)
    var seekUpDownCatchup by bool(false)
    var seekRwFfCatchup by bool(true)
    var rewindLiveWithCatchup by str("rw")
    var autoplayNextEpisode by bool(true)
    var pipOnHome by bool(false)
    var preferredAudioLanguage by str("")
    var preferredSubtitleLanguage by str("")
    var speedIndex by int(3)
    var maxReconnects by int(5)
    var confirmChannelSwitch by bool(false)

    // ---- EPG
    var epgUpdateOnStart by bool(true)
    var epgUpdateOnPlaylistChange by bool(true)
    var epgUpdateHours by int(24)
    var epgPastDays by int(2)
    var epgFutureDays by int(7)
    var epgStoreDescriptions by bool(true)
    var epgOffsetSec by long(0)
    var epgFullScan by bool(true)

    // ---- Parental controls
    var pinHash by str("")
    /** "blocked" (blocked channels/groups only) or "blocked_settings" (also settings). */
    var pinRequiredFor by str("blocked")
    /** "always", "app_session", "10m", "30m", "1h", "2h". */
    var pinUnlockScope by str("app_session")
    var pinFailedCount by int(0)
    var pinLockedUntil by long(0)

    // ---- Reminders and recordings
    var remindBeforeMin by int(1)
    var reminderPopupSec by int(20)
    var reminderDefaultAction by str("watch")
    var reminderWakeUp by bool(true)
    var recordingsFolder by str("")
    var recordBeforeMin by int(0)
    var recordAfterMin by int(0)

    // ---- Search
    var searchPreferVoice by bool(false)
    var searchShowHistory by bool(true)
    var searchFavoritesFirst by bool(true)
    var searchShowPastWithoutCatchup by bool(false)
    var searchStayOnSwitch by bool(false)
    var searchHistory by str("")

    // ---- VOD
    var groupAllMoviesByCategory by bool(true)
    var groupAllShowsByCategory by bool(true)
    var vodSort by str("added")

    fun remote(button: RemoteButton): RemoteAction {
        val v = prefs.getString("remote_${button.name}", null) ?: return RemoteDefaults.map[button] ?: RemoteAction.NONE
        return runCatching { RemoteAction.valueOf(v) }.getOrDefault(RemoteAction.NONE)
    }

    fun setRemote(button: RemoteButton, action: RemoteAction) = prefs.edit().putString("remote_${button.name}", action.name).apply()

    fun resetRemote() = prefs.edit().apply { RemoteButton.entries.forEach { remove("remote_${it.name}") } }.apply()

    fun checkPin(pin: String): Boolean = pinHash.isNotEmpty() && pinHash == hash(pin)
    fun setPin(pin: String) { pinHash = if (pin.isEmpty()) "" else hash(pin) }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("castv:$pin").toByteArray()).joinToString("") { "%02x".format(it) }

    fun searchHistoryList(): List<String> = searchHistory.split('\n').filter { it.isNotBlank() }
    fun addSearchHistory(q: String) {
        searchHistory = (listOf(q) + searchHistoryList().filter { it != q }).take(20).joinToString("\n")
    }

    /** Everything, for a backup. */
    fun exportAll(): Map<String, Any?> = prefs.all.filterKeys { it != "pinFailedCount" && it != "pinLockedUntil" }

    fun importAll(values: Map<String, Any?>) {
        val e = prefs.edit().clear()
        for ((k, v) in values) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
    }
}
