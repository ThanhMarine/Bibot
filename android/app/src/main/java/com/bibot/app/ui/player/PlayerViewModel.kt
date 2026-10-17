package com.bibot.app.ui.player

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bibot.app.data.offline.OfflineTtsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 0.85f,
    val title: String = "Bibot Player",
    val subtitle: String = "Sẵn sàng phát",
    val isBuffering: Boolean = false
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val player: ExoPlayer = ExoPlayer.Builder(application).apply {
        // WAKE_MODE_LOCAL: giu CPU thuc khi tat man hinh (PARTIAL_WAKE_LOCK, khong can permission).
        // Ban cu WAKE_MODE_NONE -> CPU ngu -> audio dut khi man hinh toi Doze.
        setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
        setHandleAudioBecomingNoisy(true)
        setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
    }.build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
            }
            // Karaoke sync: UI highlight NGHE LỆNH player (mediaId từng câu),
            // không tự đoán bằng delay ước tính → hết lệch pha tô trước/kẹt sau.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val prev = _currentMediaId.value
                _currentMediaId.value = mediaItem?.mediaId
                // Câu cũ rời vị trí phát (chuyển sang câu kế / bị xóa khỏi queue) → xong thật
                if (prev != null && prev != mediaItem?.mediaId) {
                    _activeIds.value = _activeIds.value - prev
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                _playerState.value = _playerState.value.copy(isBuffering = state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    _playerState.value = _playerState.value.copy(durationMs = duration.coerceAtLeast(0L))
                }
                // Câu cuối queue phát xong: ExoPlayer giữ nguyên item (không transition nữa)
                // → ghi ended marker để consumer biết mà chuyển highlight.
                if (state == Player.STATE_ENDED) {
                    player.currentMediaItem?.mediaId?.let { id ->
                        _endedMediaId.value = id
                        _activeIds.value = _activeIds.value - id
                    }
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val dir = com.bibot.app.data.files.FilePaths.ttsCache(getApplication())
                            dir.listFiles()?.filter { it.name.startsWith("bibot_tts_") && System.currentTimeMillis() - it.lastModified() > 60_000 }?.forEach { it.delete() }
                        } catch (_: Exception) {}
                    }
                }
                // Queue bị xóa sạch (stop/jump) → reset tracking để consumer chờ không kẹt
                if (state == Player.STATE_IDLE) {
                    _currentMediaId.value = null
                    _endedMediaId.value = null
                    _activeIds.value = emptySet()
                }
            }
        })
    }

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    // Karaoke sync: mediaId câu đang phát + tập id còn trong queue + id vừa phát xong.
    // Consumer (Story/Book) chờ id của mình rời _activeIds = audio xong THẬT.
    private val _currentMediaId = MutableStateFlow<String?>(null)
    val currentMediaId: StateFlow<String?> = _currentMediaId.asStateFlow()
    private val _endedMediaId = MutableStateFlow<String?>(null)
    val endedMediaId: StateFlow<String?> = _endedMediaId.asStateFlow()
    private val _activeIds = MutableStateFlow<Set<String>>(emptySet())
    val activeIds: StateFlow<Set<String>> = _activeIds.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            _playerState.value = _playerState.value.copy(
                currentPositionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.coerceAtLeast(0L).let { if (it < 0) _playerState.value.durationMs else it },
                isPlaying = player.isPlaying
            )
            // 2.3.2: chỉ tick khi đang phát/buffer để tiết kiệm CPU/RAM
            if (player.isPlaying || _playerState.value.isBuffering) handler.postDelayed(this, 500)
            else handler.postDelayed(this, 1000)
        }
    }

    init { handler.post(progressRunnable) }
    // 2.3.2 lifecycle helper cho UI
    fun onPause() { handler.removeCallbacks(progressRunnable) }
    fun onResume() { handler.removeCallbacks(progressRunnable); handler.post(progressRunnable) }

    // --- A3.2: 1 controller audio (ExoPlayer + engine Android TTS) ---
    private val offlineTts = OfflineTtsHelper.shared(application)

    /**
     * Màn có job đọc nền (News auto-read, Voice pipeline) đăng ký listener để
     * [stopAll] dừng đúng 1 lần là hết — không còn mỗi màn tự `stop()` kiểu của mình.
     */
    private val stopListeners = CopyOnWriteArrayList<() -> Unit>()
    fun addStopListener(listener: () -> Unit) { stopListeners.addIfAbsent(listener) }
    fun removeStopListener(listener: () -> Unit) { stopListeners.remove(listener) }

    fun ensureOfflineTts(onReady: () -> Unit) = offlineTts.ensureInit(onReady)

    /**
     * Đọc bằng engine Android TTS qua controller (màn không còn tự tạo OfflineTtsHelper).
     * Tự dừng ExoPlayer trước → 2 nguồn không bao giờ phát chồng; [mediaId] (nếu có)
     * ghi vào `activeIds` trong lúc đọc để trạng thái karaoke phản ánh đúng audio đang chạy.
     */
    suspend fun speakOffline(
        text: String,
        speed: Float = 0.85f,
        lang: String = "vi",
        mediaId: String? = null
    ): Boolean {
        withContext(Dispatchers.Main) { stopPlayback() }
        if (mediaId != null) _activeIds.value = _activeIds.value + mediaId
        android.util.Log.d(TAG_AUDIO, "speakOffline start lang=$lang len=${text.length} mediaId=$mediaId")
        return try {
            offlineTts.speakOffline(text, speed, lang).also { ok ->
                android.util.Log.d(TAG_AUDIO, "speakOffline done ok=$ok")
            }
        } finally {
            if (mediaId != null) _activeIds.value = _activeIds.value - mediaId
        }
    }

    /**
     * A3.2: HÀM DỪNG DUY NHẤT cho mọi màn — ExoPlayer + engine TTS + báo job đọc
     * nền (listener) hủy theo. Mọi màn gọi đây thay cho cặp `stopPlayback()` +
     * `offlineTts.stop()` rải rác (lỗi đọc chồng News/Weather/Voice bypass activeIds).
     */
    fun stopAll() {
        // Listener trước: hủy job đọc nền đã (News auto-read / Voice pipeline) —
        // coroutine dừng trước khi engine trả false, không sinh lỗi "TTS offline" giả.
        android.util.Log.d(TAG_AUDIO, "stopAll listeners=${stopListeners.size}")
        stopListeners.forEach { l -> try { l() } catch (_: Exception) {} }
        stopPlayback()
        stopOfflineEngine()
    }

    // Một nguồn sắp phát → cắt nguồn còn lại.
    private fun stopOfflineEngine() {
        if (Looper.myLooper() == Looper.getMainLooper()) offlineTts.stop()
        else handler.post { offlineTts.stop() }
    }

    private companion object {
        const val TAG_AUDIO = "AudioCtrl"
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }
    fun play() { player.play() }
    fun pause() { player.pause() }
    fun seekTo(positionMs: Long) {
        val d = player.duration
        player.seekTo(if (d > 0) positionMs.coerceIn(0L, d) else positionMs.coerceAtLeast(0L))
    }
    // A1.3: duration chưa biết (TIME_UNSET=-1, MP3 chưa load xong) thì KHÔNG clamp
    // về -1 (bug cũ: coerceAtMost(-1) → seekTo(-1)). Seek tuyến tính trên MP3
    // CBR theo byte (ExoPlayer + Xing header của Google MP3).
    fun seekForward(ms: Long = 10000) {
        val d = player.duration
        val target = player.currentPosition + ms
        player.seekTo(if (d > 0) target.coerceAtMost(d) else target)
    }
    fun seekBack(ms: Long = 10000) { player.seekTo((player.currentPosition - ms).coerceAtLeast(0L)) }
    // A1.3: speed 0.5–2.0x (khớp CloudTtsBridge.coerceIn) — ngoài dải thì kẹp,
    // không để ExoPlayer nhận rate lạ vỡ nhịp karaoke.
    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.5f, 2.0f)
        player.playbackParameters = PlaybackParameters(s)
        _playerState.value = _playerState.value.copy(playbackSpeed = s)
    }
    fun playUrl(url: String, title: String = "Bibot", subtitle: String = "", speed: Float = 0.85f, mediaId: String? = null) {
        // A3.2: ExoPlayer sắp phát → cắt engine TTS đang đọc (loại trừ 2 nguồn).
        stopOfflineEngine()
        // Session mới (setMediaItem thay cả queue) → reset tracking karaoke
        _currentMediaId.value = null
        _endedMediaId.value = null
        _activeIds.value = if (mediaId != null) setOf(mediaId) else emptySet()
        val item = if (mediaId != null) MediaItem.Builder().setUri(url).setMediaId(mediaId).build()
            else MediaItem.fromUri(url)
        player.setMediaItem(item)
        player.playbackParameters = PlaybackParameters(speed)
        _playerState.value = _playerState.value.copy(title = title, subtitle = subtitle, playbackSpeed = speed)
        player.prepare()
        // 2.4 ExoPlayer async: prepare không block, play khi STATE_READY
        player.play()
    }
    // 2.4 + 2.2.8: file write off Main (Dispatchers.IO) + spill file chỉ khi >512KB đã handle trong caller
    fun playBytes(bytes: ByteArray, mimeType: String = "audio/mp3", mediaId: String? = null) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val dir = com.bibot.app.data.files.FilePaths.ttsCache(ctx)
                val ext = if (mimeType.contains("wav")) "wav" else "mp3"
                val f = java.io.File(dir, "bibot_tts_${System.currentTimeMillis()}.$ext")
                f.writeBytes(bytes)
                // emit xong, giải phóng bytes gốc (2.3.1) — caller nên bytes = ByteArray(0) sau gọi
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    playUrl(android.net.Uri.fromFile(f).toString(), mediaId = mediaId)
                }
            } catch (_: Exception) {}
        }
    }
    // Flip-flop double-buffer: addMediaItem không clear queue, Exo tự double-buffer 2.4
    // mediaId: id câu karaoke — consumer chờ id này rời activeIds = audio xong THẬT.
    fun enqueueBytes(bytes: ByteArray, mimeType: String = "audio/mp3", mediaId: String? = null) {
        // A3.2: thêm chunk vào queue → cắt engine TTS nếu màn khác đang đọc dở.
        stopOfflineEngine()
        if (mediaId != null) _activeIds.value = _activeIds.value + mediaId
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val dir = com.bibot.app.data.files.FilePaths.ttsCache(ctx)
                val ext = if (mimeType.contains("wav")) "wav" else "mp3"
                val f = java.io.File(dir, "bibot_tts_${System.currentTimeMillis()}.$ext")
                f.writeBytes(bytes)
                val item = if (mediaId != null) MediaItem.Builder().setUri(android.net.Uri.fromFile(f)).setMediaId(mediaId).build()
                    else MediaItem.fromUri(android.net.Uri.fromFile(f))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    player.addMediaItem(item)
                    // nếu chưa prepare thì prepare, nếu đang phát thì chỉ add (double-buffer)
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            } catch (_: Exception) {
                // Ghi file/enqueue fail → gỡ tracking để consumer không chờ vô hạn (timeout lo)
                if (mediaId != null) _activeIds.value = _activeIds.value - mediaId
            }
        }
    }
    fun stopPlayback() {
        player.stop(); player.clearMediaItems()
        _currentMediaId.value = null
        _endedMediaId.value = null
        _activeIds.value = emptySet()
    }
    fun updateMedia(title: String, subtitle: String, durationMs: Long) {
        _playerState.value = _playerState.value.copy(title = title, subtitle = subtitle, durationMs = durationMs, currentPositionMs = 0L)
    }
    override fun onCleared() {
        handler.removeCallbacks(progressRunnable)
        player.release()
        super.onCleared()
    }
}
