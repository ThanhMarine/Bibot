package com.bibot.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 10.3 Worker sync memory nền — offline-first: chỉ chạy khi có mạng,
 * chưa cấu hình Firebase thì no-op (Result.success để không retry vô ích).
 */
class MemorySyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            if (!FirestoreSync.isConfigured()) return Result.success()
            val r = FirestoreSync.syncNow(applicationContext)
            if (r.isSuccess) Result.success() else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }

    companion object {
        const val UNIQUE_NAME = "bibot_memory_sync"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<MemorySyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}
