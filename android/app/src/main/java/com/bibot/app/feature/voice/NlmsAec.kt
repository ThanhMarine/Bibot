package com.bibot.app.feature.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Port Pi `services/aec.py` — NLMS adaptive filter khử echo loa→mic cho duplex thật.
 *
 * Nguyên lý (giữ nguyên Pi):
 * - y[n] = w^T * x[n]  (ước lượng echo từ reference signal x = TTS đã phát)
 * - e[n] = d[n] - y[n]  (error = mic input - estimated echo → đưa vào VAD/STT)
 * - w += leakage*w + μ * e * x / (x^T*x + δ)  (block-NLMS 1 lần/frame, double-talk gate)
 *
 * Params mặc định = Pi: filter_length=256, sr=16k, mu=0.25, delta=1e-6, leakage=0.999.
 * Frame 512 mẫu (32ms @16k): ~260k MAC/frame — nhẹ cho mobile (vài ms Kotlin).
 */
class NlmsAec(
    private val filterLength: Int = 256,
    private val sampleRate: Int = 16000,
    private val mu: Float = 0.25f,
    private val delta: Float = 1e-6f,
    private val leakage: Float = 0.999f
) {
    private var w = FloatArray(filterLength)
    private var xBuffer = FloatArray(filterLength)
    private var nearEndPower = 0.0
    private var farEndPower = 0.0
    private var framesProcessed = 0
    var erleDb = 0.0
        private set

    /** Block-NLMS 1 frame: mic + ref cùng độ dài N → PCM đã trừ echo. */
    fun process(micFrame: FloatArray, refFrame: FloatArray): FloatArray {
        val n = minOf(micFrame.size, refFrame.size)
        if (n == 0) return FloatArray(0)
        val mic = micFrame.copyOf(n)
        val ref = refFrame.copyOf(n)

        var dPow = 0.0
        var fPow = 0.0
        for (i in 0 until n) {
            dPow += mic[i] * mic[i]
            fPow += ref[i] * ref[i]
        }
        dPow /= n
        fPow /= n
        nearEndPower = 0.9 * nearEndPower + 0.1 * dPow
        farEndPower = 0.9 * farEndPower + 0.1 * fPow

        // Lịch sử ref: đuôi cũ + frame mới → convolution đủ taps.
        // y[i] = dot(w, hist[i..i+L-1]) với i = 0..N (N+1 giá trị), lấy N cuối (Pi: y_full[-N:]).
        val hist = FloatArray(filterLength + n)
        xBuffer.copyInto(hist, 0)
        ref.copyInto(hist, filterLength)
        val y = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            val base = i + 1
            for (j in 0 until filterLength) s += w[j] * hist[base + j]
            y[i] = s
        }
        val e = FloatArray(n) { i -> mic[i] - y[i] }

        // Block-NLMS update 1 lần/frame (leakage + double-talk gate — port Pi).
        if (farEndPower > 1e-8 && (nearEndPower / (farEndPower + 1e-12)) < 0.5) {
            var xPow = delta.toDouble()
            for (v in ref) xPow += v * v
            if (xPow > delta) {
                val step = (mu / xPow).toFloat()
                // grad[l] = dot(ext[l..l+N-1], e), ext = N+L-1 mẫu cuối hist
                val extStart = hist.size - (n + filterLength - 1)
                for (l in 0 until filterLength) {
                    var g = 0f
                    for (i in 0 until n) g += hist[extStart + l + i] * e[i]
                    w[l] = leakage * w[l] + step * g
                }
            }
        }

        // Giữ đuôi ref cho frame sau.
        val keep = FloatArray(filterLength)
        if (n >= filterLength) {
            ref.copyInto(keep, 0, n - filterLength, n)
        } else {
            val fromOld = filterLength - n
            xBuffer.copyInto(keep, 0, filterLength - fromOld, filterLength)
            ref.copyInto(keep, fromOld, 0, n)
        }
        xBuffer = keep

        framesProcessed += n
        if (framesProcessed % (sampleRate / 10) < n) {
            var mp = 0.0
            var op = 0.0
            for (i in 0 until n) {
                mp += mic[i] * mic[i]
                op += e[i] * e[i]
            }
            mp /= n
            op /= n
            if (op > 1e-10) erleDb = 10 * log10(mp / op)
        }
        return e
    }

    fun reset() {
        w.fill(0f)
        xBuffer.fill(0f)
        nearEndPower = 0.0
        farEndPower = 0.0
        framesProcessed = 0
        erleDb = 0.0
    }
}

/**
 * Port Pi `AECProcessor` — quản lý reference TTS + xử lý mic theo frame 512.
 * - `feedReference()`: TTS PCM int16 mono + refRate (Piper 22050) → resample tuyến tính về 16k, giữ ~2s.
 * - `processMic()`: không đủ reference → **bypass nguyên bản** (Pi: bản cũ pad zeros + filter giữ
 *   state cũ → trừ echo ảo, méo tiếng mic sạch, STT tệ hơn cả khi tắt AEC).
 */
class AecProcessor(
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 512,
    filterLength: Int = 256,
    var enabled: Boolean = true
) {
    private val aec = NlmsAec(filterLength = filterLength, sampleRate = sampleRate)
    private var refBuffer = FloatArray(0)

    fun feedReference(pcm: ShortArray, refRate: Int = 22050) {
        if (!enabled || pcm.isEmpty()) return
        var ref = FloatArray(pcm.size) { i -> pcm[i] / 32768f }
        if (refRate != sampleRate && ref.size > 1) {
            val nOut = maxOf(1, (ref.size * sampleRate / refRate))
            val out = FloatArray(nOut)
            for (i in 0 until nOut) {
                val pos = i.toFloat() * (ref.size - 1) / (nOut - 1).coerceAtLeast(1)
                val lo = pos.toInt().coerceIn(0, ref.size - 1)
                val hi = (lo + 1).coerceAtMost(ref.size - 1)
                val frac = pos - lo
                out[i] = ref[lo] * (1 - frac) + ref[hi] * frac
            }
            ref = out
        }
        val merged = FloatArray(refBuffer.size + ref.size)
        refBuffer.copyInto(merged, 0)
        ref.copyInto(merged, refBuffer.size)
        refBuffer = merged
        val maxKeep = sampleRate * 2
        if (refBuffer.size > maxKeep) refBuffer = refBuffer.copyOfRange(refBuffer.size - maxKeep, refBuffer.size)
    }

    /** Mic PCM16 → PCM16 đã khử echo (frame 512, phần lẻ giữ nguyên). */
    fun processMic(mic: ShortArray): ShortArray {
        if (!enabled) return mic
        if (refBuffer.size < mic.size) return mic // bypass — port Pi
        val out = ShortArray(mic.size)
        var pos = 0
        while (pos < mic.size) {
            val end = minOf(pos + frameSize, mic.size)
            val m = FloatArray(end - pos) { i -> mic[pos + i] / 32768f }
            val r = refBuffer.copyOfRange(pos, pos + (end - pos))
            val e = aec.process(m, r)
            for (i in e.indices) {
                val v = (e[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                out[pos + i] = v
            }
            pos = end
        }
        refBuffer = refBuffer.copyOfRange(minOf(mic.size, refBuffer.size), refBuffer.size)
        return out
    }

    fun pendingRefSamples(): Int = refBuffer.size

    fun setAecEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (enabled) aec.reset()
    }

    fun reset() = aec.reset()
    fun erleDb(): Double = aec.erleDb
}

/** Parse WAV PCM16 mono 44-byte header (output của PiperTtsHelper.floatToWav) → PCM + sampleRate. */
fun parseWavPcm16(wav: ByteArray): Pair<ShortArray, Int>? {
    if (wav.size < 44) return null
    if (wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte()) return null
    fun le32(off: Int): Int =
        (wav[off].toInt() and 0xff) or ((wav[off + 1].toInt() and 0xff) shl 8) or
            ((wav[off + 2].toInt() and 0xff) shl 16) or ((wav[off + 3].toInt() and 0xff) shl 24)
    fun le16(off: Int): Int = (wav[off].toInt() and 0xff) or ((wav[off + 1].toInt() and 0xff) shl 8)
    if (le16(20) != 1) return null // PCM only
    if (le16(22) != 1) return null // mono only
    if (le16(34) != 16) return null // 16-bit only
    val rate = le32(24)
    if (rate <= 0) return null
    val dataSize = le32(40)
    val start = 44
    if (start + dataSize > wav.size) return null
    val n = dataSize / 2
    val pcm = ShortArray(n)
    for (i in 0 until n) {
        val lo = wav[start + i * 2].toInt() and 0xff
        val hi = wav[start + i * 2 + 1].toInt()
        pcm[i] = ((hi shl 8) or lo).toShort()
    }
    return pcm to rate
}

/** RMS kiểu Pi `audioop.rms` (int16 units) cho 1 chunk mic. */
fun micRms(pcm: ShortArray, size: Int = pcm.size): Double {
    if (size <= 0) return 0.0
    var sum = 0.0
    val n = minOf(size, pcm.size)
    for (i in 0 until n) {
        val v = pcm[i].toDouble()
        sum += v * v
    }
    return sqrt(sum / n)
}
