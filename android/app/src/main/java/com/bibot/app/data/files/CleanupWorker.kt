package com.bibot.app.data.files

import android.app.ActivityManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit

/**
 * 2.1.3 WorkManager daily 02:00 — dọn cache/bibot/chunks + tts + pdfs
 * LRU 150 MB máy yếu / 200 MB máy khỏe, TTL 12/24h
 */
class CleanupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val isLowRam = memInfo.lowMemory || memInfo.totalMem < 2L * 1024 * 1024 * 1024
            val maxMb = if (isLowRam) 150 else 200
            val ttlHours = if (isLowRam) 12 else 24
            FilePaths.cleanupCache(applicationContext, maxMb = maxMb, ttlHours = ttlHours)
            // thêm dọn models_tmp dở
            try {
                val tmp = java.io.File(FilePaths.cacheRoot(applicationContext), "models_tmp")
                tmp.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24*3600*1000L) it.deleteRecursively() }
            } catch (_: Exception) {}
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    companion object {
        const val UNIQUE_NAME = "bibot_cleanup_daily"
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresDeviceIdle(false)
                .setRequiresBatteryNotLow(false)
                .build()
            val req = PeriodicWorkRequestBuilder<CleanupWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(2, TimeUnit.HOURS) // lệch 2h sau cài, chạy nền daily
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}
