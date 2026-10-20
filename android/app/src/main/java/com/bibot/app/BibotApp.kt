package com.bibot.app

import android.app.Application
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.bibot.app.data.ads.AdManager
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BibotApp : Application(), ComponentCallbacks2 {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // P1 i18n: ap dung ngon ngu app da luu truoc khi UI dung resource
        try {
            kotlinx.coroutines.runBlocking {
                val lang = com.bibot.app.data.store.BibotPreferences(this@BibotApp)
                    .prefsFlow.first()
                    .language
                com.bibot.app.core.locale.AppLocale.applyOnStart(this@BibotApp, lang)
            }
        } catch (_: Exception) {}
        // 11.2: StrictMode debug — bắt leak file descriptor/thread trên Main
        if (com.bibot.app.BuildConfig.DEBUG) {
            try {
                android.os.StrictMode.setThreadPolicy(
                    android.os.StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
                )
                android.os.StrictMode.setVmPolicy(
                    android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build()
                )
            } catch (_: Exception) {}
        }
        // 2.1.2 + 2.1.1: dọn cache + prune Room khi khởi chạy (không block Main)
        appScope.launch {
            try {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val isLowRam = memInfo.lowMemory || memInfo.totalMem < 2L * 1024 * 1024 * 1024
                val maxMb = if (isLowRam) 150 else 200
                val ttlHours = if (isLowRam) 12 else 24
                FilePaths.cleanupCache(this@BibotApp, maxMb = maxMb, ttlHours = ttlHours)
                // prune Room 2.1.1: chat_log >500 + facts TTL
                try {
                    val db = com.bibot.app.data.db.BibotDatabase.get(this@BibotApp)
                    val count = db.chatLog().count()
                    if (count > 500) db.chatLog().deleteOldest((count - 500).toInt())
                } catch (_: Exception) {}
                // xoá tmp models dở
                try {
                    val tmp = java.io.File(FilePaths.cacheRoot(this@BibotApp), "models_tmp")
                    if (tmp.exists()) tmp.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24*3600*1000L) it.deleteRecursively() }
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
        // 2.1.3 WorkManager daily
        try { com.bibot.app.data.files.CleanupWorker.schedule(this) } catch (_: Exception) {}
        // 10.3 memory sync nền 12h/lần khi có mạng (no-op nếu chưa cấu hình Firebase)
        try { com.bibot.app.data.sync.MemorySyncWorker.schedule(this) } catch (_: Exception) {}
        // H2 ruler: nạp rulepack đọc sách 1 lần (nhẹ ~5KB, cache RAM, có fallback cứng)
        try { com.bibot.app.core.text.ReadingRules.init(this) } catch (_: Exception) {}
        // H3.2 OTA rulepack: tải JSON mới ở nền (404/lỗi → giữ bản nhúng),
        // bản mới ghi filesDir → hiệu lực từ lần mở app sau.
        appScope.launch {
            try { com.bibot.app.core.text.RulesOta.sync(this@BibotApp) } catch (_: Exception) {}
        }
        // AdMob initialize — chi ban demo (chinh thuc, ENABLE_ADS=true); ban commercial VIP (vip_only) khong init
        if (com.bibot.app.BuildConfig.ENABLE_ADS) {
            try { com.bibot.app.data.ads.AdManager.initialize(this) } catch (_: Exception) {}
        }
        // VIP het han -> tu quay ve co ban ngay tu luc khoi dong (don tier/until het han).
        appScope.launch {
            try { com.bibot.app.data.ads.AdsPreferences(this@BibotApp).checkAndClearExpiredVip() } catch (_: Exception) {}
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 2.2.10: phản ứng theo mức RAM — dọn cache + gợi ý GC (không rely)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                try { FilePaths.cleanupCache(this, maxMb = 80, ttlHours = 12) } catch (_: Exception) {}
                try { System.gc() } catch (_: Exception) {}
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                try { FilePaths.cleanupCache(this, maxMb = 80, ttlHours = 12) } catch (_: Exception) {}
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try { FilePaths.cleanupCache(this, maxMb = 80, ttlHours = 12) } catch (_: Exception) {}
        try { System.gc() } catch (_: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) }
}
