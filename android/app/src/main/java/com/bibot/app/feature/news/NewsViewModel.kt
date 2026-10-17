package com.bibot.app.feature.news

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bibot.app.data.offline.OfflineTtsHelper
import com.bibot.app.data.store.BibotPreferences
import com.bibot.app.ui.player.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel màn Điểm tin — đọc 1 tin / đọc liên tục hết danh sách.
 *
 * Fix 2026-09-30 (module Tin tức chết sau khi thêm đọc liên tục):
 * ① `NewsViewModel(context)` + `viewModel<NewsViewModel>()` không factory → crash
 *    `Cannot create an instance` ngay khi mở màn → chuyển AndroidViewModel.
 * ② `autoReadMode/currentIndex/isSpeaking` là plain var → Compose không recompose,
 *    nút play/next/prev + tiến trình đóng băng → chuyển mutableStateOf.
 * ③ Producer `trySend` fail chỉ delay 100ms rồi BỎ item (loop qua i tiếp) +
 *    `currentIndex = i` chạy theo producer (nhảy tới cuối ngay) → chuyển send()
 *    suspend (backpressure, không mất tin) + consumer set index theo tin ĐANG đọc.
 * ④ `stopSpeaking()` chỉ hạ cờ, KHÔNG cancel autoReadJob → đọc nền vẫn chạy →
 *    cancel job trước + stop engine.
 *
 * Fix 2026-09-30 (lỗi phần đọc Điểm tin — kẹt speakOffline):
 * ⑤ `offlineTts.stop()` gọi giữa `speakOffline` đang suspend làm mất callback
 *    onDone/onError (listener bị ghi đè) → coroutine treo, bấm đọc lần sau im lặng.
 *    → OfflineTtsHelper đã serialize bằng Mutex + cancellable + timeout; ở đây giữ
 *    thêm `speakJob` riêng, `stopSpeaking()` đồng bộ (hạ cờ + cancel job + stop
 *    engine ngay, không launch lồng nhau), auto-read viết lại vòng lặp tuần tự
 *    thay cho Channel producer/consumer (tránh producer chạy trước consumer).
 * ⑥ Text tin RSS còn HTML/entities → clean qua TextCleaner.cleanForTts trước khi đọc.
 */
class NewsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    // A3.2: engine TTS dùng chung toàn process — không tạo instance riêng cho màn Điểm tin.
    private val offlineTts = OfflineTtsHelper.shared(appContext)
    private val prefs = BibotPreferences(appContext)
    private var autoReadJob: Job? = null
    private var speakJob: Job? = null
    private var playerVm: PlayerViewModel? = null
    private var stopListener: (() -> Unit)? = null
    var autoReadMode by mutableStateOf(false)
        private set
    var currentIndex by mutableStateOf(0)
        private set
    var isSpeaking by mutableStateOf(false)
        private set
    /** Lỗi TTS gần nhất (hiện UI thay vì crash app) — kèm log tag NewsTts để lấy stack trace. */
    var speakError by mutableStateOf<String?>(null)
        private set
    /** Tên nguồn thực tế của lô tin đang hiển thị (VnExpress/Tuổi Trẻ/…/nguồn dự phòng). */
    var sourceName by mutableStateOf("")
        private set
    var currentNewsItems: List<NewsItem> = emptyList()

    /**
     * A3.2: nhận controller audio chung (1 controller cho ExoPlayer + engine TTS) —
     * mọi lần đọc/dừng của màn Điểm tin đi qua đây thay cho engine riêng.
     */
    fun bindPlayer(pm: PlayerViewModel) {
        playerVm = pm
        // Nghe lệnh dừng DUY NHẤT từ app (chuyển mode/đóng màn) — auto-read sống theo
        // Activity nên nếu không hủy theo, tin vẫn đọc khi đã ở màn khác.
        if (stopListener == null) {
            val l: () -> Unit = { runLocalStop() }
            stopListener = l
            pm.addStopListener(l)
        }
    }

    /** Dừng việc của màn (không gọi ngược controller — tránh lồng stopAll). */
    private fun runLocalStop() {
        cancelAutoLocked()
        cancelSpeakLocked()
        try { offlineTts.stop() } catch (_: Exception) {}
        isSpeaking = false
    }

    /** A3.2: 1 lệnh dừng duy nhất của màn Điểm tin (qua controller nếu đã bind). */
    private fun stopAudio() {
        val pm = playerVm
        if (pm != null) pm.stopAll() else runLocalStop()
    }

    /** Chuẩn bị trước 1 lượt đọc mới: hủy job cũ + cắt audio bằng đúng 1 lệnh dừng. */
    private fun resetAudioLocked() {
        cancelAutoLocked()
        cancelSpeakLocked()
        stopAudio()
        isSpeaking = false
    }

    /** Đọc 1 đoạn bằng engine TTS qua controller (fallback thẳng engine khi chưa bind). */
    private suspend fun speakViaController(text: String, speed: Float): Boolean {
        val pm = playerVm
        return if (pm != null) pm.speakOffline(text, speed) else offlineTts.speakOffline(text, speed)
    }

    fun resetIndex() {
        currentIndex = 0
    }

    fun clearSpeakError() {
        speakError = null
    }

    private suspend fun voiceSpeed(): Float = try {
        prefs.prefsFlow.first().voiceSpeed.coerceIn(0.5f, 2.0f)
    } catch (_: Exception) { 0.85f }

    private fun cleanSpeakText(item: NewsItem): String {
        val merged = buildNewsText(item)
        return try {
            com.bibot.app.core.text.TextCleaner.cleanForTts(merged).ifBlank { merged }
        } catch (_: Exception) { merged }
    }

    fun speakCurrentNews(index: Int) {
        try {
            if (currentNewsItems.isEmpty() || index !in currentNewsItems.indices) return
            resetAudioLocked()
            speakError = null
            currentIndex = index
            val text = cleanSpeakText(currentNewsItems[index])
            if (text.isBlank()) {
                speakError = "Tin này không có nội dung đọc được"
                return
            }
            Log.d(TAG_NEWS, "speakCurrentNews idx=$index len=${text.length}")
            isSpeaking = true
            speakJob = viewModelScope.launch {
                try {
                    val ok = speakViaController(text, voiceSpeed())
                    if (!ok) speakError = "TTS offline lỗi (thiếu engine/giọng) — thử lại hoặc cài Piper"
                } catch (_: CancellationException) {
                    try { offlineTts.stop() } catch (_: Exception) {}
                } catch (e: Exception) {
                    Log.e(TAG_NEWS, "speakCurrentNews failed", e)
                    speakError = "Lỗi đọc tin: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    isSpeaking = false
                }
            }
        } catch (e: Exception) {
            // Bọc cả đường đồng bộ trong onClick (buildNewsText/mergeTts/Regex) → hiện lỗi, không crash
            Log.e(TAG_NEWS, "speakCurrentNews sync failed", e)
            speakError = "Lỗi đọc tin: ${e.message ?: e.javaClass.simpleName}"
            isSpeaking = false
        }
    }

    /** Dừng đồng bộ: 1 lệnh dừng duy nhất (hủy job + ExoPlayer + engine TTS) ngay để UI phản hồi tức thì. */
    fun stopSpeaking() {
        resetAudioLocked()
    }

    fun setNewsItems(newsItems: List<NewsItem>) {
        currentNewsItems = newsItems
        if (currentIndex >= newsItems.size) currentIndex = 0
    }

    /** NewsScreen gọi sau mỗi lần tải: để câu chốt bản tin đọc đúng nguồn thực tế. */
    fun updateSourceName(name: String) {
        sourceName = name
    }

    fun playNext() {
        if (autoReadMode) return
        if (currentIndex < currentNewsItems.size - 1) {
            speakCurrentNews(currentIndex + 1)
        }
    }

    fun playPrevious() {
        if (autoReadMode) return
        if (currentIndex > 0) {
            speakCurrentNews(currentIndex - 1)
        }
    }

    fun toggleAutoRead() {
        if (autoReadMode) {
            stopSpeaking()
            return
        }
        if (currentNewsItems.isEmpty()) return
        resetAudioLocked()
        speakError = null
        autoReadMode = true
        currentIndex = 0
        isSpeaking = true
        autoReadJob = viewModelScope.launch {
            try {
                val speed = voiceSpeed()
                for (i in currentNewsItems.indices) {
                    if (!autoReadMode) break
                    // 1 tin loi (text la, regex, engine) khong duoc phep dung ca ban tin.
                    try {
                        currentIndex = i
                        isSpeaking = true
                        val text = cleanSpeakText(currentNewsItems[i])
                        if (text.isBlank()) continue
                        try {
                            val ok = speakViaController(text, speed)
                            if (!ok) speakError = "TTS offline lỗi ở tin ${i + 1} — thử lại hoặc cài Piper"
                        } catch (_: CancellationException) {
                            try { offlineTts.stop() } catch (_: Exception) {}
                            break
                        }
                    } catch (_: CancellationException) {
                        try { offlineTts.stop() } catch (_: Exception) {}
                        break
                    } catch (t: Throwable) {
                        Log.w(TAG_NEWS, "autoRead skip bad item $i", t)
                        continue
                    } finally {
                        isSpeaking = false
                    }
                    if (!autoReadMode) break
                    if (i != currentNewsItems.lastIndex) delay(400)
                }
                // Nguon chi HIEN tren the tin (khong doc): khong chot cau "Nguon: ..." cuoi ban tin.
            } catch (_: CancellationException) {
                try { offlineTts.stop() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG_NEWS, "autoRead failed", e)
                speakError = "Lỗi đọc tin: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isSpeaking = false
                autoReadMode = false
            }
        }
    }

    /** Hạ cờ + cancel job đọc nền (gọi trước mọi lần đọc đơn / dừng / đổi nguồn). */
    private fun cancelAutoLocked() {
        autoReadMode = false
        try { autoReadJob?.cancel() } catch (_: Exception) {}
        autoReadJob = null
    }

    private fun cancelSpeakLocked() {
        try { speakJob?.cancel() } catch (_: Exception) {}
        speakJob = null
    }

    /** Giữ tên cũ cho call-site hiện tại (stopSpeaking/speakCurrentNews dùng nội bộ). */
    private fun stopAutoLocked() = cancelAutoLocked()

    override fun onCleared() {
        super.onCleared()
        cancelAutoLocked()
        cancelSpeakLocked()
        // A3.2: KHÔNG shutdown() — engine TTS dùng chung toàn process (màn khác vẫn đọc).
        try { offlineTts.stop() } catch (_: Exception) {}
        stopListener?.let { l -> try { playerVm?.removeStopListener(l) } catch (_: Exception) {} }
        stopListener = null
    }

    companion object {
        const val TAG_NEWS = "NewsTts"

        /**
         * Text đọc 1 tin: LOẠI BỎ TIÊU ĐỀ + LƯỢC BỎ TRÍCH DẪN — chỉ đọc mô tả
         * (fallback tiêu đề nếu mô tả rỗng). Câu "Nguồn: xyz" KHÔNG đọc kèm
         * từng tin nữa, chỉ chốt 1 lần ở cuối bản tin (xem buildSourceLine).
         * (Trước đây mergeTts(title, desc) hay đọc lặp tiêu đề 2 lần vì RSS
         * thường copy title vào đầu description.)
         */
        fun buildNewsText(item: NewsItem): String {
            var desc = item.description.trim().ifBlank { item.title.trim() }
            // RSS hay dinh kem ghi nguon o duoi mo ta ("(Nguon: X)", "(Dan tri)",
            // "- Tuoitre", "Nguon: X") -> cat bo de khoi doc "Nguon: ..." sau MOI tin
            // (chi chot nguon 1 lan o cuoi ban tin). Lap 2 vong cho dau dan long nhau.
            repeat(2) { desc = stripSourceTail(desc).trim() }
            return desc.trimEnd().trimEnd('.', '!', '?', ':', ';', '…').trim().ifBlank { item.title.trim() }
        }

        /** Cat 1 duoi ghi nguon o cuoi chuoi; khong khop thi tra nguyen. */
        private fun stripSourceTail(s: String): String {
            if (s.isBlank()) return s
            var r = SOURCE_TAIL_PAREN.replace(s, "")
            r = SOURCE_TAIL_DASH.replace(r, "")
            r = SOURCE_TAIL_COLON.replace(r, "")
            return r
        }

        private val SOURCE_TAIL_PAREN =
            Regex("""\s*[\(\[]\s*(nguồn|source|theo)\s*[:：-]?\s*[^\)\]]{0,60}[\)\]]\s*$""", RegexOption.IGNORE_CASE)
        private val SOURCE_TAIL_DASH =
            Regex("""\s*[-–—]\s*(vnexpress|tuổi trẻ|tuoi tre|dân trí|dan tri|thanh niên|thanhnien|tiền phong|tien phong|người lao động|lao dong|google news|vietnamnet|vov|vtv|zingnews|zing|kenh14)\s*$""", RegexOption.IGNORE_CASE)
        private val SOURCE_TAIL_COLON =
            Regex("""\s*(nguồn|source)\s*[:：]\s*\S[^.]{0,30}\s*$""", RegexOption.IGNORE_CASE)

        /** Câu chốt cuối bản tin đọc tất cả: "Nguồn: Tuổi Trẻ." (theo nguồn thực tế). */
        fun buildSourceLine(actualSource: String): String = "Nguồn: ${actualSource.trim()}."
    }
}
