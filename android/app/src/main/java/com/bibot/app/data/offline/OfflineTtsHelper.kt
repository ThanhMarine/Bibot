package com.bibot.app.data.offline

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Offline TTS fallback — hybrid Cloud → on-device
 * Cloud: Google Cloud TTS (WaveNet) via Go tts.Synthesize / REST
 * Offline: Android TextToSpeech (Pico/Google TTS engine) — không cần mạng, giọng vi-VN nếu đã cài
 *   Kiểm tra: Settings → System → Language → Text-to-speech → Preferred engine
 *   Hoặc tải giọng vi-VN trong Google TTS.
 *
 * Thứ tự hybrid (điều khiển bởi DataStore ttsOfflineFallback + TTS_PRIORITY):
 *   auto:   Cloud → offline
 *   offline: offline trước → Cloud
 *   online: chỉ Cloud
 */
class OfflineTtsHelper(private val context: Context) : TextToSpeech.OnInitListener {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pendingInit: (() -> Unit)? = null
    // Fix kẹt speakOffline (Điểm tin/Story): serialize mọi lần speak + chờ init không mất callback.
    private val speakMutex = Mutex()
    private val initLock = Any()
    private val initWaiters = mutableListOf<CompletableDeferred<Boolean>>()

    fun ensureInit(onReady: () -> Unit) {
        if (ready) { onReady(); return }
        pendingInit = onReady
        if (tts == null) tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            // Ưu tiên vi-VN, fallback en-US
            val vi = tts?.isLanguageAvailable(Locale("vi", "VN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (vi >= TextToSpeech.LANG_AVAILABLE) tts?.language = Locale("vi", "VN")
            else tts?.language = Locale.US
            tts?.setSpeechRate(0.95f) // map 0.85x Pi → 0.95 Android (engine khác scale)
        }
        pendingInit?.invoke(); pendingInit = null
        // Đánh thức mọi waiter đang chờ init (không mất callback như pendingInit đơn trước đây)
        synchronized(initLock) {
            val copy = initWaiters.toList()
            initWaiters.clear()
            copy.forEach { d -> if (!d.isCompleted) d.complete(ready) }
        }
    }

    fun isAvailable(): Boolean = ready

    fun setSpeed(speed: Float) {
        // TTS speed 0.75x/0.85x/1.0x Pi → map 0.85-1.15 Android
        tts?.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
    }

    private suspend fun awaitReady(): Boolean {
        if (ready) return true
        val deferred = CompletableDeferred<Boolean>()
        synchronized(initLock) {
            if (ready) return true
            initWaiters.add(deferred)
            if (tts == null) {
                try { tts = TextToSpeech(context, this) } catch (_: Exception) {
                    initWaiters.remove(deferred)
                    if (!deferred.isCompleted) deferred.complete(false)
                    return false
                }
            }
        }
        // Hủy chờ init → gỡ waiter để không rò
        deferred.invokeOnCompletion {
            if (deferred.isCancelled) synchronized(initLock) { initWaiters.remove(deferred) }
        }
        return withTimeoutOrNull(8000L) { deferred.await() } ?: run {
            synchronized(initLock) { initWaiters.remove(deferred) }
            ready
        }
    }

    suspend fun speakOffline(text: String, speed: Float = 0.85f, lang: String = "vi"): Boolean {
        val speakText = com.bibot.app.core.text.TextCleaner.stripPauseMarker(text)
        if (speakText.isBlank()) return false
        // Serialize: 1 lần speak tại 1 thời điểm → listener không bị ghi đè, stop() không làm mất callback.
        return speakMutex.withLock {
            if (!awaitReady()) return false
            // Chọn giọng theo ngôn ngữ TEXT: sách tiếng Anh phải đọc bằng giọng en-US,
            // nếu không thiết bị có giọng vi-VN sẽ đọc tiếng Anh bằng giọng Việt (hỏng phát âm).
            if (lang == "en") {
                tts?.language = Locale.US
            } else {
                val vi = tts?.isLanguageAvailable(Locale("vi", "VN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                tts?.language = if (vi >= TextToSpeech.LANG_AVAILABLE) Locale("vi", "VN") else Locale.US
            }
            setSpeed(speed)
            // Timeout chống treo vô hạn nếu engine không gọi onDone/onError (VD stop giữa chừng).
            withTimeoutOrNull(90_000L) {
                suspendCancellableCoroutine { cont ->
                    val done = AtomicBoolean(false)
                    fun safeResume(v: Boolean) {
                        if (done.compareAndSet(false, true) && cont.isActive) cont.resume(v)
                    }
                    // Hủy coroutine (đổi tin/dừng/auto-read cancel) → dừng engine ngay, không kẹt mutex.
                    cont.invokeOnCancellation { try { tts?.stop() } catch (_: Exception) {} }
                    val id = "bibot_${System.currentTimeMillis()}_${speakText.hashCode()}"
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { if (utteranceId == id) safeResume(true) }
                        @Deprecated("Deprecated") override fun onError(utteranceId: String?) { safeResume(false) }
                        override fun onError(utteranceId: String?, errorCode: Int) { safeResume(false) }
                    })
                    val res = try {
                        tts?.speak(speakText, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
                    } catch (_: Exception) { TextToSpeech.ERROR }
                    if (res == TextToSpeech.ERROR) safeResume(false)
                }
            } ?: false
        }
    }

    suspend fun synthesizeToFile(text: String, speed: Float = 0.85f): File? = suspendCancellableCoroutine { cont ->
        val speakText = com.bibot.app.core.text.TextCleaner.stripPauseMarker(text)
        ensureInit {
            if (!ready) { cont.resume(null); return@ensureInit }
            setSpeed(speed)
            val out = File(FilePaths.ttsCache(context), "offline_${System.currentTimeMillis()}.wav")
            val id = out.absolutePath
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(out) }
                @Deprecated("Deprecated") override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(null) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
            val res = tts?.synthesizeToFile(speakText, null, out, id) ?: TextToSpeech.ERROR
            if (res == TextToSpeech.ERROR && cont.isActive) cont.resume(null)
        }
    }

    fun stop() { try { tts?.stop() } catch (_: Exception) {} }
    fun shutdown() { tts?.shutdown(); tts = null; ready = false }

    // Kiểm tra giọng vi-VN đã cài chưa
    fun hasVietnameseVoice(): Boolean {
        val avail = tts?.isLanguageAvailable(Locale("vi", "VN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        return avail >= TextToSpeech.LANG_AVAILABLE
    }
}

/**
 * Hybrid TTS manager: Cloud → offline
 */
class HybridTtsManager(private val context: Context) {
    private val offline = OfflineTtsHelper(context)

    enum class Priority { AUTO, OFFLINE, ONLINE }

    suspend fun speak(
        text: String,
        speed: Float = 0.85f,
        priority: Priority = Priority.AUTO,
        cloudSynthesize: suspend (String) -> ByteArray? // Go Cloud TTS trả bytes hoặc null khi fail
    ): Boolean {
        val clean = com.bibot.app.data.offline.TextCleanerForTts.clean(text) // reuse tts logic
        return when (priority) {
            Priority.OFFLINE -> {
                if (offline.speakOffline(clean, speed)) true
                else cloudSynthesize(clean)?.let { playCloudBytes(it, speed) } ?: false
            }
            Priority.ONLINE -> {
                cloudSynthesize(clean)?.let { playCloudBytes(it, speed) } ?: false
            }
            Priority.AUTO -> {
                val b = cloudSynthesize(clean)
                if (b != null) playCloudBytes(b, speed) else offline.speakOffline(clean, speed)
            }
        }
    }

    private fun playCloudBytes(bytes: ByteArray, speed: Float): Boolean {
        // Ghi cache rồi phát qua ExoPlayer (PlayerViewModel.playBytes)
        // Ở đây trả true để caller tự phát; thực tế VoiceScreen sẽ gọi PlayerViewModel.playBytes
        return bytes.isNotEmpty()
    }
}

// Thin cleaner mirror for offline path (tránh import Go)
private object TextCleanerForTts {
    fun clean(s: String): String = s.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("https?://\\S+"), " ")
        .replace(Regex("[*_~`#\\[\\]()]"), "")
        .trim().replace(Regex("\\s+"), " ")
}
