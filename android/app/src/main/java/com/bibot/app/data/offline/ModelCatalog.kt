package com.bibot.app.data.offline

import android.content.Context
import com.bibot.app.core.tts.SherpaEngine
import com.bibot.app.data.files.FilePaths
import java.io.File

/**
 * Catalog mô hình offline — user tự tải khi cần (không bundle trong APK).
 * Đặt trong Settings → Mô hình offline (§ menu thích hợp: ⚙️ Cài đặt > Mô hình).
 * Nguồn tương đồng Pi: scripts/install_tts_models.sh --with-phatjkk/--with-k2svi
 */
enum class ModelType { STT, TTS }

data class ModelInfo(
    val id: String, // vosk-vi-small, piper-vi-phatjkk, piper-en-lessac
    val displayName: String, // "Vosk STT tiếng Việt (nhỏ, ~40MB)"
    val emoji: String,
    val type: ModelType,
    val lang: String, // vi/en
    val url: String,
    val fileName: String, // tên file zip/onnx tải về
    val installedSizeMb: Int,
    val description: String,
    val isZip: Boolean = true
) {
    fun destDir(context: Context): File = when (type) {
        // Sherpa STT vi: tar.bz2 extract 4 file vào sherpaViModelDir (KHÔNG phải vosk-*)
        ModelType.STT -> if (id == "sherpa-vi-zipformer") SherpaEngine.sherpaViModelDir(context)
        else FilePaths.voskModelDir(context, lang)
        ModelType.TTS -> FilePaths.offlineModelsDir(context)
    }
    fun destFile(context: Context): File = when (type) {
        ModelType.STT -> File(destDir(context), fileName) // zip tạm, sau unzip xoá
        ModelType.TTS -> {
            // Chuẩn hoá: lưu đúng FilePaths.piperModelFile (piper-vi/en.onnx) để OfflineTtsHelper/FilePaths đồng bộ
            // Riêng en-lessac huggingface gốc là en_US-lessac-medium.onnx → vẫn map về piper-en.onnx + giữ bản gốc để isInstalled check cả 2
            if (lang == "vi") FilePaths.piperModelFile(context, "vi")
            else if (lang == "en") FilePaths.piperModelFile(context, "en")
            else {
                val onnxName = fileName.replace(".tar.gz", ".onnx").replace(".tgz", ".onnx")
                File(FilePaths.offlineModelsDir(context), onnxName)
            }
        }
    }
    fun isInstalled(context: Context): Boolean = when (type) {
        ModelType.STT -> when (id) {
            "sherpa-vi-zipformer" -> SherpaEngine.isSherpaViModelInstalled(context)
            else -> {
                val dir = FilePaths.voskModelDir(context, lang)
                dir.exists() && dir.listFiles()?.isNotEmpty() == true && File(dir, "am/final.mdl").exists()
            }
        }
        ModelType.TTS -> {
            val f = destFile(context)
            // Huggingface en-lessac còn có .onnx.json kèm; check cả legacy path en_US-lessac-medium.onnx để không báo chưa cài sau khi migrate
            val legacy = File(FilePaths.offlineModelsDir(context), fileName)
            val legacy2 = File(FilePaths.offlineModelsDir(context), "en_US-lessac-medium.onnx")
            listOf(f, legacy, legacy2).any { it.exists() && it.length() > 1_000_000 }
        }
    }
    fun installedSize(context: Context): Long = when (type) {
        ModelType.STT -> when (id) {
            "sherpa-vi-zipformer" -> SherpaEngine.sherpaViModelDir(context)
                .walkTopDown().filter { it.isFile }.sumOf { it.length() }
            else -> FilePaths.voskModelDir(context, lang)
                .walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        ModelType.TTS -> destFile(context).takeIf { it.exists() }?.length() ?: 0L
    }

    /** MIME type cho file picker khi cài từ file thiết bị. */
    fun mimeType(): String = when {
        fileName.endsWith(".zip") -> "application/zip"
        fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz") -> "application/gzip"
        fileName.endsWith(".onnx") -> "application/octet-stream"
        else -> "*/*"
    }
}

object ModelCatalog {
    val all: List<ModelInfo> = listOf(
        ModelInfo(
            id = "vosk-vi-small",
            displayName = "Vosk STT tiếng Việt (nhỏ)",
            emoji = "🎙️",
            type = ModelType.STT,
            lang = "vi",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip",
            fileName = "vosk-model-small-vn-0.4.zip",
            installedSizeMb = 42,
            description = "Offline STT VI ~42MB. Dùng khi mất mạng, thay SpeechRecognizer. Độ chính xác vừa, chạy 100% on-device.",
            isZip = true
        ),
        ModelInfo(
            id = "vosk-en-small",
            displayName = "Vosk STT English (small)",
            emoji = "🎙️",
            type = ModelType.STT,
            lang = "en",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            fileName = "vosk-model-small-en-us-0.15.zip",
            installedSizeMb = 40,
            description = "Offline STT EN ~40MB.",
            isZip = true
        ),
        ModelInfo(
            id = "piper-vi-phatjkk",
            displayName = "Piper TTS tiếng Việt (PhatJKK)",
            emoji = "🔊",
            type = ModelType.TTS,
            lang = "vi",
            url = "https://raw.githubusercontent.com/phatjkk/vits-tts-vietnamese/main/pretrained_vi.onnx",
            fileName = "piper-vi-phatjkk.onnx",
            installedSizeMb = 63,
            description = "Giọng VI VITS 22050Hz ~63MB, giống Pi PhatJKK. TTS offline khi Cloud fail.",
            isZip = false
        ),
        ModelInfo(
            id = "piper-en-lessac",
            displayName = "Piper TTS English (Lessac)",
            emoji = "🔊",
            type = ModelType.TTS,
            lang = "en",
            url = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
            fileName = "en_US-lessac-medium.onnx",
            installedSizeMb = 63,
            description = "Giọng EN Lessac medium ~63MB (huggingface). Tải .onnx + .json để dùng offline Piper. Fix 2026-09-23: thay github 404 → huggingface.",
            isZip = false
        ),
        ModelInfo(
            id = "sherpa-vi-zipformer",
            displayName = "Sherpa STT tiếng Việt (Zipformer 30M)",
            emoji = "🎙️",
            type = ModelType.STT,
            lang = "vi",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2",
            fileName = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2",
            installedSizeMb = 35,
            description = "Offline STT VI Zipformer 30M int8 ~35MB. Chạy 100% on-device, độ chính xác cao. Yêu cầu engine sherpa-onnx (tự cài khi tải).",
            isZip = true
        ),
    )
    fun byId(id: String): ModelInfo? = all.find { it.id == id }
    fun sttModels(): List<ModelInfo> = all.filter { it.type == ModelType.STT }
    fun ttsModels(): List<ModelInfo> = all.filter { it.type == ModelType.TTS }
}
