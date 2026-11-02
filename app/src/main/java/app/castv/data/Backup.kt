package app.castv.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupFile(
    val format: String = "castv-backup",
    val version: Int = 1,
    val createdAt: Long = 0,
    /** Setting name → "b:true", "i:3", "l:7", "f:0.5" or "s:text". */
    val settings: Map<String, String> = emptyMap(),
    val playlists: List<PlaylistEntity> = emptyList(),
    val epgSources: List<EpgSourceEntity> = emptyList(),
    val epgAssignments: List<PlaylistEpgEntity> = emptyList(),
    val channelPrefs: List<ChannelPrefEntity> = emptyList(),
    val groupPrefs: List<GroupPrefEntity> = emptyList(),
    val customGroups: List<CustomGroupEntity> = emptyList(),
    val customGroupChannels: List<CustomGroupChannelEntity> = emptyList(),
    val myList: List<MyListEntity> = emptyList(),
    val vodProgress: List<VodProgressEntity> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(),
    val recordings: List<RecordingEntity> = emptyList(),
)

/** Saves and restores playlists, guides, every user choice and all settings as one JSON file. */
class Backup(private val context: Context, private val db: AppDb, private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    suspend fun create(): String = withContext(Dispatchers.IO) {
        val tagged = settings.exportAll().mapNotNull { (k, v) ->
            when (v) {
                is Boolean -> k to "b:$v"
                is Int -> k to "i:$v"
                is Long -> k to "l:$v"
                is Float -> k to "f:$v"
                is String -> k to "s:$v"
                else -> null
            }
        }.toMap()
        val file = BackupFile(
            createdAt = System.currentTimeMillis(),
            settings = tagged,
            playlists = db.playlists().all().map { it.copy(lastUpdated = 0, channelCount = 0, movieCount = 0, seriesCount = 0) },
            epgSources = db.epg().sources().map { it.copy(lastUpdated = 0, channelCount = 0, programmeCount = 0) },
            epgAssignments = db.epg().assignments(),
            channelPrefs = db.prefs().channelPrefs(),
            groupPrefs = db.prefs().groupPrefs(),
            customGroups = db.prefs().customGroups(),
            customGroupChannels = db.prefs().customGroupChannels(),
            myList = db.vod().myList(),
            vodProgress = db.vod().allProgress(),
            reminders = db.activity().reminders(),
            recordings = db.activity().recordings(),
        )
        json.encodeToString(BackupFile.serializer(), file)
    }

    suspend fun writeTo(uri: Uri) = withContext(Dispatchers.IO) {
        val text = create()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("Can't write the file")
    }

    suspend fun readFrom(source: String): BackupFile = withContext(Dispatchers.IO) {
        val text = Http.getText(context, source)
        val file = json.decodeFromString(BackupFile.serializer(), text)
        if (file.format != "castv-backup") error("This is not a CasTV backup")
        file
    }

    /** Replaces everything with the backup. The caller restarts the app afterwards. */
    suspend fun restore(file: BackupFile) = withContext(Dispatchers.IO) {
        db.clearAllTables()
        db.withTransaction {
            file.playlists.forEach { db.playlists().upsert(it) }
            file.epgSources.forEach { db.epg().upsertSource(it) }
            file.epgAssignments.forEach { db.epg().assign(it) }
            db.prefs().upsertChannelPrefs(file.channelPrefs)
            db.prefs().upsertGroupPrefs(file.groupPrefs)
            file.customGroups.forEach { db.prefs().upsertCustomGroup(it) }
            db.prefs().upsertCustomGroupChannels(file.customGroupChannels)
            file.myList.forEach { db.vod().addToMyList(it) }
            file.vodProgress.forEach { db.vod().saveProgress(it) }
            file.reminders.forEach { db.activity().insertReminder(it) }
            file.recordings.filter { it.status == RecStatus.SCHEDULED || it.status == RecStatus.DONE }.forEach { db.activity().upsertRecording(it) }
        }
        settings.importAll(file.settings.mapValues { (_, v) ->
            val body = v.substring(2)
            when (v.take(2)) {
                "b:" -> body.toBoolean()
                "i:" -> body.toIntOrNull()
                "l:" -> body.toLongOrNull()
                "f:" -> body.toFloatOrNull()
                else -> body
            }
        })
    }
}
