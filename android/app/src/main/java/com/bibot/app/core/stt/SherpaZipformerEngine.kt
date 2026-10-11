package com.bibot.app.core.stt

import android.content.Context
import android.util.Log
import com.bibot.app.core.tts.SherpaEngine
import com.bibot.app.data.files.FilePaths
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * STT offline sherpa-onnx Zipformer vi-30M-int8 — port Pi `services/local_asr.py`
 * (`create_recognizer`/`is_available`) + `offline_listen.py::decode_pcm`.
 *
 * - Model cùng bộ với Pi: sherpa-onnx-zipformer-vi-30M-int8-2026-02-09
 *   (encoder.int8.onnx, decoder.onnx, joiner.int8.onnx, tokens.txt — ~34MB),
 *   đặt no_backup/bibot/models/sherpa-vi/ (adb run-as sideload, không bắt buộc).
 * - Native: dùng chung engine pack sherpa-onnx v1.13.8 với TTS (SherpaEngine —
 *   bindings OfflineRecognizer/OfflineStream vendored tại com/k2fsa/sherpa/onnx).
 * - Recognizer singleton giữ suốt process (Pi cache `_RECOGNIZER`), numThreads=2
 *   (Pi STT_LOCAL_THREADS=2).
 * - decode: PCM16 16k → float32 /32768 (Pi decode_pcm), min 0.2s, clean_text
 *   homophone đồng bộ Pi.
 */
object SherpaZipformerEngine {
    private const val TAG = "SherpaStt"
    const val SAMPLE_RATE = 16000
    private const val NUM_THREADS = 2 // Pi STT_LOCAL_THREADS

    private val lock = Any()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var initFailed = false

    fun modelDir(context: Context): File = SherpaEngine.sherpaViModelDir(context)

    /** Pi `is_available()`: đủ 4 file model (không đụng native — gọi thoải mái). */
    fun isModelAvailable(context: Context): Boolean {
        return SherpaEngine.isSherpaViModelInstalled(context)
    }

    /**
     * Decode PCM16 mono 16kHz → text. Trả về "" (quá ngắn/rỗng) hoặc null
     * (engine không sẵn sàng/lỗi native — caller fallback engine khác).
     * Gọi trên thread IO; recognizer tạo 1 lần, giữ suốt process.
     */
    fun decode(context: Context, pcm: ShortArray): String? {
        if (pcm.size < SAMPLE_RATE / 5) return "" // Pi: <0.2s → ""
        val rec = ensureRecognizer(context) ?: return null
        return try {
            val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
            val stream = rec.createStream()
            val text = try {
                stream.acceptWaveform(floats, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
            Log.d(TAG, "decoded ${pcm.size} samples -> ${text.length} chars")
            normalize(text)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "native missing (SherpaEngine chưa ensureInstalled?): $e")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "decode failed: $e")
            null
        }
    }

    /** Normalize STT output: lowercase + cleanText homophone + whitespace trim. */
    private fun normalize(text: String): String {
        var t = text.lowercase()
        // homophone replacements (Pi dict, lowercase keys)
        val replacements = mapOf(
            "hd my" to "hdmi",
            "ubia" to "usb",
            "upc" to "usb-c",
            "gram" to "ram",
            "i nắm" to "core i5",
            "phun" to "full",
            "trang viên" to "tràn viền",
            "bánh luôn" to "bằng aluminum",
        )
        for ((old, new) in replacements) t = t.replace(old, new)
        // whitespace normalize
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Tạo/cached recognizer (Pi `create_recognizer`). Engine native phải đã ensureInstalled trước. */
    private fun ensureRecognizer(context: Context): OfflineRecognizer? {
        recognizer?.let { return it }
        if (initFailed) return null
        synchronized(lock) {
            recognizer?.let { return it }
            if (!isModelAvailable(context)) return null
            val dir = modelDir(context)
            return try {
                val cfg = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = File(dir, "encoder.int8.onnx").absolutePath,
                            decoder = File(dir, "decoder.onnx").absolutePath,
                            joiner = File(dir, "joiner.int8.onnx").absolutePath,
                        ),
                        numThreads = NUM_THREADS,
                        debug = false,
                        provider = "cpu",
                        // Zipformer v1 (hynt/Zipformer-30M-RNNT-6000h) — Pi ghim online
                        // "model_type=zipformer" vì auto-detect thử zipformer2 trước; offline
                        // Python để auto nhưng mình set thẳng cho chắc.
                        modelType = "zipformer",
                        tokens = File(dir, "tokens.txt").absolutePath,
                    ),
                )
                val rec = OfflineRecognizer(assetManager = null, config = cfg)
                recognizer = rec
                Log.d(TAG, "recognizer ready (numThreads=$NUM_THREADS)")
                rec
            } catch (e: Throwable) {
                Log.w(TAG, "create recognizer failed: $e")
                initFailed = true
                null
            }
        }
    }
}
