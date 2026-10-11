package com.k2fsa.sherpa.onnx

/**
 * Bibot addition (không có trong sherpa gốc) — cầu nối đường dẫn .so runtime.
 * SherpaEngine tải engine pack (tarball GitHub) vào no_backup/sherpa rồi gán
 * [orderedSoFiles] TRƯỚC lần đầu chạm tới binding; companion của file vendored
 * sẽ gọi [loadJni] System.load() từng file theo đúng thứ tự phụ thuộc
 * (onnxruntime → c-api → jni). Giữ nguyên package để JNI bind đúng tên native.
 */
object SherpaLibDirs {
    private const val TAG = "SherpaLib"

    @Volatile var orderedSoFiles: List<java.io.File> = emptyList()

    fun dirs(): List<java.io.File> =
        orderedSoFiles.ifEmpty { throw IllegalStateException("Sherpa engine chưa cài (SherpaEngine.ensureInstalled)") }

    /**
     * Nạp chuỗi .so theo [orderedSoFiles], fallback loadLibrary cho bản build có
     * jniLibs kèm sẵn. Trả về true nếu nạp OK. Mỗi lỗi System.load được Log.e
     * (trước đây bị nuốt im lặng → không chẩn đoán được).
     *
     * Gọi từ companion `init` của bindings vendored (OfflineRecognizer/OfflineStream/Tts).
     */
    fun loadJni(): Boolean {
        val dirs = try {
            dirs()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "orderedSoFiles chưa gán (SherpaEngine.ensureInstalled chưa chạy): $e")
            null
        }
        var loaded = false
        if (dirs != null) {
            for (f in dirs) {
                try {
                    System.load(f.absolutePath)
                    loaded = true
                } catch (e: UnsatisfiedLinkError) {
                    android.util.Log.e(TAG, "System.load ${f.absolutePath} FAILED: $e")
                    loaded = false
                    break
                } catch (e: SecurityException) {
                    android.util.Log.e(TAG, "System.load ${f.absolutePath} SECURITY: $e")
                    loaded = false
                    break
                }
            }
        }
        if (!loaded) {
            try {
                System.loadLibrary("sherpa-onnx-jni")
                loaded = true
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "fallback System.loadLibrary(sherpa-onnx-jni) FAILED: $e")
            }
        }
        return loaded
    }

    /** Companion init helper: nạp OK thì qua, không thì ném lỗi class-fail rõ ràng. */
    fun requireJni() {
        if (!loadJni()) {
            throw UnsatisfiedLinkError("sherpa .so không nạp được — xem logcat tag SherpaLib")
        }
    }
}
