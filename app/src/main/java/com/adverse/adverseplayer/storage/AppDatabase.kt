package com.adverse.adverseplayer.storage

import android.content.Context
import androidx.room.*

/** One row per TimeSlot the device currently knows about. `localPath` is
 *  null until the file has actually finished downloading and been verified. */
@Entity(tableName = "cached_playlist_item")
data class CachedPlaylistItem(
    @PrimaryKey val timeSlotId: String,
    val mediaId: String,
    val mediaUrl: String,
    val contentHash: String,
    val mediaTitle: String,
    val campaignName: String,
    val advertiser: String,
    val playOrder: Int,
    val scheduledSecondsOfDay: Int, // seconds since midnight — what actually drives playback timing now
    val durationSeconds: Int,
    val localPath: String?,
    val downloadedAt: Long?
)

/** Queued proof-of-play. Never deleted until the server confirms receipt —
 *  if the app crashes mid-sync we just resend on the next cycle. */
@Entity(tableName = "playback_log_queue")
data class PlaybackLogQueueItem(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val mediaId: String,
    val timeSlotId: String?,
    val startedAt: String,       // ISO-8601 — also the server-side idempotency key
    val durationSeconds: Int,
    val completed: Boolean,
    val synced: Boolean = false
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM cached_playlist_item ORDER BY scheduledSecondsOfDay ASC")
    suspend fun getAll(): List<CachedPlaylistItem>

    @Query("SELECT * FROM cached_playlist_item WHERE localPath IS NOT NULL ORDER BY scheduledSecondsOfDay ASC")
    suspend fun getPlayable(): List<CachedPlaylistItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CachedPlaylistItem)

    @Query("DELETE FROM cached_playlist_item WHERE timeSlotId NOT IN (:keepIds)")
    suspend fun deleteNotIn(keepIds: List<String>)

    @Query("SELECT * FROM cached_playlist_item WHERE timeSlotId = :id LIMIT 1")
    suspend fun findById(id: String): CachedPlaylistItem?
}

@Dao
interface PlaybackLogDao {
    @Insert
    suspend fun enqueue(item: PlaybackLogQueueItem)

    @Query("SELECT * FROM playback_log_queue WHERE synced = 0 ORDER BY localId ASC LIMIT :limit")
    suspend fun getUnsyncedBatch(limit: Int = 500): List<PlaybackLogQueueItem>

    @Query("UPDATE playback_log_queue SET synced = 1 WHERE localId IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    // Bound retention so a week-long outage can't fill the disk. Old *synced*
    // rows are safe to prune; unsynced rows are never touched by this.
    @Query("DELETE FROM playback_log_queue WHERE synced = 1 AND localId NOT IN (SELECT localId FROM playback_log_queue WHERE synced = 1 ORDER BY localId DESC LIMIT 1000)")
    suspend fun pruneSyncedOverflow()
}

@Database(
    entities = [CachedPlaylistItem::class, PlaybackLogQueueItem::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun playbackLogDao(): PlaybackLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "adverse_player.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
