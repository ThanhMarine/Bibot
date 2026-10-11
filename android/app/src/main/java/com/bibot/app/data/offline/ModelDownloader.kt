package com.bibot.app.data.offline

import android.content.Context
import android.net.Uri
import com.bibot.app.core.tts.SherpaEngine
import com.bibot.app.data.files.FilePaths
import com.bibot.app.data.offline.ModelInfo
import com.bibot.app.data.offline.ModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val percent: Int, val bytesDone: Long, val total: Long) : DownloadState()
    data class Unzipping(val percent: Int) : DownloadState()
    data class Done(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Tải model theo chuẩn Android: cache → verify → unzip → move to no_backup/models
 * - Không cần quyền storage (app-specific dirs)
 * - Hỗ trợ resume không? V1 tải lại từ đầu nếu file dở
 * - Dùng HttpURLConnection (không thêm OkHttp để nhẹ)
 */
class ModelDownloader(private val context: Context) {

    private val _states = mutableMapOf<String, MutableStateFlow<DownloadState>>()
    fun stateFlow(id: String): StateFlow<DownloadState> = _states.getOrPut(id) { MutableStateFlow(DownloadState.Idle) }

    suspend fun download(model: ModelInfo, onProgress: (Int) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        val stateFlow = _states.getOrPut(model.id) { MutableStateFlow(DownloadState.Idle) }
        try {
            // Temp file in cache (declare early for space check cleanup)
            val tmpDir = File(FilePaths.cacheRoot(context), "models_tmp").apply { mkdirs() }
            val tmpFile = File(tmpDir, model.fileName)
            if (tmpFile.exists()) tmpFile.delete()

            // Check space
            val free = FilePaths.cacheRoot(context).freeSpace
            if (free < model.installedSizeMb * 1024 * 1024 * 2) {
                tmpFile.delete()
                stateFlow.value = DownloadState.Error("Thiếu dung lượng (cần ~${model.installedSizeMb * 2}MB)")
                return@withContext Result.failure(Exception("low space"))
            }

            // Download from network
            stateFlow.value = DownloadState.Downloading(0, 0, -1)
            val url = URL(model.url)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("User-Agent", "Bibot/1.0")
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                val msg = "HTTP ${conn.responseCode}: ${conn.responseMessage}"
                stateFlow.value = DownloadState.Error(msg)
                return@withContext Result.failure(Exception(msg))
            }
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    var lastPct = -1
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                stateFlow.value = DownloadState.Downloading(pct, done, total)
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            conn.disconnect()

            // Verify size
            if (tmpFile.length() < 500_000) {
                tmpFile.delete()
                stateFlow.value = DownloadState.Error("File tải quá nhỏ, có thể link hỏng")
                return@withContext Result.failure(Exception("small file"))
            }

            // Sherpa STT vi: tar.bz2 (bzip2 — KHÔNG phải zip/gzip) → extract 4 file
            // model vào sherpaViModelDir. Nhánh riêng vì untarGzFile/unzipWithProgress
            // đều fail với bzip2 (lỗi "nhấn Tải nhưng báo lỗi" trước đây).
            if (model.id == "sherpa-vi-zipformer") {
                return@withContext installSherpaModel(tmpFile, stateFlow, onProgress)
            }

            // Install: unzip or move
            var source: File = tmpFile
            if (model.isZip) {
                stateFlow.value = DownloadState.Unzipping(0)
                val destDir = model.destDir(context)
                // Clean old dir for STT
                if (model.type == ModelType.STT && destDir.exists()) destDir.deleteRecursively()
                destDir.mkdirs()

                // Handle .zip vs .tar.gz
                if (model.fileName.endsWith(".tar.gz") || model.fileName.endsWith(".tgz")) {
                    untarGzFile(source, destDir, stateFlow)
                    // Tìm file .onnx bên trong và đổi tên
                    val extractedOnnx = destDir.walkTopDown().find { it.name.endsWith(".onnx") && it.isFile }
                    if (extractedOnnx != null) {
                        val targetName = model.fileName.replace(".tar.gz", ".onnx").replace(".tgz", ".onnx")
                        val target = File(destDir, targetName)
                        if (extractedOnnx.absolutePath != target.absolutePath) {
                            extractedOnnx.renameTo(target)
                        }
                    }
                    if (source === tmpFile) tmpFile.delete()
                    stateFlow.value = DownloadState.Done(destDir)
                } else {
                    unzipWithProgress(source, destDir, stateFlow)
                    if (source === tmpFile) tmpFile.delete()
                    stateFlow.value = DownloadState.Done(destDir)
                }
            } else {
                // .onnx single file
                val dest = model.destFile(context)
                dest.parentFile?.mkdirs()
                source.copyTo(dest, overwrite = true)
                // Piper cần cả .json companion (en-lessac huggingface)
                if (model.id == "piper-en-lessac") {
                    try {
                        // Giữ bản gốc huggingface name để tương thích check legacy
                        val legacyCopy = File(FilePaths.offlineModelsDir(context), model.fileName)
                        if (legacyCopy.absolutePath != dest.absolutePath) source.copyTo(legacyCopy, overwrite = true)
                        // .json companion: tải từ mạng
                        val jsonDest = File(dest.parentFile, dest.name + ".json")
                        val jsonLegacy = File(FilePaths.offlineModelsDir(context), model.fileName + ".json")
                        downloadExtraFile(model.url + ".json", jsonDest)
                        if (jsonDest.exists() && jsonLegacy.absolutePath != jsonDest.absolutePath) {
                            jsonDest.copyTo(jsonLegacy, overwrite = true)
                        }
                    } catch (_: Exception) {}
                }
                if (source === tmpFile) tmpFile.delete()
                stateFlow.value = DownloadState.Done(dest)
            }

            Result.success(
                if (model.isZip && !model.fileName.endsWith(".tar.gz") && !model.fileName.endsWith(".tgz")) model.destDir(context)
                else model.destFile(context)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            val msg = e.message ?: "Lỗi tải: $e"
            _states[model.id]?.value = DownloadState.Error(msg)
            Result.failure(e)
        }
    }

    /** Cài model từ file user chọn (Storage Access Framework / file picker). */
    suspend fun installFromFile(model: ModelInfo, uri: Uri, onProgress: (Int) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        val stateFlow = _states.getOrPut(model.id) { MutableStateFlow(DownloadState.Idle) }
        try {
            // Check space
            val free = FilePaths.cacheRoot(context).freeSpace
            if (free < model.installedSizeMb * 1024 * 1024 * 2) {
                stateFlow.value = DownloadState.Error("Thiếu dung lượng (cần ~${model.installedSizeMb * 2}MB)")
                return@withContext Result.failure(Exception("low space"))
            }

            // Copy from Uri to temp file for processing
            val tmpDir = File(FilePaths.cacheRoot(context), "models_tmp").apply { mkdirs() }
            val tmpFile = File(tmpDir, model.fileName)
            if (tmpFile.exists()) tmpFile.delete()

            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw Exception("Không đọc được file")
                FileOutputStream(tmpFile).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                    }
                }
            }

            if (tmpFile.length() < 500_000) {
                tmpFile.delete()
                stateFlow.value = DownloadState.Error("File quá nhỏ, có thể file hỏng")
                return@withContext Result.failure(Exception("small file"))
            }

            // Process the file (same logic as download)
            stateFlow.value = DownloadState.Downloading(100, tmpFile.length(), tmpFile.length())
            onProgress(100)

            // Sherpa STT vi: tar.bz2 → nhánh riêng (xem download())
            if (model.id == "sherpa-vi-zipformer") {
                return@withContext installSherpaModel(tmpFile, stateFlow, onProgress)
            }

            val dest: File
            if (model.isZip) {
                stateFlow.value = DownloadState.Unzipping(0)
                val destDir = model.destDir(context)
                if (model.type == ModelType.STT && destDir.exists()) destDir.deleteRecursively()
                destDir.mkdirs()

                if (model.fileName.endsWith(".tar.gz") || model.fileName.endsWith(".tgz")) {
                    untarGzFile(tmpFile, destDir, stateFlow)
                    val extractedOnnx = destDir.walkTopDown().find { it.name.endsWith(".onnx") && it.isFile }
                    if (extractedOnnx != null) {
                        val targetName = model.fileName.replace(".tar.gz", ".onnx").replace(".tgz", ".onnx")
                        val target = File(destDir, targetName)
                        if (extractedOnnx.absolutePath != target.absolutePath) {
                            extractedOnnx.renameTo(target)
                        }
                    }
                    tmpFile.delete()
                    dest = destDir
                } else {
                    unzipWithProgress(tmpFile, destDir, stateFlow)
                    tmpFile.delete()
                    dest = destDir
                }
            } else {
                // .onnx single file
                dest = model.destFile(context)
                dest.parentFile?.mkdirs()
                tmpFile.copyTo(dest, overwrite = true)
                if (model.id == "piper-en-lessac") {
                    try {
                        val legacyCopy = File(FilePaths.offlineModelsDir(context), model.fileName)
                        if (legacyCopy.absolutePath != dest.absolutePath) tmpFile.copyTo(legacyCopy, overwrite = true)
                        val jsonDest = File(dest.parentFile, dest.name + ".json")
                        val jsonLegacy = File(FilePaths.offlineModelsDir(context), model.fileName + ".json")
                        // For file picker, we can't easily get companion JSON - user must pick it separately or we skip
                        if (jsonDest.exists() && jsonLegacy.absolutePath != jsonDest.absolutePath) {
                            jsonDest.copyTo(jsonLegacy, overwrite = true)
                        }
                    } catch (_: Exception) {}
                }
                tmpFile.delete()
            }

            stateFlow.value = DownloadState.Done(dest)
            Result.success(dest)
        } catch (e: Exception) {
            e.printStackTrace()
            val msg = e.message ?: "Lỗi cài đặt: $e"
            _states[model.id]?.value = DownloadState.Error(msg)
            Result.failure(e)
        }
    }

    /** Cài sherpa-vi model + engine từ file user chọn (file picker). */
    suspend fun installSherpaViPackage(context: Context, modelUri: Uri, onProgress: (Int) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 1) Cài engine trước
            val engineOk = SherpaEngine.ensureInstalled(context) { p -> onProgress(p / 2) }
            if (!engineOk) return@withContext Result.failure(Exception("Cài sherpa engine thất bại"))

            // 2) Copy model file from Uri to temp
            val tmpDir = File(FilePaths.cacheRoot(context), "sherpa_tmp").apply { mkdirs() }
            val tmpFile = File(tmpDir, "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2")
            if (tmpFile.exists()) tmpFile.delete()

            context.contentResolver.openInputStream(modelUri).use { input ->
                if (input == null) throw Exception("Không đọc được file model")
                FileOutputStream(tmpFile).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                    }
                }
            }

            if (tmpFile.length() < 100_000) {
                tmpFile.delete()
                return@withContext Result.failure(Exception("File model quá nhỏ"))
            }

            // 3) Extract model
            extractSherpaViModel(tmpFile, context)
            tmpFile.delete()
            onProgress(100)

            // 4) Verify
            if (!SherpaEngine.isSherpaViModelInstalled(context)) {
                return@withContext Result.failure(Exception("Model cài xong nhưng verify thất bại"))
            }

            Result.success(SherpaEngine.sherpaViModelDir(context))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Cài model sherpa-vi từ file tar.bz2 đã có (tải mạng xong hoặc user chọn qua file picker).
     * Dùng chung cho download() + installFromFile(): extract BZip2 → verify đủ 4 file →
     * tự cài engine sherpa-onnx (best-effort) → Done. Xoá file nguồn sau khi xong.
     */
    private suspend fun installSherpaModel(
        source: File,
        stateFlow: MutableStateFlow<DownloadState>,
        onProgress: (Int) -> Unit = {}
    ): Result<File> {
        val destDir = SherpaEngine.sherpaViModelDir(context)
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()
        stateFlow.value = DownloadState.Unzipping(10)
        extractSherpaViModel(source, context)
        try { source.delete() } catch (_: Exception) {}
        stateFlow.value = DownloadState.Unzipping(70)
        if (!SherpaEngine.isSherpaViModelInstalled(context)) {
            stateFlow.value = DownloadState.Error("Giải nén xong nhưng thiếu file model (cần encoder/decoder/joiner/tokens)")
            return Result.failure(Exception("incomplete sherpa-vi model"))
        }
        // Engine sherpa-onnx tự cài khi tải model (best-effort: mất mạng thì model vẫn xong,
        // user cài engine sau ở mục Piper / lần dùng STT đầu).
        try {
            val okEngine = SherpaEngine.ensureInstalled(context) { p ->
                val pct = 70 + (p * 30 / 100).coerceIn(0, 30)
                stateFlow.value = DownloadState.Unzipping(pct)
                onProgress(pct)
            }
            if (!okEngine) android.util.Log.w("ModelDownloader", "sherpa-vi model OK, engine chưa cài (thử lại khi có mạng)")
        } catch (e: Exception) {
            android.util.Log.w("ModelDownloader", "ensure engine failed: $e")
        }
        stateFlow.value = DownloadState.Done(destDir)
        onProgress(100)
        return Result.success(destDir)
    }

    private fun downloadExtraFile(urlStr: String, dest: File) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("User-Agent", "Bibot/1.0")
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) return
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                }
            }
        } catch (_: Exception) {
        } finally { conn?.disconnect() }
    }

    /** Extract sherpa-vi model tar.bz2 to no_backup/bibot/models/sherpa-vi/ */
    private fun extractSherpaViModel(source: File, context: Context) {
        val destDir = SherpaEngine.sherpaViModelDir(context)
        destDir.mkdirs()
        try {
            FileInputStream(source).use { fis ->
                BZip2CompressorInputStream(BufferedInputStream(fis)).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            val path = entry.name.replace('\\', '/').removePrefix("./")
                            if (!entry.isDirectory) {
                                val name = path.substringAfterLast("/")
                                if (name in listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")) {
                                    val out = File(destDir, name)
                                    FileOutputStream(out).use { fos -> tar.copyTo(fos) }
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unzipWithProgress(zipFile: File, destDir: File, stateFlow: MutableStateFlow<DownloadState>) {
        // Đếm entries trước để tính %
        val totalEntries = ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var c = 0; while (zis.nextEntry != null) c++; c
        }
        var done = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) outFile.mkdirs()
                else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (zis.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
                done++
                if (totalEntries > 0) stateFlow.value = DownloadState.Unzipping((done * 100 / totalEntries).coerceIn(0, 100))
                entry = zis.nextEntry
            }
        }
        // Nếu zip chứa 1 thư mục gốc (vosk-model-small-vn-0.4/*) thì flatten 1 cấp
        val singleRoot = destDir.listFiles()?.singleOrNull { it.isDirectory }
        if (singleRoot != null && destDir.listFiles()?.size == 1) {
            singleRoot.listFiles()?.forEach { it.renameTo(File(destDir, it.name)) }
            singleRoot.delete()
        }
    }

    private fun untarGzFile(tarGzFile: File, destDir: File, stateFlow: MutableStateFlow<DownloadState>) {
        val buffer = ByteArray(8192)
        var totalRead = 0L
        val totalSize = tarGzFile.length()
        GZIPInputStream(FileInputStream(tarGzFile)).use { gis ->
            // Minimal tar parser
            val tarBuffer = ByteArray(512)
            while (true) {
                val read = gis.read(tarBuffer)
                if (read < 512) break
                if (tarBuffer.all { it == 0.toByte() }) break
                val nameBytes = tarBuffer.copyOfRange(0, 100)
                val name = String(nameBytes).trim { it == '\u0000' || it == ' ' }.trim()
                val sizeStr = String(tarBuffer.copyOfRange(124, 136)).trim { it == '\u0000' || it == ' ' }.trim()
                val size = try { sizeStr.toLong(8) } catch (_: Exception) { 0L }
                val typeFlag = tarBuffer[156].toInt().toChar()
                if (name.isNotBlank() && typeFlag != '5') {
                    val outFile = File(destDir, name)
                    outFile.parentFile?.mkdirs()
                    var remaining = size
                    FileOutputStream(outFile).use { outStream ->
                        while (remaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val r = gis.read(buffer, 0, toRead)
                            if (r <= 0) break
                            outStream.write(buffer, 0, r)
                            remaining -= r
                        }
                    }
                    totalRead += size
                    if (totalSize > 0) stateFlow.value = DownloadState.Unzipping(((totalRead * 100 / totalSize).coerceIn(0, 100)).toInt())
                } else {
                    var toSkip = size
                    while (toSkip > 0) {
                        val skipped = gis.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                }
                // Skip padding to 512-byte boundary
                val padding = (512 - (size % 512)) % 512
                if (padding > 0) gis.skip(padding.toLong())
            }
        }
    }

    fun delete(model: ModelInfo): Boolean {
        return try {
            when (model.type) {
                ModelType.STT -> {
                    if (model.id == "sherpa-vi-zipformer") {
                        SherpaEngine.sherpaViModelDir(context).deleteRecursively()
                    } else {
                        FilePaths.voskModelDir(context, model.lang).deleteRecursively()
                    }
                }
                ModelType.TTS -> {
                    model.destFile(context).delete()
                    // Xóa cả companion .json và legacy copy
                    File(model.destFile(context).absolutePath + ".json").delete()
                    File(FilePaths.offlineModelsDir(context), model.fileName).delete()
                    File(FilePaths.offlineModelsDir(context), model.fileName + ".json").delete()
                    File(FilePaths.offlineModelsDir(context), "en_US-lessac-medium.onnx").delete()
                    File(FilePaths.offlineModelsDir(context), "en_US-lessac-medium.onnx.json").delete()
                }
            }
            _states[model.id]?.value = DownloadState.Idle
            true
        } catch (_: Exception) { false }
    }

    fun isDownloading(id: String): Boolean = _states[id]?.value is DownloadState.Downloading || _states[id]?.value is DownloadState.Unzipping
}