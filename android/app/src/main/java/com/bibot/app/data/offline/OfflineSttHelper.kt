package com.bibot.app.data.offline

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.bibot.app.core.stt.SherpaZipformerEngine
import com.bibot.app.core.stt.VadUtterance
import com.bibot.app.core.tts.SherpaEngine
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline STT — pipeline kế thừa Pi (services/offline_listen.py):
 *   mic 16k → VAD adaptive ([VadUtterance] = port `_listen_once_vad`: pre-roll 300ms,
 *   tail 200ms, silence 0.8s, min-speech 0.3s, ngưỡng 2×noise EMA)
 *   → decode sherpa Zipformer vi ([SherpaZipformerEngine] = port `local_asr.create_recognizer`)
 *   → fallback Vosk (cùng audio đã capture, nếu sherpa thiếu model/decode rỗng).
 *
 * Model sherpa (sideload, không bắt buộc): no_backup/bibot/models/sherpa-vi/
 *   encoder.int8.onnx + decoder.onnx + joiner.int8.onnx + tokens.txt (~34MB, bộ của Pi).
 * Model Vosk (fallback): no_backup/bibot/models/vosk-vi/ — tải trong Settings → Mô hình offline.
 */
class OfflineSttHelper(private val context: Context) {

    fun isModelAvailable(): Boolean =
        SherpaZipformerEngine.isModelAvailable(context) || pickVoskModelDir() != null

    /** Ưu tiên VI, thiếu thì EN nếu user chỉ cài EN (giữ nguyên hành vi cũ). */
    private fun pickVoskModelDir(): java.io.File? {
        val vi = FilePaths.voskModelDir(context, "vi")
        if (java.io.File(vi, "am/final.mdl").exists()) return vi
        val en = FilePaths.voskModelDir(context, "en")
        if (java.io.File(en, "am/final.mdl").exists()) return en
        return null
    }

    suspend fun recognizeOffline(
        maxSeconds: Int = 8,
        onPartial: (String) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val sherpaOk = SherpaZipformerEngine.isModelAvailable(context) &&
            SherpaEngine.ensureInstalled(context)
        if (!sherpaOk && pickVoskModelDir() == null) return@withContext null

        // 1) Capture: VAD chốt câu (1 lần, dùng chung cho cả 2 engine) — như Pi.
        val pcm = captureVad(maxSeconds)
        if (pcm == null) return@withContext null // timeout không có utterance (đã log)

        // 2) Decode: sherpa trước → Vosk cùng audio nếu rỗng/lỗi.
        if (sherpaOk) {
            val text = SherpaZipformerEngine.decode(context, pcm)
            if (!text.isNullOrBlank()) return@withContext text
        }
        decodeVosk(pcm)?.takeIf { it.isNotBlank() }
    }

    /** Port `_listen_once_vad`: đọc mic 100ms/chunk, trả PCM16 utterance (tail đã chèn) hoặc null. */
    private fun captureVad(maxSeconds: Int): ShortArray? {
        val frameBytes = 3200 // 1600 samples × 2B = 100ms @16k — bằng Pi CHUNK_SIZE
        val minBuf = AudioRecord.getMinBufferSize(
            SherpaZipformerEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { Log.w(TAG, "getMinBufferSize=$minBuf"); return null }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SherpaZipformerEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: Throwable) { Log.w(TAG, "AudioRecord ctor failed: $e"); return null }

        val vad = VadUtterance()
        val tmp = ByteArray(frameBytes)
        val frame = ByteArray(frameBytes)
        var fill = 0
        val floats = FloatArray(1600)
        var done = false
        val deadlineMs = System.currentTimeMillis() + maxSeconds * 1000L
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized (quyền mic?)"); return null
            }
            recorder.startRecording()
            while (!done && System.currentTimeMillis() < deadlineMs) {
                val n = recorder.read(tmp, 0, tmp.size)
                if (n <= 0) continue
                var off = 0
                while (off < n && !done) {
                    val take = minOf(n - off, frameBytes - fill)
                    System.arraycopy(tmp, off, frame, fill, take)
                    fill += take; off += take
                    if (fill == frameBytes) {
                        bytesToFloats(frame, 0, floats)
                        done = vad.feed(floats, System.currentTimeMillis() / 1000.0)
                        fill = 0
                    }
                }
            }
            if (!done) vad.forceEnd(System.currentTimeMillis() / 1000.0)
        } catch (e: Throwable) {
            Log.w(TAG, "VAD capture failed: $e"); return null
        } finally {
            try { recorder.stop() } catch (_: Throwable) {}
            recorder.release()
        }
        val utt = vad.takeUtterance()
        // Chẩn đoán 1 dòng/lượt (Pi print): nhìn max_rms là biết audio vào + ngưỡng đủ không.
        Log.d(TAG, "VAD turn max_rms=%.5f thr=%.5f chunks=%d forced=%s got=%s".format(
            vad.maxRms, vad.threshold, vad.chunkCount, vad.forcedEndpoint, utt != null
        ))
        return utt?.let { VadUtterance.toPcm16(it) }
    }

    /** Decode PCM16 bằng Vosk (reflection, giữ nguyên như cũ) — nhận utterance cố định. */
    private fun decodeVosk(pcm: ShortArray): String? {
        val modelDir = pickVoskModelDir() ?: return null
        return try {
            val modelCls = Class.forName("org.vosk.Model")
            val recCls = Class.forName("org.vosk.Recognizer")
            val model = modelCls.getConstructor(String::class.java).newInstance(modelDir.absolutePath)
            val rec = recCls.getConstructor(modelCls, Float::class.javaPrimitiveType).newInstance(model, 16000f)
            try {
                // Vosk file-decode recipe: cho trailing silence (utterance VAD ~1.0s + 1s zeros)
                // để endpoint trigger rồi getResult.
                val extra = ShortArray(pcm.size + SherpaZipformerEngine.SAMPLE_RATE)
                pcm.copyInto(extra)
                val bytes = shortArrayToPcm16Le(extra)
                val recognize = recCls.getMethod("acceptWaveForm", ByteArray::class.java, Int::class.javaPrimitiveType)
                val getResult = recCls.getMethod("getResult")
                recognize.invoke(rec, bytes, bytes.size)
                val json = getResult.invoke(rec) as String
                org.json.JSONObject(json).optString("text").takeIf { it.isNotBlank() }
            } finally {
                recCls.getMethod("close").invoke(rec)
                modelCls.getMethod("close").invoke(model)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Vosk decode failed: $e"); null
        }
    }

    /**
     * QA debug (long-press title khi có files/qa_stt.pcm): chạy VAD + decode trên file
     * PCM16 16k mono với virtual clock 100ms/chunk — test pipeline offline không cần mic.
     */
    fun testDecodePcm(file: java.io.File, onDone: (String) -> Unit) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val report = try {
                val bytes = file.readBytes()
                val vad = VadUtterance()
                val floats = FloatArray(1600)
                var done = false
                var i = 0
                var chunkIdx = 0
                while (!done && i + 3200 <= bytes.size) {
                    bytesToFloats(bytes, i, floats)
                    done = vad.feed(floats, chunkIdx * 0.1)
                    chunkIdx++; i += 3200
                }
                if (!done) vad.forceEnd(chunkIdx * 0.1)
                val utt = vad.takeUtterance()
                val sb = StringBuilder(
                    "vad: chunks=%d maxRms=%.5f thr=%.5f forced=%s got=%s len=%d".format(
                        vad.chunkCount, vad.maxRms, vad.threshold, vad.forcedEndpoint,
                        utt != null, utt?.size ?: 0
                    )
                )
                if (utt != null) {
                    val pcm = VadUtterance.toPcm16(utt)
                    if (SherpaZipformerEngine.isModelAvailable(context)) {
                        val t = if (SherpaEngine.ensureInstalled(context))
                            SherpaZipformerEngine.decode(context, pcm) else null
                        sb.append(" | sherpa=").append(
                            when (t) { null -> "ERR/engine"; "" -> "(blank)"; else -> "\"$t\"" }
                        )
                    } else sb.append(" | sherpa=no-model")
                    sb.append(" | vosk=").append(decodeVosk(pcm)?.let { "\"$it\"" } ?: "n/a")
                }
                sb.toString()
            } catch (e: Throwable) {
                "error: $e"
            }
            Log.d("SttTest", report)
            onDone(report)
        }
    }

    companion object {
        private const val TAG = "OfflineStt"

        /** PCM16 LE bytes (offset 0) → float32 [-1,1]. */
        private fun bytesToFloats(src: ByteArray, offset: Int, out: FloatArray) {
            for (i in out.indices) {
                val lo = src[offset + i * 2].toInt() and 0xff
                val hi = src[offset + i * 2 + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort() / 32768f
            }
        }

        private fun shortArrayToPcm16Le(src: ShortArray): ByteArray {
            val bytes = ByteArray(src.size * 2)
            for (i in src.indices) {
                bytes[i * 2] = (src[i].toInt() and 0xff).toByte()
                bytes[i * 2 + 1] = ((src[i].toInt() shr 8) and 0xff).toByte()
            }
            return bytes
        }
    }

    /** Continuous hands-free listening: loop VAD → decode → callback */
    private var continuousJob: Job? = null
    @Volatile private var continuousRunning = false

    fun startContinuousListening(
        maxSeconds: Int = 30,
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        continuousJob?.cancel()
        continuousRunning = true
        continuousJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            while (continuousRunning) {
                if (!continuousRunning) break
                try {
                    val pcm = captureVad(maxSeconds) ?: continue
                    val text = if (SherpaZipformerEngine.isModelAvailable(context) && SherpaEngine.ensureInstalled(context))
                        SherpaZipformerEngine.decode(context, pcm) else null
                    if (!continuousRunning) break
                    if (text != null && text.isNotBlank()) onResult(text)
                    else onPartial("")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    break
                } catch (e: Throwable) {
                    Log.w(TAG, "Continuous STT error: $e")
                    if (continuousRunning) delay(1000)
                }
            }
        }
    }

    fun stopContinuousListening() {
        continuousRunning = false
        continuousJob?.cancel()
    }

    /** Push-to-talk: capture VAD once then decode with sherpa (offline priority) */
    suspend fun captureAndDecodeOnce(maxSeconds: Int = 30): String? = withContext(Dispatchers.IO) {
        val pcm = captureVad(maxSeconds) ?: return@withContext null
        SherpaZipformerEngine.decode(context, pcm)
    }
}

/**
 * Hybrid manager: thử online trước, fail (network/no match) → offline (VAD + sherpa/Vosk).
 * Đảo được qua DataStore sttOfflineFirst=true (thử offline trước).
 */
class HybridSttManager(private val context: Context) {
    private val online = com.bibot.app.core.stt.SttBridge(context)
    private val offline = OfflineSttHelper(context)

    fun listenHybrid(
        preferOffline: Boolean = false,
        onResult: (String, isOffline: Boolean) -> Unit,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit
    ) {
        if (preferOffline && offline.isModelAvailable()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launchWithOffline(onResult, onPartial, onError)
            return
        }
online.startListening(
                onResult = { text -> onResult(text, false) },
                onPartial = onPartial,
                onError = { code, msg ->
                    // ERROR_NETWORK(2)/NO_MATCH(7)/SERVER(4)/SPEECH_TIMEOUT(8) → fallback offline nếu có model
                    if (code in listOf(2, 7, 4, 8) && offline.isModelAvailable()) {
                        Log.d("HybridStt", "Fallback offline triggered: code=$code, modelAvailable=${offline.isModelAvailable()}")
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launchWithOffline(onResult, onPartial, onError)
                    } else {
                        Log.d("HybridStt", "No fallback: code=$code, modelAvailable=${offline.isModelAvailable()}")
                        onError("Online STT lỗi ($code): $msg")
                    }
                }
            )
    }

    private fun kotlinx.coroutines.CoroutineScope.launchWithOffline(
        onResult: (String, Boolean) -> Unit, onPartial: (String) -> Unit, onError: (String) -> Unit
    ) {
        launch(kotlinx.coroutines.Dispatchers.Main) {
            val r = offline.recognizeOffline(onPartial = onPartial)
            if (r != null) onResult(r, true) else onError(
                "Offline STT chưa có model (sherpa-vi hoặc Vosk) hoặc không nhận diện được. " +
                    "Chọn 'Cài từ file' ở Settings → Mô hình offline để chọn file model từ thiết bị."
            )
        }
    }

    /** QA debug: decode file PCM16 16k mono qua VAD + sherpa/Vosk (xem OfflineSttHelper.testDecodePcm). */
    fun testDecodePcm(file: java.io.File, onDone: (String) -> Unit) = offline.testDecodePcm(file, onDone)

    /** Push-to-talk: capture VAD once then decode with sherpa (offline priority) */
    suspend fun captureAndDecodeOnce(maxSeconds: Int = 30): String? = offline.captureAndDecodeOnce(maxSeconds)

    /** Continuous hands-free listening */
    fun startContinuousListening(
        maxSeconds: Int = 30,
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit,
        onError: (String) -> Unit
    ) = offline.startContinuousListening(maxSeconds, onResult, onPartial, onError)

    fun stopContinuousListening() = offline.stopContinuousListening()

    fun release() {
        try { online.stopListening() } catch (_: Exception) {}
    }
}
