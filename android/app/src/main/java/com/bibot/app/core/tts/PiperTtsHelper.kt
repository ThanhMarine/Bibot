package com.bibot.app.core.tts

import android.content.Context
import android.net.Uri
import com.bibot.app.data.files.FilePaths
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Piper TTS on-device qua sherpa-onnx (STORY_PREFER_PIPER) — giọng VI/EN offline, không tốn quota.
 * Engine (~30MB/ABI) + voice pack tải 1 lần vào no_backup (giữ APK gọn):
 *  - engine: SherpaEngine (tarball v1.13.8, nạp System.load)
 *  - voice EN: csukuangfj/vits-piper-en_US-lessac-medium (.onnx + tokens.txt + espeak-ng-data)
 *  - voice VI: PhatJKK pretrained_vi.onnx + .onnx.json, tokens.txt SUY TỪ phoneme_id_map
 *    (thuật toán đã kiểm chứng: suy từ EN .onnx.json ra file đồng nhất 0 diff với tokens.txt chuẩn),
 *    espeak-ng-data dùng chung với EN.
 */
object PiperTtsHelper {

    private const val EN_REPO = "https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium"
    private const val EN_PACK = "$EN_REPO/resolve/main"
    private const val EN_API_TREE = "https://huggingface.co/api/models/csukuangfj/vits-piper-en_US-lessac-medium/tree/main"
    private const val VI_ONNX_URL = "https://raw.githubusercontent.com/phatjkk/vits-tts-vietnamese/main/pretrained_vi.onnx"
    private const val VI_JSON_URL = "https://raw.githubusercontent.com/phatjkk/vits-tts-vietnamese/main/pretrained_vi.onnx.json"
    // Mirror URLs for Vietnam (if GitHub/HuggingFace blocked)
    private const val VI_ONNX_MIRROR = "https://huggingface.co/phatjkk/vits-tts-vietnamese/resolve/main/pretrained_vi.onnx"
    private const val VI_JSON_MIRROR = "https://huggingface.co/phatjkk/vits-tts-vietnamese/resolve/main/pretrained_vi.onnx.json"
    private const val EN_ONNX_MIRROR = "https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium/resolve/main/en_US-lessac-medium.onnx"
    private const val EN_JSON_MIRROR = "https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium/resolve/main/en_US-lessac-medium.onnx.json"
    private const val ESPEAK_MIRROR_BASE = "https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium/resolve/main/espeak-ng-data"

    fun voiceDir(context: Context, lang: String): File =
        File(FilePaths.offlineModelsDir(context), "sherpa-$lang").apply { mkdirs() }

    fun modelFile(context: Context, lang: String): File =
        File(voiceDir(context, lang), "model.onnx")

    fun tokensFile(context: Context, lang: String): File =
        File(voiceDir(context, lang), "tokens.txt")

    fun espeakDir(context: Context): File =
        File(FilePaths.offlineModelsDir(context), "sherpa-espeak").apply { mkdirs() }

    private fun voiceReadyMarker(context: Context, lang: String): File =
        File(voiceDir(context, lang), ".voice_ready")

    // --- sideload (adb push vào models_in) — deprecated, use file picker instead ---

    @Deprecated("Use installVoiceFromFile with file picker instead")
    private fun stagedOnnx(context: Context, lang: String): File? {
        val dir = FilePaths.modelStagingDir(context)
        val names = if (lang == "vi") listOf("pretrained_vi.onnx", "piper-vi.onnx", "piper-vi-phatjkk.onnx")
                    else listOf("en_US-lessac-medium.onnx", "piper-en.onnx")
        return names.map { File(dir, it) }.firstOrNull { it.exists() && it.length() > 1_000_000 }
    }

    @Deprecated("Use installVoiceFromFile with file picker instead")
    private fun stagedJson(context: Context, lang: String): File? {
        val dir = FilePaths.modelStagingDir(context)
        val names = if (lang == "vi") listOf("pretrained_vi.onnx.json", "piper-vi-phatjkk.onnx.json", "piper-vi.onnx.json")
                    else listOf("en_US-lessac-medium.onnx.json", "piper-en.onnx.json")
        return names.map { File(dir, it) }.firstOrNull { it.exists() && it.length() > 100 }
    }

    @Deprecated("Use installEspeakFromFile with file picker instead")
    fun stagedEspeakZip(context: Context): File? {
        val f = File(FilePaths.modelStagingDir(context), "espeak-ng-data.zip")
        return if (f.exists() && f.length() > 100_000) f else null
    }

    /** Danh sách thành phần sideload đang chờ cài (hiện ở Settings → Cài Piper). */
    @Deprecated("Use file picker buttons instead")
    fun stagedStatus(context: Context): List<String> {
        val out = mutableListOf<String>()
        if (SherpaEngine.hasLocalPack(context)) out += "engine .so"
        if (stagedOnnx(context, "vi") != null) out += "giọng VI"
        if (stagedOnnx(context, "en") != null) out += "giọng EN"
        if (stagedEspeakZip(context) != null) out += "espeak-ng-data"
        return out
    }

    /** Check rẻ (file tồn tại) — dùng trong producer mỗi lượt đọc. */
    fun isVoiceComplete(context: Context, lang: String): Boolean {
        return try {
            voiceReadyMarker(context, lang).exists() &&
                modelFile(context, lang).length() > 1_000_000 &&
                tokensFile(context, lang).exists() &&
                File(espeakDir(context), ".espeak_ready").exists()
        } catch (_: Exception) { false }
    }

    /** Cài voice pack (gọi từ UI, có progress). Engine cài riêng qua SherpaEngine. */
    suspend fun ensureVoice(context: Context, lang: String, onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dir = voiceDir(context, lang)
                // 1) model .onnx — ưu tiên file sideload → file catalog đã tải → tải mạng
                val dest = modelFile(context, lang)
                if (dest.length() < 1_000_000) {
                    val staged = stagedOnnx(context, lang)
                    val existing = if (staged == null) findExistingOnnx(context, lang) else null
                    when {
                        staged != null -> {
                            staged.copyTo(dest, overwrite = true)
                            File(dir, ".model_path").writeText(dest.absolutePath)
                        }
                        existing != null -> {
                            // Trỏ thẳng file catalog (không dup); copy đường dẫn vào marker
                            dest.delete()
                            File(dir, ".model_path").writeText(existing.absolutePath)
                        }
                        else -> {
                            val urls = if (lang == "vi") listOf(VI_ONNX_URL, VI_ONNX_MIRROR)
                            else listOf("$EN_PACK/en_US-lessac-medium.onnx", EN_ONNX_MIRROR)
                            var success = false
                            for (url in urls) {
                                try {
                                    downloadTo(url, dest) { p -> onProgress((p * 40 / 100).coerceIn(0, 40)) }
                                    success = true
                                    break
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    continue
                                }
                            }
                            if (!success) return@withContext false
                            File(dir, ".model_path").writeText(dest.absolutePath)
                        }
                    }
                }
                onProgress(45)
                // 2) tokens.txt — suy từ .onnx.json (có bản sideload thì không tải gì);
                //    EN không suy được thì tải tokens.txt chuẩn
                val tokens = tokensFile(context, lang)
                if (!tokens.exists() || tokens.length() < 10) {
                    stagedJson(context, lang)?.copyTo(File(dir, "model.onnx.json"), overwrite = true)
                    if (!deriveTokensFromJson(context, lang)) {
                        if (lang == "en") {
                            val urls = listOf("$EN_PACK/tokens.txt", "https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium/resolve/main/tokens.txt")
                            var ok = false
                            for (url in urls) {
                                try { downloadTo(url, tokens) { }; ok = true; break } catch (_: Exception) {}
                            }
                            if (!ok) return@withContext false
                        } else return@withContext false
                    }
                }
                onProgress(55)
                // 3) espeak-ng-data dùng chung (1 lần cho mọi giọng)
                ensureEspeakData(context) { p -> onProgress(55 + (p * 45 / 100).coerceIn(0, 45)) }
                voiceReadyMarker(context, lang).writeText(System.currentTimeMillis().toString())
                onProgress(100)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    /** Cài voice pack từ file user chọn (.onnx) qua file picker. */
    suspend fun installVoiceFromFile(context: Context, lang: String, onnxUri: Uri, jsonUri: Uri?, onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dir = voiceDir(context, lang)
                dir.mkdirs()
                val dest = modelFile(context, lang)

                // Copy .onnx file
                context.contentResolver.openInputStream(onnxUri).use { input ->
                    if (input == null) throw Exception("Không đọc được file .onnx")
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(32 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (dest.length() < 1_000_000) {
                    dest.delete()
                    return@withContext false
                }
                File(dir, ".model_path").writeText(dest.absolutePath)
                onProgress(40)

                // Copy .json file if provided (for tokens derivation)
                val jsonFile = File(dir, "model.onnx.json")
                if (jsonUri != null) {
                    context.contentResolver.openInputStream(jsonUri).use { input ->
                        if (input != null) {
                            FileOutputStream(jsonFile).use { out ->
                                val buf = ByteArray(32 * 1024)
                                var n: Int
                                while (input.read(buf).also { n = it } != -1) {
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                }

                // Derive tokens from json
                val tokens = tokensFile(context, lang)
                if (!tokens.exists() || tokens.length() < 10) {
                    if (!deriveTokensFromJson(context, lang)) {
                        if (lang == "en") {
                            // Try download tokens for EN
                            val urls = listOf("$EN_PACK/tokens.txt", "https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium/resolve/main/tokens.txt")
                            var ok = false
                            for (url in urls) {
                                try { downloadTo(url, tokens) { }; ok = true; break } catch (_: Exception) {}
                            }
                            if (!ok) return@withContext false
                        } else return@withContext false
                    }
                }
                onProgress(60)

                // Ensure espeak-ng-data
                ensureEspeakData(context) { p -> onProgress(60 + (p * 40 / 100).coerceIn(0, 40)) }
                voiceReadyMarker(context, lang).writeText(System.currentTimeMillis().toString())
                onProgress(100)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    /** Cài espeak-ng-data từ file zip user chọn qua file picker. */
    suspend fun installEspeakFromFile(context: Context, zipUri: Uri, onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val espeakDir = espeakDir(context)
                espeakDir.mkdirs()
                val marker = File(espeakDir, ".espeak_ready")
                if (marker.exists()) return@withContext true

                val tmpDir = File(FilePaths.cacheRoot(context), "espeak_tmp").apply { mkdirs() }
                val tmpZip = File(tmpDir, "espeak-ng-data.zip")
                if (tmpZip.exists()) tmpZip.delete()

                context.contentResolver.openInputStream(zipUri).use { input ->
                    if (input == null) throw Exception("Không đọc được file espeak-ng-data.zip")
                    FileOutputStream(tmpZip).use { out ->
                        val buf = ByteArray(32 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                        }
                    }
                }

                if (tmpZip.length() < 100_000) {
                    tmpZip.delete()
                    return@withContext false
                }

                // Extract zip
                ZipInputStream(FileInputStream(tmpZip)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(espeakDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buf = ByteArray(8192)
                                var n: Int
                                while (zis.read(buf).also { n = it } != -1) {
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                tmpZip.delete()
                marker.writeText(System.currentTimeMillis().toString())
                onProgress(100)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    private fun findExistingOnnx(context: Context, lang: String): File? {
        val dir = FilePaths.offlineModelsDir(context)
        val candidates = if (lang == "vi") {
            listOf("piper-vi.onnx", "piper-vi-phatjkk.onnx")
        } else {
            listOf("piper-en.onnx", "en_US-lessac-medium.onnx")
        }
        return candidates.map { File(dir, it) }.firstOrNull { it.exists() && it.length() > 1_000_000 }
    }

    fun resolveModelPath(context: Context, lang: String): String {
        val override = File(voiceDir(context, lang), ".model_path")
        val p = try {
            if (override.exists()) override.readText().trim().ifBlank { null } else null
        } catch (_: Exception) { null } ?: modelFile(context, lang).absolutePath
        return p
    }

    /** Suy tokens.txt từ phoneme_id_map trong piper .onnx.json (đã verify 0 diff với bản chuẩn EN). */
    private fun deriveTokensFromJson(context: Context, lang: String): Boolean {
        val dir = voiceDir(context, lang)
        val jsonFile = File(dir, "model.onnx.json")
        if (!jsonFile.exists() || jsonFile.length() < 100) {
            val models = FilePaths.offlineModelsDir(context)
            val names = if (lang == "vi") listOf("pretrained_vi.onnx.json", "piper-vi.onnx.json", "piper-vi-phatjkk.onnx.json")
                        else listOf("en_US-lessac-medium.onnx.json", "piper-en.onnx.json")
            val found = names.map { File(models, it) }.firstOrNull { it.exists() && it.length() > 100 }
            if (found != null) found.copyTo(jsonFile, overwrite = true)
            else {
                val urls = if (lang == "vi") listOf(VI_JSON_URL, VI_JSON_MIRROR)
                else listOf("$EN_PACK/en_US-lessac-medium.onnx.json", EN_JSON_MIRROR)
                var success = false
                for (url in urls) {
                    try { downloadTo(url, jsonFile) { }; success = true; break } catch (_: Exception) {}
                }
                if (!success) return false
            }
        }
        val root = JSONObject(jsonFile.readText())
        val map = root.getJSONObject("phoneme_id_map")
        val byId = sortedMapOf<Int, String>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val phoneme = keys.next()
            val ids = map.getJSONArray(phoneme)
            for (i in 0 until ids.length()) {
                val id = ids.getInt(i)
                if (!byId.containsKey(id)) byId[id] = phoneme
            }
        }
        require(byId.isNotEmpty()) { "phoneme_id_map rỗng" }
        val maxId = byId.keys.max()
        val sb = StringBuilder()
        for (id in 0..maxId) sb.append(byId[id] ?: "_").append(' ').append(id).append('\n')
        tokensFile(context, lang).writeText(sb.toString())
        return true
    }

    /** espeak-ng-data (~392 file nhỏ) — ưu tiên zip sideload, không có list qua HF API rồi tải. */
    private suspend fun ensureEspeakData(context: Context, onProgress: (Int) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val dir = espeakDir(context)
            val marker = File(dir, ".espeak_ready")
            if (marker.exists()) return@withContext
            val stagedZip = stagedEspeakZip(context)
            if (stagedZip != null) {
                extractEspeakZip(stagedZip, dir)
                marker.writeText("staged")
                onProgress(100)
                return@withContext
            }
            val listUrl = "$EN_API_TREE/espeak-ng-data?recursive=true"
            val treeJson = downloadText(listUrl) ?: throw IllegalStateException("Không list được espeak-ng-data")
            val arr = org.json.JSONArray(treeJson)
            val files = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i).optString("path")
                val t = arr.getJSONObject(i).optString("type")
                if (t == "file" && p.startsWith("espeak-ng-data/")) files += p
            }
            if (files.isEmpty()) throw IllegalStateException("espeak-ng-data rỗng")
            var done = 0
            for (rel in files) {
                val out = File(dir, rel.removePrefix("espeak-ng-data/"))
                if (!out.exists() || out.length() == 0L) {
                    out.parentFile?.mkdirs()
                    val urls = listOf(
                        "$EN_REPO/resolve/main/$rel",
                        "$ESPEAK_MIRROR_BASE/${rel.removePrefix("espeak-ng-data/")}"
                    )
                    var ok = false
                    for (url in urls) {
                        try { downloadTo(url, out) { }; ok = true; break } catch (_: Exception) {}
                    }
                    if (!ok) return@withContext
                }
                done++
                onProgress((done * 100 / files.size).coerceIn(0, 100))
            }
            marker.writeText(files.size.toString())
        }

    /** Giải nén espeak-ng-data.zip (sideload) — strip thư mục gốc "espeak-ng-data/". */
    private fun extractEspeakZip(zipFile: File, destDir: File) {
        java.util.zip.ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                var name = entry.name.replace('\\', '/')
                if (name.startsWith("espeak-ng-data/")) name = name.removePrefix("espeak-ng-data/")
                val segs = name.split('/')
                val safe = name.isNotBlank() && !name.endsWith("/") && segs.none { it == ".." }
                if (safe && !entry.isDirectory) {
                    val out = File(destDir, name)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { out2 -> zis.copyTo(out2) }
                }
                entry = zis.nextEntry
            }
        }
    }

    // --- inference ---

    private val ttsCache = mutableMapOf<String, OfflineTts>()
    private val ttsLock = Any()

    private fun getTts(context: Context, lang: String): OfflineTts? {
        synchronized(ttsLock) {
            ttsCache[lang]?.let { return it }
            try {
                if (!SherpaEngine.loaded) return null
                val dir = voiceDir(context, lang)
                val vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
                    model = resolveModelPath(context, lang),
                    lexicon = "",
                    tokens = tokensFile(context, lang).absolutePath,
                    dataDir = espeakDir(context).absolutePath
                )
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu")
                )
                val tts = OfflineTts(config = config)
                ttsCache[lang] = tts
                return tts
            } catch (_: UnsatisfiedLinkError) {
                return null
            } catch (_: Exception) {
                return null
            }
        }
    }

    /** Tổng hợp 1 câu → WAV bytes (PCM 16-bit mono). Null nếu engine/voice chưa sẵn. */
    suspend fun synthesize(context: Context, text: String, lang: String, speed: Float = 1.0f): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                // Phòng thủ: sherpa-ppn đọc nguyên "PAUSE2SEC" (pause do caller lo)
                val plain = com.bibot.app.core.text.TextCleaner.stripPauseMarker(text)
                if (plain.isBlank() || !isVoiceComplete(context, lang)) return@withContext null
                val tts = getTts(context, lang) ?: return@withContext null
                val audio = tts.generate(text = plain, sid = 0, speed = speed)
                if (audio.samples.isEmpty() || audio.sampleRate <= 0) return@withContext null
                floatToWav(audio.samples, audio.sampleRate)
            } catch (_: UnsatisfiedLinkError) {
                null
            } catch (_: Exception) {
                null
            }
        }

    fun release(lang: String? = null) {
        synchronized(ttsLock) {
            try {
                if (lang == null) {
                    ttsCache.values.forEach { try { it.release() } catch (_: Exception) {} }
                    ttsCache.clear()
                } else {
                    try { ttsCache.remove(lang)?.release() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun floatToWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(1) // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2) // byteRate
        buf.putShort(2) // blockAlign
        buf.putShort(16) // bits
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
            buf.putShort(v.toShort())
        }
        return buf.array()
    }

    // --- http helpers (32KB buffer, disconnect finally — như PublicDriveSource) ---

    private fun downloadTo(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            dest.parentFile?.mkdirs()
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Bibot/1.0")
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}: $urlStr")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val b = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(b)
                        if (n <= 0) break
                        output.write(b, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun downloadText(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Bibot/1.0")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
