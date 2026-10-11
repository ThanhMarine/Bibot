package com.bibot.app.ui.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * KeepAwakeService — foreground service loai mediaPlayback giu app song khi tat man hinh.
 *
 * Vi sao wake lock (WAKE_MODE_LOCAL) chua du: khi may nam yen + tat man hinh ~30-60 phut,
 * Android Doze bo qua wake lock thuong, cat mang tong hop tieng Cloud + ha uu tien tien trinh
 * (OEM Xiaomi/Oppo con kill thang). Foreground service dang phat audio duoc mien Doze/App
 * Standby, doc xuyen dem khong dung.
 *
 * Pham vi v1: chi giu app song (content intent mo lai app). Dieu khien Play/Pause tren
 * notification de ban MediaSession tiep theo. Start khi bam Play (Story/Book), stop khi
 * Pause/Back/doi tab. Giua quang cao ngat pha KHONG stop (de tu doc tiep ngay ca khi tat man hinh).
 */
class KeepAwakeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Bat buoc goi startForeground ngay (qua timeout tren Android 12+ se crash)
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        return START_STICKY
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Doc nen",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Giu truyen/sach doc tiep khi tat man hinh" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bibot dang doc")
            .setContentText("Cham de mo lai ung dung")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bibot_playback"
        private const val NOTIF_ID = 1001
        private const val ACTION_START = "bibot.action.KEEP_AWAKE_START"
        private const val ACTION_STOP = "bibot.action.KEEP_AWAKE_STOP"

        /** Goi khi bam Play (idempotent: goi nhieu lan an toan). */
        fun start(context: Context) {
            try {
                val i = Intent(context, KeepAwakeService::class.java).setAction(ACTION_START)
                androidx.core.content.ContextCompat.startForegroundService(context, i)
            } catch (_: Exception) {}
        }

        /** Goi khi Pause/Back/doi tab (khong goi giua quang cao ngat pha). */
        fun stop(context: Context) {
            try {
                context.startService(Intent(context, KeepAwakeService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                try { context.stopService(Intent(context, KeepAwakeService::class.java)) } catch (_: Exception) {}
            }
        }
    }
}
