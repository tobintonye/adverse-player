package com.adverse.adverseplayer.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.adverse.adverseplayer.R
import com.adverse.adverseplayer.network.AdverseApiClient
import com.adverse.adverseplayer.network.PlaybackLogEntry
import com.adverse.adverseplayer.network.TimeSlotDto
import com.adverse.adverseplayer.network.UnauthorizedException
import com.adverse.adverseplayer.storage.AppDatabase
import com.adverse.adverseplayer.storage.CachedPlaylistItem
import com.adverse.adverseplayer.storage.MediaCache
import com.adverse.adverseplayer.storage.PlaybackLogQueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import com.adverse.adverseplayer.network.HeartbeatRequest
import com.adverse.adverseplayer.network.ScheduleResult
import com.adverse.adverseplayer.storage.SecurePrefs

/* What the UI(pairing-code vs. player screen) reacts to */
sealed class DeviceState {
    object Unpaired : DeviceState()
    data class ShowPairingCode(val code: String) : DeviceState()
    data class Playing(val items: List<CachedPlaylistItem>) : DeviceState()
}

// This is SyncService — the background engine that keeps
// the Android TV player running unattended, forever, without a person touching it.
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SecurePrefs
    private lateinit var db: AppDatabase
    private lateinit var mediaCache: MediaCache
    private val api = AdverseApiClient()

    companion object {
        private const val CHANNEL_ID = "adverse_sync"
        private const val NOTIF_ID = 1
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val SCHEDULE_PULL_EVERY_N_HEARTBEATS = 10L // ~5 min at 30s cadence
        private const val PAIRING_POLL_INTERVAL_MS = 10_000L

        val state: MutableStateFlow<DeviceState> = MutableStateFlow(DeviceState.Unpaired)

        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Called from the player UI (not bound to this service — it's simpler
         * to just write straight to Room) whenever a piece of content finishes
         * playing, to queue proof-of-play for the next sync cycle to flush.
         */
        fun logPlayback(context: Context, item: CachedPlaylistItem, completed: Boolean) {
            CoroutineScope(Dispatchers.IO).launch {
                AppDatabase.getInstance(context).playbackLogDao().enqueue(
                    PlaybackLogQueueItem(
                        mediaId = item.mediaId,
                        timeSlotId = item.timeSlotId,
                        startedAt = Instant.now().toString(),
                        durationSeconds = item.durationSeconds,
                        completed = completed
                    )
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = SecurePrefs(this)
        db = AppDatabase.getInstance(this)
        mediaCache = MediaCache(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        scope.launch { runForever() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun CoroutineScope.runForever() {
        var heartbeatCount = 0L
        while (isActive) {
            try {
                if (!prefs.isPaired) {
                    runUnpairedFlow()
                } else {
                    runPairedCycle(heartbeatCount)
                    heartbeatCount++
                }
            } catch (e: UnauthorizedException) {
                prefs.clearPairing()
                state.value = DeviceState.Unpaired
            } catch (e: Exception) {
                // Network hiccup or similar — never crash the loop.
            }
            delay(if (prefs.isPaired) HEARTBEAT_INTERVAL_MS else PAIRING_POLL_INTERVAL_MS)
        }
    }

    /** Register (idempotent) then poll pairing-status until the admin pairs
     *  it from the dashboard and the server hands back a token. */
    @Suppress("HardwareIds") // ANDROID_ID identifies this physical player box for
    // pairing with the backend — not used for user tracking.
    private suspend fun runUnpairedFlow() {
        val deviceUid = prefs.deviceUid ?: Settings.Secure.getString(
            contentResolver, Settings.Secure.ANDROID_ID
        ).also { prefs.deviceUid = it }

        if (prefs.pairingCode == null) {
            api.register(deviceUid).onSuccess { reg ->
                prefs.pairingCode = reg.pairing_code
                state.value = DeviceState.ShowPairingCode(reg.pairing_code)
            }
            return
        }

        state.value = DeviceState.ShowPairingCode(prefs.pairingCode!!)

        api.pairingStatus(deviceUid).onSuccess { status ->
            if (status.is_paired && status.auth_token != null) {
                prefs.authToken = status.auth_token
                updateNotification("Paired. Syncing schedule…")
            }
        }
    }

    private suspend fun runPairedCycle(heartbeatCount: Long) {
        val token = prefs.authToken ?: return

        api.heartbeat(
            token,
            HeartbeatRequest(free_storage_mb = availableStorageMb())
        )

        if (heartbeatCount % SCHEDULE_PULL_EVERY_N_HEARTBEATS == 0L) {
            pullSchedule(token)
        }

        flushPlaybackLogs(token)

        val playable = db.playlistDao().getPlayable()
        state.value = DeviceState.Playing(playable)
        updateNotification("Playing ${playable.size} item(s)")
    }

    private suspend fun pullSchedule(token: String) {
        when (val result = api.schedule(token, prefs.scheduleLastModified)) {
            is ScheduleResult.NotModified -> Unit // nothing changed, cache stands
            is ScheduleResult.Error -> Unit         // stay on cached playlist
            is ScheduleResult.Updated -> {
                prefs.scheduleLastModified = result.lastModified
                syncPlaylistToCache(result.schedule.schedule)
            }
        }
    }

    private suspend fun syncPlaylistToCache(slots: List<TimeSlotDto>) {
        val dao = db.playlistDao()
        val activeIds = slots.filter { it.is_active }.map { it.id }
        dao.deleteNotIn(activeIds)

        for (slot in slots) {
            if (!slot.is_active) continue
            val existing = dao.findById(slot.id)
            // Diff on content_hash, not media_url — the server marks a re-uploaded
            // replacement as a new hash even if the URL happened to stay the same,
            // so this is the only reliable signal for "do I already have this exact file."
            val alreadyCached = existing?.localPath != null && existing.contentHash == slot.content_hash

            val localPath = if (alreadyCached) {
                existing!!.localPath
            } else {
                mediaCache.download(slot.content_hash, slot.media_url).getOrNull()?.absolutePath
            }

            dao.upsert(
                CachedPlaylistItem(
                    timeSlotId = slot.id,
                    mediaId = slot.id,
                    mediaUrl = slot.media_url,
                    contentHash = slot.content_hash,
                    mediaTitle = slot.media_title,
                    campaignName = slot.campaign_name,
                    advertiser = slot.advertiser,
                    playOrder = slot.play_order,
                    durationSeconds = slot.duration_seconds,
                    localPath = localPath,
                    downloadedAt = if (localPath != null) System.currentTimeMillis() else null
                )
            )
        }

        val keepFiles = dao.getAll().mapNotNull { it.localPath?.let { p -> java.io.File(p).name } }.toSet()
        mediaCache.pruneUnused(keepFiles)
    }

    private suspend fun flushPlaybackLogs(token: String) {
        val batch = db.playbackLogDao().getUnsyncedBatch()
        if (batch.isEmpty()) return

        val payload = batch.map {
            PlaybackLogEntry(
                media_id = it.mediaId,
                started_at = it.startedAt,
                duration_seconds = it.durationSeconds,
                completed = it.completed,
                time_slot_id = it.timeSlotId
            )
        }
        api.playbackBulk(token, payload).onSuccess {
            db.playbackLogDao().markSynced(batch.map { it.localId })
            db.playbackLogDao().pruneSyncedOverflow()
        }
        // On failure: rows stay unsynced, retried next cycle. Never lost.
    }

    private fun availableStorageMb(): Long =
        filesDir.usableSpace / (1024 * 1024)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "AdVerse Sync", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AdVerse Player")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
