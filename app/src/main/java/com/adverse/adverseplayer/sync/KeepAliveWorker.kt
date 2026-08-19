package com.adverse.adverseplayer.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * WorkManager is deliberately NOT running the sync loop itself — its
 * periodic-work floor is 15 minutes, far too coarse for a 30s heartbeat.
 * Its one job here is to check every 15 min that SyncService is still
 * alive and restart it if some OEM battery-optimizer killed it — a real
 * risk on aggressive Android TV boxes.
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        SyncService.start(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "adverse_keep_alive"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
