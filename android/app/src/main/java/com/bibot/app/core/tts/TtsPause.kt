package com.bibot.app.core.tts

import com.bibot.app.ui.player.PlayerViewModel
import kotlinx.coroutines.delay

/**
 * `delay(totalMs)` nhưng dừng sớm khi [jumped] trả về true.
 *
 * Dùng cho nút Rev/Next khi đang đọc: hủy phần "chờ audio xong" (ước tính `textLen * 60ms`)
 * → bấm là nhảy chương ngay, không phải đợi câu dài đọc hết mới thấy tác dụng.
 * Chờ theo lát 100ms để bắt thay đổi `jumpSeq` kịp (tất cả chạy trên Main).
 */
suspend fun delayUntilDone(totalMs: Long, jumped: () -> Boolean) {
    var remaining = totalMs
    while (remaining > 0 && !jumped()) {
        val step = minOf(remaining, 100L)
        delay(step)
        remaining -= step
    }
}

/**
 * Karaoke sync — highlight NGHE LỆNH player thay vì delay ước tính.
 *
 * Chờ tới khi [mediaId] rời `PlayerViewModel.activeIds`, tức audio câu này đã
 * phát xong THẬT (player chuyển sang câu kế = onMediaItemTransition, câu cuối
 * queue = STATE_ENDED, hoặc queue bị xóa = stop/jump). Hết lệch pha tô trước
 * block kế trong khi âm thanh block cũ chưa chạy xong.
 *
 * @param estimateMs chỉ dùng làm cơ sở timeout an toàn (ước tính*4 + 15s),
 * chống kẹt vô hạn nếu sự kiện player bị miss; Rev/Next dừng sớm qua [jumped].
 */
suspend fun awaitAudioDone(
    playerVm: PlayerViewModel,
    mediaId: String,
    estimateMs: Long,
    jumped: () -> Boolean
) {
    val timeout = (estimateMs * 4).coerceAtLeast(20_000L) + 15_000L
    var elapsed = 0L
    while (elapsed < timeout && !jumped()) {
        if (mediaId !in playerVm.activeIds.value) return
        delay(100L)
        elapsed += 100L
    }
}

/**
 * Karaoke sync (phan 1) — cho audio THUC SU bat dau roi moi to mau.
 *
 * Sua loi chunk dau bi to som: consumer lay item tu Channel ngay khi producer
 * synth xong, nhung audio Pcm (Piper/Cloud) can them 1-3s ghi file + ExoPlayer
 * prepare/buffer. Ban cu gan highlight ngay tai dequeue -> mat nhin truoc tai
 * nghe, cam giac chunk dau bi luot qua. Tu chunk 2 pipeline goi dau nen khop.
 *
 * Tra ve true neu player da chuyen sang [mediaId] (sap/đang phat), false neu
 * timeout (caller van to mau best-effort de khong dung hinh).
 */
suspend fun awaitAudioStart(
    playerVm: PlayerViewModel,
    mediaId: String,
    estimateMs: Long,
    jumped: () -> Boolean
): Boolean {
    val timeout = (estimateMs * 2).coerceAtLeast(10_000L) + 10_000L
    var elapsed = 0L
    while (elapsed < timeout && !jumped()) {
        // Player da nhan cau nay (transition) -> audio sap phat, to mau ngay
        if (playerVm.currentMediaId.value == mediaId) return true
        // Lo chuyen tiep (audio ngan da chay xong truoc khi poll kip) -> coi nhu da start
        if (mediaId !in playerVm.activeIds.value) return true
        delay(50L)
        elapsed += 50L
    }
    return false
}
