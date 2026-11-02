package app.castv.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

object PlaylistType {
    const val M3U = "m3u"
    const val XTREAM = "xtream"
    const val STALKER = "stalker"
}

@Serializable
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    /** M3U address (or content:// for a local file), Xtream server or Stalker portal. */
    val url: String,
    val username: String = "",
    val password: String = "",
    val mac: String = "",
    val serial: String = "",
    val deviceId: String = "",
    val userAgent: String = "",
    /** Xtream stream format: "ts" or "m3u8". */
    val outputFormat: String = "ts",
    val enabled: Boolean = true,
    val vodOnly: Boolean = false,
    val includeVod: Boolean = true,
    val position: Int = 0,
    val lastUpdated: Long = 0,
    val lastError: String = "",
    val updateIntervalHours: Int = -1,
    /** "playlist", "epg", "folder" or "" for the global setting. */
    val logoPriority: String = "",
    val groupSort: String = "",
    val expiresAt: Long = 0,
    val maxConnections: Int = 0,
    val channelCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    /** Playlist-wide catch-up values from the #EXTM3U line. */
    val catchupType: String = "",
    val catchupDays: Int = 0,
    val catchupSource: String = "",
)

@Entity(
    tableName = "channels",
    indices = [Index("playlistId"), Index("playlistId", "key"), Index("epgChannelId")],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** Stable across playlist updates; user choices (favorites, names, blocking) are stored against it. */
    val key: String,
    val name: String,
    val url: String,
    val groupName: String,
    val logo: String = "",
    val tvgId: String = "",
    val tvgName: String = "",
    val number: Int = 0,
    val playlistOrder: Int = 0,
    val catchupType: String = "",
    val catchupDays: Int = 0,
    val catchupSource: String = "",
    val tvgShiftSeconds: Long = 0,
    val userAgent: String = "",
    val referrer: String = "",
    val drmType: String = "",
    val drmKey: String = "",
    val streamId: String = "",
    val addedAt: Long = 0,
    val epgSourceId: Long = 0,
    val epgChannelId: String = "",
)

@Serializable
@Entity(tableName = "channel_prefs", primaryKeys = ["playlistId", "key"])
data class ChannelPrefEntity(
    val playlistId: Long,
    val key: String,
    val customName: String = "",
    val hidden: Boolean = false,
    val blocked: Boolean = false,
    /** Position in Favorites, or -1 when not a favorite. */
    val favoritePosition: Int = -1,
    /** Manual position inside its group, or -1. */
    val position: Int = -1,
    /** "sourceId|xmltvId" chosen by hand, or empty for automatic matching. */
    val epgOverride: String = "",
    val displayMode: String = "",
    val audioOffsetMs: Int = 0,
    val audioLanguage: String = "",
    val watchSeconds: Long = 0,
    val lastWatched: Long = 0,
)

@Serializable
@Entity(tableName = "group_prefs", primaryKeys = ["playlistId", "name"])
data class GroupPrefEntity(
    val playlistId: Long,
    val name: String,
    val customName: String = "",
    val hidden: Boolean = false,
    val blocked: Boolean = false,
    val position: Int = -1,
    /** Channel sorting override for this group: "", "playlist", "name", "number", "watch", "added", "manual". */
    val sort: String = "",
    val seen: Boolean = true,
)

@Serializable
@Entity(tableName = "custom_groups")
data class CustomGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
)

@Serializable
@Entity(tableName = "custom_group_channels", primaryKeys = ["groupId", "playlistId", "key"])
data class CustomGroupChannelEntity(
    val groupId: Long,
    val playlistId: Long,
    val key: String,
    val position: Int = 0,
)

@Entity(tableName = "vod", indices = [Index("playlistId"), Index("playlistId", "kind")])
data class VodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** "movie" or "series". */
    val kind: String,
    val key: String,
    val name: String,
    val category: String,
    val poster: String = "",
    val url: String = "",
    val streamId: String = "",
    val extension: String = "",
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val year: String = "",
    val rating: Double = 0.0,
    val durationSecs: Long = 0,
    val trailer: String = "",
    val backdrop: String = "",
    val addedAt: Long = 0,
    val playlistOrder: Int = 0,
)

@Serializable
@Entity(tableName = "vod_progress", primaryKeys = ["playlistId", "key"])
data class VodProgressEntity(
    val playlistId: Long,
    /** movie key, or "seriesKey/episodeId" for an episode. */
    val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val title: String = "",
    val seriesKey: String = "",
)

@Serializable
@Entity(tableName = "my_list", primaryKeys = ["playlistId", "key"])
data class MyListEntity(
    val playlistId: Long,
    val key: String,
    /** "movie", "series" or "program". */
    val kind: String,
    val title: String,
    val addedAt: Long,
    val image: String = "",
)

@Serializable
@Entity(tableName = "epg_sources")
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val lastUpdated: Long = 0,
    val lastError: String = "",
    val channelCount: Int = 0,
    val programmeCount: Int = 0,
    val shiftSeconds: Long = 0,
    /** Created automatically for a playlist (url-tvg or Xtream xmltv.php). */
    val autoForPlaylist: Long = 0,
)

@Serializable
@Entity(tableName = "playlist_epg", primaryKeys = ["playlistId", "sourceId"])
data class PlaylistEpgEntity(
    val playlistId: Long,
    val sourceId: Long,
    val priority: Int = 0,
)

@Entity(tableName = "epg_channels", primaryKeys = ["sourceId", "xmltvId"], indices = [Index("matchKey")])
data class EpgChannelEntity(
    val sourceId: Long,
    val xmltvId: String,
    val displayName: String,
    val icon: String = "",
    val matchKey: String,
)

@Entity(
    tableName = "programmes",
    indices = [Index("sourceId", "channelId", "start"), Index("title")],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val subTitle: String = "",
    val description: String = "",
    val category: String = "",
    val icon: String = "",
    val episode: String = "",
    val rating: String = "",
    val year: String = "",
)

@Entity(tableName = "history", primaryKeys = ["playlistId", "key"])
data class HistoryEntity(
    val playlistId: Long,
    val key: String,
    val watchedAt: Long,
)

@Serializable
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelKey: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
)

object RecStatus {
    const val SCHEDULED = "scheduled"
    const val RECORDING = "recording"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

@Serializable
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelKey: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
    val status: String = RecStatus.SCHEDULED,
    /** file path or content:// address of the recorded file. */
    val location: String = "",
    val sizeBytes: Long = 0,
    val error: String = "",
    /** "", "daily", "weekly". */
    val repeat: String = "",
)

data class GroupCount(val groupName: String, val count: Int)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY position, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY position, id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Upsert
    suspend fun upsert(p: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId IN (SELECT id FROM playlists WHERE enabled = 1) ORDER BY playlistId, playlistOrder")
    fun observeEnabled(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY playlistOrder")
    suspend fun forPlaylist(playlistId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun get(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND `key` = :key LIMIT 1")
    suspend fun byKey(playlistId: Long, key: String): ChannelEntity?

    @Insert
    suspend fun insertAll(list: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("UPDATE channels SET epgSourceId = :sourceId, epgChannelId = :channelId WHERE id = :id")
    suspend fun setEpg(id: Long, sourceId: Long, channelId: String)

    @Query("SELECT groupName, COUNT(*) AS count FROM channels WHERE playlistId = :playlistId GROUP BY groupName")
    suspend fun groupCounts(playlistId: Long): List<GroupCount>
}

@Dao
interface PrefDao {
    @Query("SELECT * FROM channel_prefs")
    fun observeChannelPrefs(): Flow<List<ChannelPrefEntity>>

    @Query("SELECT * FROM channel_prefs")
    suspend fun channelPrefs(): List<ChannelPrefEntity>

    @Query("SELECT * FROM channel_prefs WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun channelPref(playlistId: Long, key: String): ChannelPrefEntity?

    @Upsert
    suspend fun upsertChannelPref(p: ChannelPrefEntity)

    @Upsert
    suspend fun upsertChannelPrefs(p: List<ChannelPrefEntity>)

    @Query("UPDATE channel_prefs SET watchSeconds = 0")
    suspend fun resetWatchTime()

    @Query("UPDATE channel_prefs SET favoritePosition = -1")
    suspend fun clearFavorites()

    @Query("SELECT * FROM group_prefs")
    fun observeGroupPrefs(): Flow<List<GroupPrefEntity>>

    @Query("SELECT * FROM group_prefs")
    suspend fun groupPrefs(): List<GroupPrefEntity>

    @Upsert
    suspend fun upsertGroupPref(p: GroupPrefEntity)

    @Upsert
    suspend fun upsertGroupPrefs(p: List<GroupPrefEntity>)

    @Query("DELETE FROM channel_prefs WHERE playlistId = :playlistId")
    suspend fun deleteChannelPrefs(playlistId: Long)

    @Query("DELETE FROM group_prefs WHERE playlistId = :playlistId")
    suspend fun deleteGroupPrefs(playlistId: Long)

    @Query("SELECT * FROM custom_groups ORDER BY position, id")
    fun observeCustomGroups(): Flow<List<CustomGroupEntity>>

    @Query("SELECT * FROM custom_groups ORDER BY position, id")
    suspend fun customGroups(): List<CustomGroupEntity>

    @Insert
    suspend fun insertCustomGroup(g: CustomGroupEntity): Long

    @Upsert
    suspend fun upsertCustomGroup(g: CustomGroupEntity)

    @Query("DELETE FROM custom_groups WHERE id = :id")
    suspend fun deleteCustomGroup(id: Long)

    @Query("DELETE FROM custom_group_channels WHERE groupId = :id")
    suspend fun clearCustomGroup(id: Long)

    @Query("SELECT * FROM custom_group_channels ORDER BY position")
    fun observeCustomGroupChannels(): Flow<List<CustomGroupChannelEntity>>

    @Query("SELECT * FROM custom_group_channels ORDER BY position")
    suspend fun customGroupChannels(): List<CustomGroupChannelEntity>

    @Upsert
    suspend fun upsertCustomGroupChannels(list: List<CustomGroupChannelEntity>)

    @Query("DELETE FROM custom_group_channels WHERE groupId = :groupId AND playlistId = :playlistId AND `key` = :key")
    suspend fun removeFromCustomGroup(groupId: Long, playlistId: Long, key: String)
}

@Dao
interface VodDao {
    @Query("SELECT * FROM vod WHERE kind = :kind AND playlistId IN (SELECT id FROM playlists WHERE enabled = 1) ORDER BY playlistId, playlistOrder")
    fun observe(kind: String): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod WHERE id = :id")
    suspend fun get(id: Long): VodEntity?

    @Query("SELECT * FROM vod WHERE playlistId = :playlistId AND `key` = :key LIMIT 1")
    suspend fun byKey(playlistId: Long, key: String): VodEntity?

    @Query("SELECT * FROM vod WHERE name LIKE '%' || :q || '%' AND playlistId IN (SELECT id FROM playlists WHERE enabled = 1) LIMIT 200")
    suspend fun search(q: String): List<VodEntity>

    @Insert
    suspend fun insertAll(list: List<VodEntity>)

    @Upsert
    suspend fun upsert(v: VodEntity)

    @Query("DELETE FROM vod WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT * FROM vod_progress ORDER BY updatedAt DESC")
    fun observeProgress(): Flow<List<VodProgressEntity>>

    @Query("SELECT * FROM vod_progress")
    suspend fun allProgress(): List<VodProgressEntity>

    @Query("SELECT * FROM vod_progress WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun progress(playlistId: Long, key: String): VodProgressEntity?

    @Upsert
    suspend fun saveProgress(p: VodProgressEntity)

    @Query("DELETE FROM vod_progress WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun deleteProgress(playlistId: Long, key: String)

    @Query("SELECT * FROM my_list ORDER BY addedAt DESC")
    fun observeMyList(): Flow<List<MyListEntity>>

    @Query("SELECT * FROM my_list")
    suspend fun myList(): List<MyListEntity>

    @Upsert
    suspend fun addToMyList(e: MyListEntity)

    @Query("DELETE FROM my_list WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun removeFromMyList(playlistId: Long, key: String)

    @Query("DELETE FROM my_list")
    suspend fun clearMyList()
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_sources ORDER BY priority, id")
    fun observeSources(): Flow<List<EpgSourceEntity>>

    @Query("SELECT * FROM epg_sources ORDER BY priority, id")
    suspend fun sources(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_sources WHERE id = :id")
    suspend fun source(id: Long): EpgSourceEntity?

    @Query("SELECT * FROM epg_sources WHERE url = :url LIMIT 1")
    suspend fun sourceByUrl(url: String): EpgSourceEntity?

    @Insert
    suspend fun insertSource(s: EpgSourceEntity): Long

    @Upsert
    suspend fun upsertSource(s: EpgSourceEntity)

    @Query("DELETE FROM epg_sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query("SELECT * FROM playlist_epg ORDER BY priority")
    fun observeAssignments(): Flow<List<PlaylistEpgEntity>>

    @Query("SELECT * FROM playlist_epg ORDER BY priority")
    suspend fun assignments(): List<PlaylistEpgEntity>

    @Query("SELECT * FROM playlist_epg WHERE playlistId = :playlistId ORDER BY priority")
    suspend fun assignmentsFor(playlistId: Long): List<PlaylistEpgEntity>

    @Upsert
    suspend fun assign(a: PlaylistEpgEntity)

    @Query("DELETE FROM playlist_epg WHERE playlistId = :playlistId AND sourceId = :sourceId")
    suspend fun unassign(playlistId: Long, sourceId: Long)

    @Query("DELETE FROM playlist_epg WHERE playlistId = :playlistId")
    suspend fun unassignPlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_epg WHERE sourceId = :sourceId")
    suspend fun unassignSource(sourceId: Long)

    @Query("SELECT * FROM epg_channels WHERE sourceId = :sourceId ORDER BY displayName")
    suspend fun channels(sourceId: Long): List<EpgChannelEntity>

    @Query("SELECT * FROM epg_channels WHERE sourceId IN (:sourceIds)")
    suspend fun channelsFor(sourceIds: List<Long>): List<EpgChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(list: List<EpgChannelEntity>)

    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId")
    suspend fun deleteChannels(sourceId: Long)

    @Insert
    suspend fun insertProgrammes(list: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE sourceId = :sourceId")
    suspend fun deleteProgrammes(sourceId: Long)

    @Query("DELETE FROM programmes")
    suspend fun deleteAllProgrammes()

    @Query("DELETE FROM epg_channels")
    suspend fun deleteAllChannels()

    @Query("SELECT * FROM programmes WHERE sourceId = :sourceId AND channelId = :channelId AND stop > :from AND start < :to ORDER BY start")
    suspend fun programmes(sourceId: Long, channelId: String, from: Long, to: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE sourceId = :sourceId AND channelId = :channelId AND start <= :at AND stop > :at LIMIT 1")
    suspend fun programmeAt(sourceId: Long, channelId: String, at: Long): ProgrammeEntity?

    @Query("SELECT * FROM programmes WHERE title LIKE '%' || :q || '%' AND stop > :from ORDER BY start LIMIT 300")
    suspend fun search(q: String, from: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE id = :id")
    suspend fun programme(id: Long): ProgrammeEntity?
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM history ORDER BY watchedAt DESC")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Upsert
    suspend fun addHistory(h: HistoryEntity)

    @Query("DELETE FROM history WHERE playlistId = :playlistId AND `key` = :key")
    suspend fun removeHistory(playlistId: Long, key: String)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("DELETE FROM history WHERE watchedAt < :before")
    suspend fun trimHistory(before: Long)

    @Query("SELECT * FROM reminders ORDER BY start")
    fun observeReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders ORDER BY start")
    suspend fun reminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun reminder(id: Long): ReminderEntity?

    @Insert
    suspend fun insertReminder(r: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("DELETE FROM reminders WHERE stop < :before")
    suspend fun trimReminders(before: Long)

    @Query("SELECT * FROM recordings ORDER BY start DESC")
    fun observeRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings ORDER BY start")
    suspend fun recordings(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun recording(id: Long): RecordingEntity?

    @Insert
    suspend fun insertRecording(r: RecordingEntity): Long

    @Upsert
    suspend fun upsertRecording(r: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecording(id: Long)
}

@Database(
    entities = [
        PlaylistEntity::class, ChannelEntity::class, ChannelPrefEntity::class, GroupPrefEntity::class,
        CustomGroupEntity::class, CustomGroupChannelEntity::class, VodEntity::class, VodProgressEntity::class,
        MyListEntity::class, EpgSourceEntity::class, PlaylistEpgEntity::class, EpgChannelEntity::class,
        ProgrammeEntity::class, HistoryEntity::class, ReminderEntity::class, RecordingEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun prefs(): PrefDao
    abstract fun vod(): VodDao
    abstract fun epg(): EpgDao
    abstract fun activity(): ActivityDao

    companion object {
        const val NAME = "castv.db"
        fun create(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, NAME)
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
