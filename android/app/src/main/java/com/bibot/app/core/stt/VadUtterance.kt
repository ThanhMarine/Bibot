package com.bibot.app.core.stt

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Port Pi `services/offline_listen.py::_listen_once_vad` (Mode 13 "hoạt động rất tốt" trên Pi 4):
 * Energy-VAD thích nghi, chốt 1 utterance → caller decode (sherpa Zipformer / Vosk).
 *
 * - Ngưỡng: 2.0 × noise nền (EMA), kẹp [0.002, 0.02] — chốt 1 lần/turn như Pi (line 244).
 * - Noise EMA sống x\u00e1c các turn trong process (Pi `_noise_ema` global).
 * - pre-roll 300ms (ring 500ms = pre+2): giữ khởi âm tiếng Việt c/t/h dưới ngưỡng.
 * - tail pad 200ms silence chốt cuối + endpoint sau 0.8s im, bỏ câu < 0.3s (blip).
 * - Timeout: chốt cưỡng bức phần đang nói dở thay vì vứt 8s audio.
 *
 * Khác Python (để test được với file): caller cấp nowSec cho từng chunk (mic = đồng hồ
 * thật, test file = virtual clock 100ms/chunk).
 */
class VadUtterance(
    sampleRate: Int = 16000,
    chunkSamples: Int = 1600,               // Pi CHUNK_SIZE = 1600 = 100ms @16k
    private val silenceSec: Double = 0.8,   // STT_VAD_SILENCE
    private val minSpeechSec: Double = 0.3, // STT_VAD_MIN_SPEECH
    preRollMs: Int = 300,                   // STT_PRE_ROLL_MS
    tailPadMs: Int = 200,                   // STT_TAIL_PAD_MS
) {
    private val chunkMs = max(1, chunkSamples * 1000 / sampleRate)
    private val preRollSlots = max(1, preRollMs / chunkMs) + 2 // Pi: deque(maxlen = pre+2) ≈ 500ms
    private val tailChunks = max(0, tailPadMs / chunkMs)

    /** Ngưỡng chốt tại turn start (Pi `_vad_threshold()` gọi 1 lần ở đầu _listen_once_vad). */
    val threshold: Double = computeThreshold()

    private val preRoll = ArrayDeque<FloatArray>(preRollSlots)
    private var buffer: MutableList<FloatArray>? = null
    private var isSpeaking = false
    private var speechStart = 0.0
    private var lastSpeech = 0.0
    private var finished = false
    private var utterance: FloatArray? = null

    var maxRms = 0.0
        private set
    var chunkCount = 0
        private set
    var forcedEndpoint = false
        private set

    /**
     * Feed 1 chunk (mono float32 ~[-1,1]). Trả về true khi turn đã chốt
     * (endpoint đạt — có utterance hoặc blip bị loại).
     */
    fun feed(samples: FloatArray, nowSec: Double): Boolean {
        if (finished) return true
        chunkCount++
        val rms = rmsOf(samples)
        if (rms > maxRms) maxRms = rms
        if (rms < threshold) noteNoise(rms)

        if (rms > threshold) {
            if (!isSpeaking) {
                isSpeaking = true
                speechStart = nowSec
                // Seed "đoạn liền trước": giữ khởi âm (bug cũ del audio_buffer = mất 100-300ms đầu)
                buffer = preRoll.map { it.copyOf() }.toMutableList()
            }
            lastSpeech = nowSec
            buffer!!.add(samples.copyOf())
        } else if (isSpeaking) {
            // "đoạn liền sau": vẫn thu silence đuôi cho endpoint + decoder đủ ngữ cảnh
            buffer!!.add(samples.copyOf())
            if (nowSec - lastSpeech > silenceSec) {
                isSpeaking = false
                val duration = lastSpeech - speechStart
                if (duration >= minSpeechSec) {
                    utterance = buildUtterance(buffer!!)
                }
                finished = true
            }
        } else {
            // Chưa nói: chỉ nuôi ring pre-roll
            preRoll.addLast(samples.copyOf())
            if (preRoll.size > preRollSlots) preRoll.removeFirst()
        }
        return finished
    }

    /** Timeout/EOF: chốt cưỡng bức nếu còn đang nói dở (Pi lines 322-338). */
    fun forceEnd(nowSec: Double) {
        if (finished) return
        if (utterance == null && isSpeaking) {
            val duration = nowSec - speechStart
            if (duration >= minSpeechSec && buffer != null && buffer!!.isNotEmpty()) {
                utterance = buildUtterance(buffer!!)
                forcedEndpoint = true
            }
        }
        finished = true
    }

    /** Lấy utterance PCM float (đã chèn tail silence), null nếu turn không có câu. */
    fun takeUtterance(): FloatArray? {
        val u = utterance
        utterance = null
        return u
    }

    private fun buildUtterance(buf: List<FloatArray>): FloatArray {
        // Pi: np.concatenate(buffer + tail zeros) — nối phẳng, tail = zeros.
        val total = buf.sumOf { it.size } + tailChunks * buf[0].size
        val out = FloatArray(total) // FloatArray init = 0.0 → tail zeros sẵn
        var pos = 0
        for (c in buf) {
            c.copyInto(out, pos)
            pos += c.size
        }
        return out
    }

    private fun rmsOf(s: FloatArray): Double {
        var sum = 0.0
        for (x in s) sum += x.toDouble() * x
        return sqrt(sum / s.size)
    }

    private fun noteNoise(rms: Double) {
        if (rms < 0.05) {
            noiseEma = 0.98 * noiseEma + 0.02 * rms
        }
    }

    private fun computeThreshold(): Double = min(0.02, max(0.002, 2.0 * noiseEma))

    companion object {
        /** Pi `_noise_ema`: sống x\u00e1c các lượt nghe trong process. */
        @Volatile private var noiseEma = 0.002

        /** PCM float [-1,1] → PCM16 LE (Pi: clip × 32767). */
        fun toPcm16(samples: FloatArray): ShortArray {
            val out = ShortArray(samples.size)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767f)
                out[i] = v.toInt().toShort()
            }
            return out
        }
    }
}
