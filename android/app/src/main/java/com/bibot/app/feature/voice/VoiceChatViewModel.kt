package com.bibot.app.feature.voice

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bibot.app.BuildConfig
import com.bibot.app.core.llm.LlmBridge
import com.bibot.app.core.llm.LlmStreamListener
import com.bibot.app.core.search.DefaultWebSearchProvider
import com.bibot.app.core.search.IntentRouter
import com.bibot.app.core.text.TextCleaner
import com.bibot.app.core.tts.CloudTtsBridge
import com.bibot.app.core.tts.PiperTtsHelper
import com.bibot.app.core.tts.VietnameseChunker
import com.bibot.app.data.memory.MemoryRepository
import com.bibot.app.data.music.DefaultMusicPlayer
import com.bibot.app.data.music.MusicIndex
import com.bibot.app.data.music.MusicQuery
import com.bibot.app.data.music.parseMusicCommand
import com.bibot.app.data.music.searchLocalTracks
import com.bibot.app.data.offline.HybridSttManager
import com.bibot.app.data.offline.OfflineTtsHelper
import com.bibot.app.data.store.BibotPreferences
import com.bibot.app.data.store.BibotPrefs
import com.bibot.app.data.store.BotPromptManager
import com.bibot.app.ui.player.PlayerViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class HandsFreeMode {
    NONE,       // Default: tap to start one turn
    CONTINUOUS, // Tap once to start continuous listening until stop
    PUSH_TO_TALK // Hold to listen, release to process
}

/** State machine của voice chat: idle / listening / thinking(streaming) / speaking. */
data class VoiceChatUiState(
    val responseText: String = "Em là Bi! Em có thể giúp gì cho bạn hôm nay? \uD83D\uDE0A",
    val partialText: String = "",
    val isListening: Boolean = false,
    val isStreaming: Boolean = false,
    val isSpeaking: Boolean = false,
    val sttError: String? = null,
    val ttsStatus: String? = null,
    val handsFreeMode: HandsFreeMode = HandsFreeMode.NONE,
    val continuousListening: Boolean = false
)

/** Stats mỗi lượt thoại (port Pi VoiceChatStats — TTFA/turn + barge-in count). */
data class VoiceChatStats(
    val turns: Int = 0,
    val lastTtfaMs: Long = -1L,
    val lastTurnMs: Long = -1L,
    val avgTtfaMs: Long = -1L,
    val bargeInCount: Int = 0
)

/**
 * VoiceChatViewModel — tách state machine + pipeline khỏi VoiceScreen (quyết định 2026-09-29).
 * VoiceScreen chỉ render + event. Logic giữ nguyên hành vi cũ (port Pi streaming_voice_chat):
 * HybridSTT → (music local / memory recall) → IntentRouter+search async → LLM stream →
 * chunker → speakChannel(64) consumer gapless enqueueBytes → filler che TTFA.
 */
class VoiceChatViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val hybridStt = HybridSttManager(context)
    // A3.2: engine TTS dùng chung toàn process — không tạo instance riêng cho màn Voice.
    private val offlineTts = OfflineTtsHelper.shared(context)
    private val chunker = VietnameseChunker(minWords = 4, maxWords = 15, maxChars = 160, yieldOnClause = true)
    private val memRepo = MemoryRepository(context)
    private val prefs = BibotPreferences(context)
    private var playerVm: PlayerViewModel? = null
    private var stopListener: (() -> Unit)? = null

    @Volatile private var latestPrefs: BibotPrefs = BibotPrefs()
    private val ttfaSamples = mutableListOf<Long>()

    private val _uiState = MutableStateFlow(VoiceChatUiState())
    val uiState: StateFlow<VoiceChatUiState> = _uiState.asStateFlow()
    private val _stats = MutableStateFlow(VoiceChatStats())
    val stats: StateFlow<VoiceChatStats> = _stats.asStateFlow()

    private var pipelineJob: Job? = null
    private var continuousJob: Job? = null
    private var fillerBytes: ByteArray? = null
    private var searchFillerBytes: ByteArray? = null
    // Duplex thật (port Pi aec.py + barge_in_detector.py): AEC NLMS + mic monitor song song loa.
    private val aec = AecProcessor()
    private var bargeInJob: Job? = null
    private var bargeInFailures = 0
    @Volatile private var bargeInDisabled = false

    companion object {
        const val VOICE_MAX_TOKENS = 150
        const val TAG_VC = "VoiceChat"
        const val SEARCH_TIMEOUT_MS = 7000L
        // Cloud TTS voice constants for Vietnamese
        private const val VOICE_VI_NEURAL2 = "vi-VN-Neural2-A"
        private const val VOICE_VI_WAVENET = "vi-VN-Wavenet-A"
        // Barge-in monitor (port Pi BargeInDetector): chunk 1024 (64ms @16k),
        // ngưỡng RMS 300, confirm 400ms liên tiếp, grace 500ms đầu, ×1.6 khi loa phát.
        private const val BARGE_CHUNK = 1024
        private const val BARGE_THRESHOLD = 300.0
        private const val BARGE_CONFIRM_MS = 400
        private const val BARGE_GRACE_MS = 500L
        private const val BARGE_TTS_BOOST = 1.6
    }

    init {
        viewModelScope.launch {
            prefs.prefsFlow.collect { latestPrefs = it }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try { offlineTts.ensureInit {} } catch (_: Exception) {}
            val hasVoice = try { PiperTtsHelper.isVoiceComplete(context, "vi") } catch (_: Exception) { false }
            fillerBytes = try {
                if (hasVoice) PiperTtsHelper.synthesize(context, "Ừm, để em suy nghĩ đã.", "vi") else null
            } catch (_: Exception) { null }
            searchFillerBytes = try {
                if (hasVoice) PiperTtsHelper.synthesize(context, "Đợi em một chút để em tìm thông tin nhé.", "vi") else null
            } catch (_: Exception) { null }
        }
    }

    fun bindPlayer(pm: PlayerViewModel) {
        playerVm = pm
        // A3.2: nghe lệnh dừng DUY NHẤT từ app (chuyển mode/đóng màn) — job pipeline
        // sống theo Activity nên nếu không hủy theo, audio sẽ tiếp tục phát trên màn khác.
        if (stopListener == null) {
            val l: () -> Unit = { runLocalStop() }
            stopListener = l
            pm.addStopListener(l)
        }
    }

    /**
     * Dừng phần việc của màn Voice (không gọi ngược controller để tránh lồng stopAll).
     * Gọi từ [stopAudio] (qua controller) hoặc trực tiếp từ onCleared.
     */
    private fun runLocalStop() {
        stopBargeInMonitor()
        try { pipelineJob?.cancel() } catch (_: Exception) {}
        try { chunker.reset() } catch (_: Exception) {}
        try { offlineTts.stop() } catch (_: Exception) {}
        _uiState.update { it.copy(isSpeaking = false, isStreaming = false, ttsStatus = null) }
    }

    /**
     * A3.2: 1 lệnh dừng duy nhất của màn Voice — báo [PlayerViewModel.stopAll]
     * (ExoPlayer + engine TTS), controller tự gọi lại [runLocalStop] qua listener.
     */
    private fun stopAudio() {
        val pm = playerVm
        if (pm != null) pm.stopAll() else runLocalStop()
    }

    /** Cắt audio trước lượt MỚI — chỉ dừng 2 nguồn phát, không đụng job/cờ của lượt sắp chạy. */
    private fun cutAudio() {
        try { playerVm?.stopPlayback() } catch (_: Exception) {}
        try { offlineTts.stop() } catch (_: Exception) {}
    }

    fun setHandsFreeMode(mode: HandsFreeMode) {
        _uiState.update { it.copy(handsFreeMode = mode) }
    }

    fun setSttError(msg: String?) {
        _uiState.update { it.copy(sttError = msg) }
    }

    /** P1-2: 1 chunk = synth → enqueueBytes (KHÔNG clear queue) → pacing ước tính, gapless. */
    suspend fun speakOne(raw: String, prefsSnapshot: BibotPrefs) {
        val (chunk, needPause) = TextCleaner.splitPause(raw)
        if (chunk.isBlank()) {
            if (needPause) delay(2000)
            return
        }
        _uiState.update { it.copy(isSpeaking = true, ttsStatus = "Đang đọc: ${chunk.take(28)}...") }
        val speed = prefsSnapshot.voiceSpeed
        val cloudKey = prefsSnapshot.cloudTtsApiKey.takeIf { s -> s.isNotBlank() }
        var spoken = false

        val priority = prefsSnapshot.ttsPriority
        val isPiperOnly = priority == "piper"
        val isCloudOnly = priority == "cloud-neural2" || priority == "cloud-wavenet"
        val isOfflineOnly = priority == "offline"

        // P3-1: Piper TTS (priority: piper, auto)
        if (!isCloudOnly && !isOfflineOnly) {
            val lang = "vi"
            val piperReady = try { PiperTtsHelper.isVoiceComplete(context, lang) } catch (_: Exception) { false }
            if (piperReady) {
                val bytes = PiperTtsHelper.synthesize(context, chunk, lang, speed.coerceIn(0.5f, 2.0f))
                if (bytes != null && bytes.isNotEmpty()) {
                    // Duplex: feed reference vào AEC TRƯỚC khi phát (port Pi _process_turn).
                    feedAecReference(bytes)
                    playerVm?.enqueueBytes(bytes, "audio/wav")
                    delay((chunk.length * 50L).coerceAtLeast(500L))
                    spoken = true
                }
            }
        }

        // P3-2: Cloud TTS (priority: auto, cloud-neural2, cloud-wavenet)
        if (!spoken && !isPiperOnly && !isOfflineOnly && !cloudKey.isNullOrBlank()) {
            val voiceName = when (priority) {
                "cloud-neural2" -> VOICE_VI_NEURAL2
                "cloud-wavenet" -> VOICE_VI_WAVENET
                else -> VOICE_VI_NEURAL2
            }
            val bytes = CloudTtsBridge.synthesize(chunk, cloudKey, voice = voiceName, speakingRate = speed.coerceIn(0.5f, 2.0f))
            if (bytes != null && bytes.isNotEmpty()) {
                playerVm?.enqueueBytes(bytes, "audio/mp3")
                delay((chunk.length * 50L).coerceAtLeast(500L))
                spoken = true
            }
        }

        // P3-3: Offline TTS fallback (Android TTS)
        if (!spoken && (isOfflineOnly || priority == "auto" || isPiperOnly || isCloudOnly)) {
            // A3.2: chờ ExoPlayer (chunk Piper/Cloud trước) phát xong rồi mới qua controller
            // đọc engine TTS — không cắt mất chữ và không để 2 nguồn phát chồng nhau.
            var idle = 0
            while ((playerVm?.playerState?.value?.isPlaying == true) && idle < 50) { delay(200); idle++ }
            val pm = playerVm
            val ok = if (pm != null) pm.speakOffline(chunk, speed) else offlineTts.speakOffline(chunk, speed)
            if (!ok) _uiState.update { it.copy(ttsStatus = "TTS offline lỗi, thử chunk tiếp...") }
        }

        if (needPause) delay(2000)
    }

    suspend fun speakChunks(chunks: List<String>, prefsSnapshot: BibotPrefs) {
        if (chunks.isEmpty()) return
        _uiState.update { it.copy(isSpeaking = true) }
        startBargeInMonitor()
        try {
            for (raw in chunks) speakOne(raw, prefsSnapshot)
            withContext(NonCancellable) {
                var guard = 0
                while ((playerVm?.playerState?.value?.isPlaying == true) && guard < 150) { delay(200); guard++ }
            }
        } finally {
            stopBargeInMonitor()
            _uiState.update { it.copy(ttsStatus = null, isSpeaking = false) }
        }
    }

    /** Feed WAV Piper vào AEC làm reference (port Pi `aec.feed_reference` trước khi phát). */
    private fun feedAecReference(wavBytes: ByteArray) {
        try {
            val (pcm, rate) = parseWavPcm16(wavBytes) ?: return
            aec.feedReference(pcm, rate)
        } catch (_: Exception) {}
    }

    /**
     * Barge-in monitor song song loa (port Pi `BargeInDetector._detect_loop`):
     * mở mic TRONG lúc TTS phát, RMS/chunk 1024 → ngưỡng ×1.6 + grace 500ms →
     * lọc AEC → confirm 400ms liên tiếp → cắt playback + nghe lại (real talk).
     * Mic fail 3 lần liên tiếp → tự disable (port Pi), chat tiếp tục không barge-in.
     */
    private fun startBargeInMonitor() {
        if (bargeInDisabled || !latestPrefs.bargeInEnabled) return
        if (bargeInJob?.isActive == true) return
        bargeInJob = viewModelScope.launch(Dispatchers.IO) {
            val startTs = SystemClock.elapsedRealtime()
            val required = maxOf(1, BARGE_CONFIRM_MS * 16000 / (1000 * BARGE_CHUNK))
            var consecutive = 0
            var recorder: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) throw IllegalStateException("getMinBufferSize=$minBuf")
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord not initialized")
                }
                recorder.startRecording()
                bargeInFailures = 0
                val buf = ShortArray(BARGE_CHUNK)
                while (_uiState.value.isSpeaking && isActive) {
                    val n = try { recorder.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                    if (n <= 0) {
                        delay(10)
                        continue
                    }
                    val inGrace = SystemClock.elapsedRealtime() - startTs < BARGE_GRACE_MS
                    // Port Pi: ngưỡng ×1.6 khi loa đang phát (monitor chỉ chạy lúc speaking → luôn ×1.6).
                    val effThr = BARGE_THRESHOLD * BARGE_TTS_BOOST
                    val energy = micRms(buf, n)
                    if (!inGrace && energy > effThr) {
                        // Active filter: trừ echo TTS khỏi mic trước khi confirm (port Pi AEC).
                        val cleaned = try {
                            aec.processMic(buf.copyOf(n))
                        } catch (_: Exception) { null }
                        val energy2 = if (cleaned != null) micRms(cleaned) else energy
                        if (energy2 > BARGE_THRESHOLD) {
                            consecutive++
                            if (consecutive >= required) {
                                Log.d(TAG_VC, "barge-in detected! rawRms=${energy.toInt()} cleanRms=${energy2.toInt()} erle=${aec.erleDb()}")
                                onBargeIn()
                                consecutive = 0
                                delay(500) // chống multi-trigger (port Pi)
                            }
                        } else {
                            consecutive = 0
                        }
                    } else {
                        consecutive = 0
                    }
                    delay(10)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG_VC, "barge-in monitor error: $e")
                bargeInFailures++
                if (bargeInFailures >= 3) {
                    bargeInDisabled = true
                    Log.w(TAG_VC, "barge-in mic fail 3 lần → tắt barge-in, chat tiếp tục không ngắt chen (port Pi)")
                }
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun stopBargeInMonitor() {
        try { bargeInJob?.cancel() } catch (_: Exception) {}
        bargeInJob = null
    }

    /** Callback khi phát hiện ngắt lời: cắt TTS + mở mic nghe lại ngay (real talk). */
    private fun onBargeIn() {
        _stats.update { it.copy(bargeInCount = it.bargeInCount + 1) }
        viewModelScope.launch(Dispatchers.Main) {
            stopBargeInMonitor()
            // A3.2: cắt ExoPlayer + engine TTS + job pipeline bằng 1 lệnh dừng duy nhất.
            stopAudio()
            // Real talk: mở mic nghe lại ngay thay vì chờ user bấm (trừ push-to-talk đang giữ nút).
            if (_uiState.value.handsFreeMode != HandsFreeMode.PUSH_TO_TALK) {
                beginStt()
            }
        }
    }

    fun openExternalMusicApp(q: MusicQuery) {
        viewModelScope.launch {
            val ps = latestPrefs
            if (DefaultMusicPlayer.searchAndPlay(context, q)) {
                val msg = "Em không thấy bài này trên máy, để em mở app nhạc mặc định cho bạn tìm \"${q.title}\"."
                _uiState.update { it.copy(responseText = "🎵 $msg") }
                speakChunks(listOf(msg), ps)
            } else {
                val hint = if (!MusicIndex.hasAudioPermission(context))
                    ". Bạn chưa cấp quyền đọc thư viện nhạc — vào màn \"Nhạc\" để cấp" else ""
                val msg = "Em không tìm thấy \"${q.title}\" trên máy và không có app nhạc nào nhận lệnh$hint."
                _uiState.update { it.copy(responseText = "🎵 $msg") }
                speakChunks(listOf(msg), ps)
            }
        }
    }

    fun handleMusicCommand(q: MusicQuery) {
        val asked = "\"${q.title}\"" + (q.artist?.let { a -> " của $a" } ?: "")
        _uiState.update { it.copy(responseText = "🎵 Đang tìm $asked...", partialText = "", isStreaming = false, sttError = null) }
        stopAudio()
        viewModelScope.launch {
            val ps = latestPrefs
            val hits = searchLocalTracks(context, q)
            val confident = hits.isNotEmpty() && hits[0].second >= 100 &&
                (hits.size == 1 || hits[1].second < 100)
            when {
                confident -> {
                    val track = hits[0].first
                    if (DefaultMusicPlayer.playTrack(context, track)) {
                        _uiState.update { it.copy(responseText = "🎵 Đang mở ${track.title} của ${track.artist} bằng app nhạc mặc định.") }
                    } else {
                        val msg = "Đang mở ${track.title} của ${track.artist}."
                        _uiState.update { it.copy(responseText = "🎵 $msg") }
                        speakChunks(listOf(msg), ps)
                        try {
                            playerVm?.playUrl(
                                Uri.fromFile(java.io.File(track.path)).toString(),
                                track.title, track.artist
                            )
                        } catch (e: Exception) { _uiState.update { it.copy(responseText = "🎵 Không mở được file nhạc: ${e.message}") } }
                    }
                }
                hits.size > 1 -> {
                    val names = hits.take(3).joinToString("; ") { h -> h.first.title + " (" + h.first.artist + ")" }
                    val msg = "Có ${hits.size} bản trùng tên. Bạn muốn nghe bản nào: $names?"
                    _uiState.update { it.copy(responseText = "🎵 $msg") }
                    speakChunks(listOf(msg), ps)
                }
                else -> openExternalMusicApp(q)
            }
        }
    }

    fun startVoicePipelineWithHybrid(sttText: String) {
        if (sttText.isBlank()) return
        val musicCmd = parseMusicCommand(sttText)
        if (musicCmd != null) {
            chunker.reset()
            pipelineJob?.cancel()
            handleMusicCommand(musicCmd)
            return
        }
        // Barge-in (turn-granularity, an toàn — không mở mic đồng thời với loa):
        // lượt mới tới khi đang nói/streaming → cắt lượt cũ + playback trước.
        val cur = _uiState.value
        if (cur.isSpeaking || cur.isStreaming) {
            Log.d(TAG_VC, "barge-in: cut previous turn (speaking=${cur.isSpeaking} streaming=${cur.isStreaming})")
        }
        _uiState.update {
            it.copy(responseText = "", partialText = "", isStreaming = true, sttError = null)
        }
        chunker.reset()
        pipelineJob?.cancel()
        // A3.2: cắt ExoPlayer + engine TTS (chỉ audio — lượt mới sắp gán pipelineJob).
        cutAudio()
        try { aec.reset() } catch (_: Exception) {} // lượt mới → filter học lại từ đầu (port Pi)

        pipelineJob = viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val ps = try { prefs.prefsFlow.first() } catch (_: Exception) { latestPrefs }
            latestPrefs = ps
            try { memRepo.observeUser(sttText) } catch (_: Exception) {}
            try { memRepo.logTurn("user", sttText) } catch (_: Exception) {}
            val recalled = try { memRepo.recallAnswer(sttText) } catch (_: Exception) { null }
            if (!recalled.isNullOrBlank()) {
                _uiState.update { it.copy(responseText = recalled) }
                speakChunks(listOf(recalled), ps)
                try { memRepo.logTurn("assistant", recalled) } catch (_: Exception) {}
                recordTurnStats(t0, ttfaMs = 0L)
                return@launch
            }

            val needsSearch = IntentRouter.requiresLiveInfo(sttText)
            Log.d(TAG_VC, "router=${if (needsSearch) "search" else "direct"} q=$sttText")
            val searchDeferred = if (needsSearch) async(Dispatchers.IO) {
                val t1 = SystemClock.elapsedRealtime()
                val r = try {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        DefaultWebSearchProvider.searchSummary(sttText)
                    } ?: ""
                } catch (_: Exception) { "" }
                Log.d(TAG_VC, "search=${r.length}ch in ${SystemClock.elapsedRealtime() - t1}ms")
                r
            } else null

            val memLine = try { memRepo.memoryContextLine() } catch (_: Exception) { "" }
            val geminiKey = ps.geminiApiKey.takeIf { k -> k.isNotBlank() }
            val openRouterKey = ps.openRouterKey.takeIf { k -> k.isNotBlank() }
            val proxyUrl = try { BuildConfig.BIBOT_LLM_PROXY_URL.takeIf { u -> u.isNotBlank() } } catch (_: Exception) { null }

            val speakChannel = Channel<String>(64)
            val tokenArrived = AtomicBoolean(false)
            var anyChunk = false
            var ttfaMs = -1L

            val consumer = launch {
                try {
                    for (c in speakChannel) {
                        _uiState.update { it.copy(isSpeaking = true) }
                        speakOne(c, ps)
                    }
                } catch (_: CancellationException) {
                } finally {
                    withContext(NonCancellable) {
                        var guard = 0
                        while ((playerVm?.playerState?.value?.isPlaying == true) && guard < 150) { delay(200); guard++ }
                    }
                    _uiState.update { it.copy(isSpeaking = false, ttsStatus = null) }
                }
            }

            val fillerJob = launch {
                delay(400)
                if (!tokenArrived.get() && _uiState.value.isStreaming) {
                    val fb = if (needsSearch) (searchFillerBytes ?: fillerBytes) else fillerBytes
                    fb?.let {
                        feedAecReference(it)
                        playerVm?.enqueueBytes(it, "audio/wav")
                        if (needsSearch) {
                            delay(2800)
                            if (!tokenArrived.get() && _uiState.value.isStreaming) playerVm?.enqueueBytes(it, "audio/wav")
                        }
                    }
                }
            }

            val searchCtx = try { searchDeferred?.await() ?: "" }
            catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
            // Ngữ cảnh hội thoại: CHỈ khi câu hiện tại cần (gating tiết kiệm token) —
            // câu độc lập skip (0 token thêm), câu nối dùng slim window 3 lượt/1000 ký tự.
            // Lấy ở đây (sau recall/music) nên chỉ tốn 1 query Room cho lượt gọi LLM thật.
            val wantHistory = try { com.bibot.app.data.memory.needsHistory(sttText) } catch (_: Exception) { false }
            val history = try {
                if (wantHistory)
                    memRepo.recentHistory(limitTurns = 3, perMsgChars = 200, totalChars = 1000)
                else emptyList()
            } catch (_: Exception) { emptyList() }
            Log.d(TAG_VC, "history gate=$wantHistory msgs=${history.size} chars=${history.sumOf { it.second.length }}")
            val sysPrompt = buildString {
                append(BotPromptManager.buildFromPrefs(ps))
                if (memLine.isNotBlank()) append("\n").append(memLine)
                if (searchCtx.isNotBlank())
                    append("\n[Kết quả web — trả lời dựa trên dữ liệu dưới đây, ngắn gọn, không bịa số liệu]:\n")
                        .append(searchCtx)
            }

            LlmBridge.generateStreamRest(
                prompt = sttText,
                geminiKey = geminiKey,
                systemPrompt = sysPrompt,
                geminiModel = ps.geminiModel,
                openRouterKey = openRouterKey,
                proxyUrl = proxyUrl,
                maxOutputTokens = VOICE_MAX_TOKENS,
                history = history,
                listener = object : LlmStreamListener {
                    override fun onToken(token: String) {
                        if (!_uiState.value.isStreaming) return
                        if (TextCleaner.looksLikeThinking(token)) return
                        if (tokenArrived.compareAndSet(false, true)) {
                            ttfaMs = SystemClock.elapsedRealtime() - t0
                            Log.d(TAG_VC, "TTFA=${ttfaMs}ms")
                        }
                        _uiState.update { it.copy(responseText = it.responseText + token) }
                        val ready = chunker.feed(token)
                        for (c in ready) {
                            val cc = TextCleaner.cleanForTts(c)
                            if (cc.isNotBlank() && speakChannel.trySend(cc).isSuccess) anyChunk = true
                        }
                    }
                    override fun onComplete() {
                        val tail = chunker.flush()
                        for (c in tail) {
                            val cc = TextCleaner.cleanForTts(c)
                            if (cc.isNotBlank() && speakChannel.trySend(cc).isSuccess) anyChunk = true
                        }
                        if (!anyChunk && _uiState.value.responseText.isNotBlank()) {
                            for (c in VietnameseChunker.split(_uiState.value.responseText)) {
                                val cc = TextCleaner.cleanForTts(c)
                                if (cc.isNotBlank()) speakChannel.trySend(cc)
                            }
                        }
                        speakChannel.close()
                        _uiState.update { it.copy(isStreaming = false) }
                    }
                    override fun onError(errorMessage: String) {
                        _uiState.update { it.copy(isStreaming = false, responseText = it.responseText + "\n[Lỗi: $errorMessage]") }
                        speakChannel.close()
                    }
                }
            )
            consumer.join()
            fillerJob.cancel()
            try { memRepo.logTurn("assistant", _uiState.value.responseText) } catch (_: Exception) {}
            recordTurnStats(t0, ttfaMs)
            try { System.gc() } catch (_: Exception) {}
        }
    }

    private fun recordTurnStats(t0: Long, ttfaMs: Long) {
        val dur = SystemClock.elapsedRealtime() - t0
        if (ttfaMs >= 0) {
            synchronized(ttfaSamples) {
                ttfaSamples += ttfaMs
                if (ttfaSamples.size > 20) ttfaSamples.removeAt(0)
            }
        }
        val avg = synchronized(ttfaSamples) {
            if (ttfaSamples.isEmpty()) -1L else ttfaSamples.sum() / ttfaSamples.size
        }
        _stats.update {
            it.copy(turns = it.turns + 1, lastTtfaMs = ttfaMs, lastTurnMs = dur, avgTtfaMs = avg)
        }
        Log.d(TAG_VC, "stats turn=${_stats.value.turns} ttfa=${ttfaMs}ms dur=${dur}ms avgTtfa=${avg}ms")
    }

    fun beginStt() {
        _uiState.update { it.copy(sttError = null, partialText = "", isListening = true) }
        // A3.2: mở mic → 1 lệnh dừng audio duy nhất (ExoPlayer + engine TTS + pipeline đang đọc).
        stopAudio()
        hybridStt.listenHybrid(
            preferOffline = latestPrefs.sttOfflineFirst,
            onResult = { text, isOffline ->
                val wasListening = _uiState.value.isListening
                _uiState.update { it.copy(isListening = false, partialText = "") }
                if (!wasListening) return@listenHybrid
                if (text.isBlank()) {
                    _uiState.update { it.copy(sttError = "Không nhận diện được") }
                    return@listenHybrid
                }
                if (isOffline) _uiState.update { it.copy(ttsStatus = "STT offline: $text") }
                startVoicePipelineWithHybrid(text)
            },
            onPartial = { p -> _uiState.update { it.copy(partialText = p) } },
            onError = { msg -> _uiState.update { it.copy(isListening = false, sttError = msg) } }
        )
    }

    fun cancelListenFlag() {
        _uiState.update { it.copy(isListening = false) }
    }

    /** Dừng trả lời của màn Voice — đi qua 1 lệnh dừng duy nhất (A3.2). */
    fun stopChat() {
        stopAudio()
    }

    fun startContinuousListening() {
        continuousJob?.cancel()
        _uiState.update { it.copy(continuousListening = true) }
        continuousJob = viewModelScope.launch {
            hybridStt.startContinuousListening(
                maxSeconds = 30,
                onResult = { text ->
                    if (_uiState.value.continuousListening) {
                        _uiState.update { it.copy(isListening = false) }
                        startVoicePipelineWithHybrid(text)
                    }
                },
                onPartial = { p -> _uiState.update { it.copy(partialText = p) } },
                onError = { msg -> _uiState.update { it.copy(sttError = msg) } }
            )
        }
    }

    fun stopContinuousListening() {
        _uiState.update { it.copy(continuousListening = false, isListening = false, partialText = "") }
        continuousJob?.cancel()
        hybridStt.stopContinuousListening()
    }

    fun onPushToTalkPress() {
        _uiState.update { it.copy(sttError = null, isListening = true, partialText = "") }
        viewModelScope.launch {
            val text = hybridStt.captureAndDecodeOnce(maxSeconds = 30)
            if (!_uiState.value.isListening) return@launch
            _uiState.update { it.copy(isListening = false) }
            if (text != null && text.isNotBlank()) {
                startVoicePipelineWithHybrid(text)
            } else {
                // Trước đây im lặng hoàn toàn → user tưởng "app không nhận model".
                _uiState.update { it.copy(sttError = hybridStt.describeFailure()) }
            }
        }
    }

    fun onPushToTalkRelease() {
        if (!_uiState.value.isListening) return
    }

    /** QA debug (DEBUG-only): giả lập 1 lượt thoại không cần mic. */
    fun runQaTurn() {
        if (!BuildConfig.DEBUG) return
        val sttPcm = java.io.File(context.filesDir, "qa_stt.pcm")
            .takeIf { f -> f.canRead() && f.length() > 1000 }
        if (sttPcm != null) {
            hybridStt.testDecodePcm(sttPcm) { report ->
                Log.d("SttTest", "report: $report")
            }
        } else {
            val qa = try {
                java.io.File(context.filesDir, "qa.txt").takeIf { f -> f.canRead() }?.readText()?.trim()
            } catch (_: Exception) { null }
            val q = qa?.takeIf { s -> s.isNotEmpty() }
                ?: "xin chào, kể cho tôi một câu đố vui"
            startVoicePipelineWithHybrid(q)
        }
    }

    override fun onCleared() {
        try { hybridStt.release() } catch (_: Exception) {}
        try { chunker.reset() } catch (_: Exception) {}
        try { pipelineJob?.cancel() } catch (_: Exception) {}
        try { continuousJob?.cancel() } catch (_: Exception) {}
        stopBargeInMonitor()
        // A3.2: KHÔNG shutdown() — engine TTS dùng chung toàn process, màn khác vẫn cần.
        try { offlineTts.stop() } catch (_: Exception) {}
        try { playerVm?.stopPlayback() } catch (_: Exception) {}
        stopListener?.let { l -> try { playerVm?.removeStopListener(l) } catch (_: Exception) {} }
        stopListener = null
        super.onCleared()
    }
}
