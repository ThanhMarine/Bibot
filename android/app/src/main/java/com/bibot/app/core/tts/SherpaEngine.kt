package com.bibot.app.core.tts

import android.content.Context
import android.net.Uri
import android.os.Build
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Engine pack sherpa-onnx v1.13.8 (onnxruntime + espeak-ng + JNI) — tải on-demand 1 lần.
 * KHÔNG bundle ~43MB .so vào APK (giữ diet): tải tarball GitHub release, giải đúng ABI máy
 * vào no_backup/sherpa (không backup), nạp bằng System.load() theo thứ tự phụ thuộc.
 * TTS Java bindings vendored đúng tag tại com.k2fsa.sherpa.onnx.TtsKt (chỉ sửa companion loader).
 */
object SherpaEngine {
    const val VERSION = "1.13.8"
    const val TARBALL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"

    // Thứ tự nạp quan trọng: onnxruntime → c-api → jni
    private val SO_NAMES = listOf(
        "libonnxruntime.so",
        "libsherpa-onnx-c-api.so",
        "libsherpa-onnx-jni.so"
    )

    @Volatile var loaded: Boolean = false
        private set

    fun engineDir(context: Context): File =
        File(FilePaths.noBackupRoot(context), "sherpa/$VERSION").apply { mkdirs() }

    /**
     * ABI của CHÍNH app — không phải SUPPORTED_ABIS[0] của hệ thống. APK này chỉ đóng
     * gói arm64/armeabi (vosk .aar) → Android cài app là arm64-v8a (dịch) dù máy x86_64;
     * lấy SUPPORTED_ABIS[0]=x86_64 sẽ bung file sai arch → dlopen "is for EM_X86_64
     * instead of EM_AARCH64". Nguồn: nativeLibraryDir (…/lib/arm64) → os.arch fallback.
     */
    fun abiName(context: Context): String {
        val seg = try { context.applicationInfo.nativeLibraryDir?.substringAfterLast('/') } catch (_: Exception) { null }
        when (seg) {
            "arm64", "arm64-v8a" -> return "arm64-v8a"
            "arm", "armeabi-v7a" -> return "armeabi-v7a"
            "x86_64", "x86" -> return seg
        }
        val arch = System.getProperty("os.arch") ?: ""
        when {
            arch.startsWith("aarch64") -> return "arm64-v8a"
            arch.startsWith("arm") -> return "armeabi-v7a"
            arch == "x86_64" -> return "x86_64"
        }
        return Build.SUPPORTED_ABIS.firstOrNull { it in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
            ?: "arm64-v8a"
    }

    fun abiDir(context: Context): File = File(engineDir(context), "lib/${abiName(context)}").apply { mkdirs() }

    /** Cài engine sherpa-onnx từ file user chọn (Storage Access Framework / file picker). */
    suspend fun installEngineFromFile(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext true
            try {
                val dir = abiDir(context)
                val marker = File(engineDir(context), ".ready_${dir.name}")
                val tmpDir = File(FilePaths.cacheRoot(context), "sherpa_tmp").apply { mkdirs() }
                val tmpFile = File(tmpDir, "sherpa-onnx-$VERSION-android.tar.bz2")
                if (tmpFile.exists()) tmpFile.delete()

                // Copy from Uri to temp file
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw Exception("Không đọc được file engine")
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
                    return@withContext false
                }

                extractSo(FileInputStream(tmpFile), tmpFile.length(), dir, onProgress)
                marker.writeText(VERSION)
                tmpFile.delete()

                val files = SO_NAMES.map { File(dir, it) }
                if (files.any { !it.exists() || it.length() < 100_000 }) return@withContext false
                com.k2fsa.sherpa.onnx.SherpaLibDirs.orderedSoFiles = files
                if (!com.k2fsa.sherpa.onnx.SherpaLibDirs.loadJni()) return@withContext false
                loaded = true
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    /** Tarball sideload (adb push vào models_in) — có thì giải nén luôn, không tải mạng. */
    @Deprecated("Use installEngineFromFile with file picker instead")
    fun stagedTarball(context: Context): File =
        File(FilePaths.modelStagingDir(context), "sherpa-onnx-$VERSION-android.tar.bz2")

    @Deprecated("Use installEngineFromFile with file picker instead")
    fun hasLocalPack(context: Context): Boolean = stagedTarball(context).let { it.exists() && it.length() > 100_000 }

    fun isInstalled(context: Context): Boolean {
        val dir = abiDir(context)
        return File(engineDir(context), ".ready_${dir.name}").exists() &&
            SO_NAMES.all { File(dir, it).exists() }
    }

    /** Cài + nạp engine. Chạy IO, báo % khi server cho content-length. An toàn gọi nhiều lần. */
    suspend fun ensureInstalled(context: Context, onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext true
            try {
                val dir = abiDir(context)
                val marker = File(engineDir(context), ".ready_${dir.name}")
                if (!marker.exists() || SO_NAMES.any { !File(dir, it).exists() }) {
                    extract(context, dir, onProgress)
                    marker.writeText(VERSION)
                }
                val files = SO_NAMES.map { File(dir, it) }
                if (files.any { !it.exists() || it.length() < 100_000 }) return@withContext false
                com.k2fsa.sherpa.onnx.SherpaLibDirs.orderedSoFiles = files
                // Nạp chuỗi .so trực tiếp qua loadJni (log lỗi tag SherpaLib). KHÔNG dùng
                // Class.forName(OfflineTts): lần đầu nạp fail sẽ "poison" class vĩnh viễn
                // trong process (ART Reject re-init → NoClassDefFoundError không phục hồi).
                if (!com.k2fsa.sherpa.onnx.SherpaLibDirs.loadJni()) return@withContext false
                loaded = true
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (_: Exception) {
                false
            }
        }

    /** Model sherpa-vi cài ở no_backup/bibot/models/sherpa-vi/ (dùng chung cho STT). */
    fun sherpaViModelDir(context: Context): File =
        File(FilePaths.noBackupRoot(context), "models/sherpa-vi").apply { mkdirs() }

    /** Check 4 file model sherpa-vi đã sẵn sàng. */
    fun isSherpaViModelInstalled(context: Context): Boolean {
        val dir = sherpaViModelDir(context)
        return listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
            .all { File(dir, it).exists() && File(dir, it).length() > 1000 }
    }

    /** Giải nén engine: ưu tiên tarball sideload (models_in), không có mới tải mạng. */
    private fun extract(context: Context, dir: File, onProgress: (Int) -> Unit) {
        val staged = stagedTarball(context)
        if (staged.exists() && staged.length() > 100_000) {
            try {
                FileInputStream(staged).use { input -> extractSo(input, staged.length(), dir, onProgress) }
                return
            } catch (e: Exception) {
                e.printStackTrace() // file staged hỏng → fallback tải mạng
            }
        }
        downloadAndExtract(dir, onProgress)
    }

    /** Nạp .so từ bzip2+tar stream: lọc theo ABI hiện tại, verify đủ bộ. */
    private fun extractSo(input: InputStream, total: Long, dir: File, onProgress: (Int) -> Unit) {
        val prefix = "jniLibs/${dir.name}/"
        var done = 0L
        var lastPct = -1
        val counting = object : FilterInputStream(input) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    done += n
                    if (total > 0) {
                        val pct = ((done * 70 / total).toInt()).coerceIn(0, 70)
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
                return n
            }
        }
        BZip2CompressorInputStream(BufferedInputStream(counting)).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    // Release thật để "./jniLibs/<abi>/x.so" → phải strip "./" trước khi so prefix
                    val path = entry.name.replace('\\', '/').removePrefix("./")
                    if (!entry.isDirectory && path.startsWith(prefix) && path.endsWith(".so")) {
                        val name = path.substringAfterLast("/")
                        if (name in SO_NAMES) {
                            val out = File(dir, name)
                            FileOutputStream(out).use { fos -> tar.copyTo(fos) }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
        onProgress(75)
        // Verify đủ bộ
        val missing = SO_NAMES.filter { !File(dir, it).exists() }
        if (missing.isNotEmpty()) throw IllegalStateException("Thiếu .so: $missing")
        onProgress(80)
    }

    private fun downloadAndExtract(dir: File, onProgress: (Int) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(TARBALL_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Bibot/1.0")
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Sherpa engine HTTP ${conn.responseCode}")
            }
            // Stream trực tiếp mạng → bzip2 → tar → file (không giữ 46MB tmp)
            extractSo(conn.inputStream, conn.contentLengthLong, dir, onProgress)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
