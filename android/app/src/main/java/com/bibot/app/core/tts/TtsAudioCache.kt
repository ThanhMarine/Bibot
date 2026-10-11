package com.bibot.app.core.tts

import android.content.Context
import android.util.Log
import com.bibot.app.data.db.BibotDatabase
import com.bibot.app.data.db.TtsSegmentCache
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A1.2 — Cache audio content-addressed cho segment TTS (port openreader
 * `audioKey = .../{settingsHash}/{audioHash}.mp3` + bảng sidecar per-ordinal).
 *
 * - File: `{segRoot}/{settingsHash}/{audioHash}.{mp3|wav}` (segRoot durable,
 *   KHÔNG TTL — chỉ LRU theo size; xem FilePaths.cleanupSegmentCache).
 * - Sidecar Room `tts_segment_cache`: source of truth để lookup
 *   (file mồ côi không row → coi như miss, dọn dần bởi LRU).
 * - Đổi voice/speed → settingsHash khác → dir khác; switch-back trúng dir cũ
 *   (hết mất cache như `bibot_tts_<ms>` tạm trước đây).
 * - Offline Direct (speak trực tiếp, không bytes) không qua cache —
 *   voice nằm ở engine máy, output không địa chỉ hoá byte-ổn định được.
 */
class TtsAudioCache(
    private val context: Context,
    private val db: BibotDatabase
) {
    companion object {
        const val TAG = "TtsCache"
        const val MAX_MB = 300L
    }

    /** Hit khi row ready + file tồn tại và đủ lớn; đồng thời chạm lastHitAt (LRU). */
    suspend fun lookup(settingsHash: String, audioHash: String): File? = withContext(Dispatchers.IO) {
        try {
            val row = db.ttsSegments().get(audioHash, settingsHash) ?: return@withContext null
            if (row.status != "ready") return@withContext null
            val f = FilePaths.ttsSegmentFile(context, settingsHash, audioHash, row.ext)
            if (!f.exists() || f.length() < 100) {
                try { db.ttsSegments().delete(audioHash) } catch (_: Exception) {}
                return@withContext null
            }
            try { db.ttsSegments().touch(audioHash, System.currentTimeMillis()) } catch (_: Exception) {}
            f.setLastModified(System.currentTimeMillis())
            Log.d(TAG, "hit $settingsHash/$audioHash.${row.ext} (${f.length()}B)")
            f
        } catch (_: Exception) { null }
    }

    /**
     * Ghi nguyên tử (tmp → rename), upsert sidecar, siết LRU.
     * Fire-and-forget từ producer (không chặn phát).
     */
    suspend fun store(settingsHash: String, audioHash: String, ext: String, bytes: ByteArray): File? =
        withContext(Dispatchers.IO) {
            try {
                if (bytes.size < 100) return@withContext null
                val safeExt = if (ext.equals("wav", ignoreCase = true)) "wav" else "mp3"
                val dir = FilePaths.ttsSegmentDir(context, settingsHash)
                val tmp = File(dir, "$audioHash.$safeExt.tmp")
                val dst = File(dir, "$audioHash.$safeExt")
                if (dst.exists() && dst.length() > 100) {
                    db.ttsSegments().upsert(
                        TtsSegmentCache(audioHash, settingsHash, "", "", safeExt, dst.length())
                    )
                    return@withContext dst
                }
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(dst)) {
                    dst.delete()
                    if (!tmp.renameTo(dst)) { tmp.delete(); return@withContext null }
                }
                dst.setLastModified(System.currentTimeMillis())
                db.ttsSegments().upsert(
                    TtsSegmentCache(
                        audioHash = audioHash,
                        settingsHash = settingsHash,
                        segmentKey = "",
                        docId = "",
                        ext = safeExt,
                        bytesLen = dst.length()
                    )
                )
                Log.d(TAG, "store $settingsHash/$audioHash.$safeExt (${dst.length()}B)")
                enforceCap()
                dst
            } catch (e: Exception) {
                Log.d(TAG, "store fail: ${e.message}")
                null
            }
        }

    /** LRU theo lastHitAt: tổng vượt cap → xoá cũ nhất (cả row + file). */
    suspend fun enforceCap(maxMb: Long = MAX_MB) = withContext(Dispatchers.IO) {
        try {
            var total = try { db.ttsSegments().sumBytes() } catch (_: Exception) { -1L }
            if (total < 0) return@withContext // DB chưa sẵn — FilePaths.cleanupSegmentCache lo phần file
            var guard = 0
            while (total > maxMb * 1024 * 1024 && guard < 500) {
                guard++
                val victim = try { db.ttsSegments().oldest(20) } catch (_: Exception) { break }
                if (victim.isEmpty()) break
                for (row in victim) {
                    if (total <= maxMb * 1024 * 1024) break
                    try { FilePaths.ttsSegmentFile(context, row.settingsHash, row.audioHash, row.ext).delete() } catch (_: Exception) {}
                    try { db.ttsSegments().delete(row.audioHash) } catch (_: Exception) {}
                    total -= row.bytesLen
                }
            }
        } catch (_: Exception) {}
    }
}
