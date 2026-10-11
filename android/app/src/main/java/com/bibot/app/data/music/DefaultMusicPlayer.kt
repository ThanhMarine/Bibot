package com.bibot.app.data.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore

/**
 * App nhạc mặc định của Android (YouTube Music / Google Play Music / Samsung Music / Spotify...)
 * — luồng phát nhạc CHÍNH của module Tìm nhạc:
 *  - [playTrack]: mở đúng bài qua `content://` MediaStore (ACTION_VIEW) → app mặc định tự phát
 *  - [searchAndPlay]: `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` + tên/tác giả → app ngoài tự tìm & phát
 *  - [open]: mở thẳng màn hình chính app nhạc (ACTION_MAIN + CATEGORY_APP_MUSIC)
 *
 * Mọi bước đều resolve trước (không có app nhận → trả false để caller fallback phát trong app).
 */
object DefaultMusicPlayer {

    /** Package app nhạc mặc định (CATEGORY_APP_MUSIC) — null nếu máy chưa chọn/lắp app nhạc. */
    fun defaultPackage(context: Context): String? = try {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.let { it.activityInfo?.packageName ?: it.serviceInfo?.packageName ?: it.providerInfo?.packageName }
    } catch (_: Exception) { null }

    /** Phát một bài cụ thể bằng app nhạc mặc định. false = không mở được (caller tự phát trong app). */
    fun playTrack(context: Context, track: Track): Boolean {
        val uri = track.mediaUri?.takeIf { it.isNotBlank() } ?: return false
        val base = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), "audio/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(context, base)
    }

    /** Gửi lệnh "tìm & phát {title} của {artist}" cho app nhạc mặc định (kể cả bài chưa có trên máy). */
    fun searchAndPlay(context: Context, q: MusicQuery): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            putExtra(MediaStore.EXTRA_MEDIA_TITLE, q.title)
            q.artist?.takeIf { it.isNotBlank() }?.let { putExtra(MediaStore.EXTRA_MEDIA_ARTIST, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, intent)
    }

    /** Mở màn hình chính của app nhạc mặc định. */
    fun open(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent)
    }

    /**
     * Bắn intent theo thứ tự: app mặc định → app duy nhất nhận → chooser (nhiều app nhận).
     * Trả false khi không có app nào nhận (tránh ActivityNotFoundException ra UI).
     */
    private fun start(context: Context, base: Intent): Boolean {
        val pm = context.packageManager
        val defPkg = defaultPackage(context)
        if (defPkg != null) {
            try { context.startActivity(Intent(base).setPackage(defPkg)); return true } catch (_: Exception) { /* thử tiếp */ }
        }
        val matches = try {
            pm.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY).size
        } catch (_: Exception) { 0 }
        return when {
            matches == 1 -> try { context.startActivity(base); true } catch (_: Exception) { false }
            matches > 1 -> try {
                context.startActivity(Intent.createChooser(base, "Chọn app nhạc").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Exception) { false }
            else -> false
        }
    }
}
