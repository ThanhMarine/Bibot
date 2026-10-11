package com.bibot.app.data.files

import android.content.Context
import java.io.File

/**
 * Chuẩn Android Storage — không hardcode path Pi (/home/adennguyen/...)
 * Tham chiếu: https://developer.android.com/training/data-storage
 *
 *  Internal (private, backup, không cần quyền):
 *    filesDir            /data/data/com.bibot.app/files/
 *    cacheDir            /data/data/com.bibot.app/cache/
 *    noBackupFilesDir    /data/data/com.bibot.app/no_backup/
 *  External app-specific (không cần quyền từ Android 10, tự xoá khi gỡ app):
 *    externalFilesDir    /sdcard/Android/data/com.bibot.app/files/
 *    externalCacheDir    /sdcard/Android/data/com.bibot.app/cache/
 *  Databases:
 *    databases/          /data/data/com.bibot.app/databases/bibot.db
 *  DataStore:
 *    files/datastore/    /data/data/com.bibot.app/files/datastore/ *.preferences_pb
 */
object FilePaths {

    // Root scoped dirs
    fun bibotRoot(context: Context): File = File(context.filesDir, "bibot").apply { mkdirs() }
    fun cacheRoot(context: Context): File = File(context.cacheDir, "bibot").apply { mkdirs() }
    fun noBackupRoot(context: Context): File = File(context.noBackupFilesDir, "bibot").apply { mkdirs() }

    // PDF — thay windows_app/pdf_inbox, md_outbox + Pi VietBooks
    fun pdfInbox(context: Context): File = File(bibotRoot(context), "pdfs/inbox").apply { mkdirs() }
    fun pdfOutbox(context: Context): File = File(bibotRoot(context), "pdfs/outbox").apply { mkdirs() }
    fun pdfCache(context: Context): File = File(cacheRoot(context), "pdfs/cache").apply { mkdirs() }

    // Audio — thay audiobookshelf/books + Music
    fun ttsCache(context: Context): File = File(cacheRoot(context), "audio/tts").apply { mkdirs() }
    fun audioBooks(context: Context): File = File(bibotRoot(context), "books").apply { mkdirs() }
    fun musicDir(context: Context): File = File(bibotRoot(context), "music").apply { mkdirs() }

    // Models offline (Vosk ~30MB, Piper ~15MB) — đặt no_backup để không backup lên Drive
    fun offlineModelsDir(context: Context): File = File(noBackupRoot(context), "models").apply { mkdirs() }
    fun voskModelDir(context: Context, lang: String = "vi"): File = File(offlineModelsDir(context), "vosk-$lang").apply { mkdirs() }
    fun piperModelFile(context: Context, lang: String = "vi"): File = File(offlineModelsDir(context), "piper-$lang.onnx")

    // Sideload — adb push file model từ PC vào đây rồi bấm "Cài" trong Settings → Mô hình offline.
    // External app-specific: không cần root, không cần quyền storage (vd models_in/model.onnx).
    // DEPRECATED: Use file picker (Storage Access Framework) instead.
    @Deprecated("Use file picker instead", ReplaceWith(""))
    fun modelStagingDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models_in").apply { mkdirs() }

    // State — thay Pi state/ (book_progress.json, mode5_*.jsonl, mode14_state.db)
    fun stateDir(context: Context): File = File(bibotRoot(context), "state").apply { mkdirs() }
    fun datastoreFile(context: Context): File = File(bibotRoot(context), "datastore/bibot.preferences_pb").apply { parentFile?.mkdirs() }
    fun roomDbFile(context: Context): File = context.getDatabasePath("bibot.db")

    // P1: prepared book cache (.prep index+blob, .sent sentence cache) — trong cacheDir
    // (system tự clear khi thiếu bộ nhớ → app tự build lại, không mất gì).
    fun prepDir(context: Context): File = File(cacheRoot(context), "prep").apply { mkdirs() }

    // External exports (user-visible, SAF)
    fun externalExportDir(context: Context): File? = context.getExternalFilesDir("exports")?.apply { mkdirs() }

    // Temp chunk data — streaming LLM token buffer, vietnamese_chunker, TTS Queue 2 spill, ExoPlayer buffer
    // RAM chính, spill ra file khi > 512KB hoặc khi cần persist resume. TTL 24h, LRU 200MB.
    fun chunkTempDir(context: Context): File = File(cacheRoot(context), "chunks").apply { mkdirs() }
    fun ttsChunkFile(context: Context, id: String): File = File(ttsCache(context), "chunk_$id.wav")
    fun llmChunkFile(context: Context, id: String): File = File(chunkTempDir(context), "llm_$id.tmp")
    fun pdfChunkDir(context: Context): File = File(pdfCache(context), "chunks").apply { mkdirs() }

    // A1.2: segment audio cache — content-addressed, DURABLE (không TTL).
    // noBackup (tránh backup phình): {noBackup}/audio/segments/{settingsHash}/{audioHash}.{mp3|wav}.
    // Chỉ LRU theo size (cleanupSegmentCache), tách khỏi cleanupCache TTL bên dưới.
    fun ttsSegmentRoot(context: Context): File = File(noBackupRoot(context), "audio/segments").apply { mkdirs() }
    fun ttsSegmentDir(context: Context, settingsHash: String): File =
        File(ttsSegmentRoot(context), settingsHash.filter { it.isLetterOrDigit() || it == '-' }).apply { mkdirs() }
    fun ttsSegmentFile(context: Context, settingsHash: String, audioHash: String, ext: String): File {
        val safeExt = if (ext.equals("wav", ignoreCase = true)) "wav" else "mp3"
        return File(ttsSegmentDir(context, settingsHash), "${audioHash.filter { it.isLetterOrDigit() }}.$safeExt")
    }

    /** LRU size-only cho segment cache (không TTL — file còn hit là còn giá trị). */
    fun cleanupSegmentCache(context: Context, maxMb: Long = 300): String {
        return try {
            val root = ttsSegmentRoot(context)
            val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
            var total = files.sumOf { it.length() }
            var deleted = 0
            for (f in files) {
                if (total <= maxMb * 1024 * 1024) break
                val sz = f.length()
                if (f.delete()) { deleted++; total -= sz }
            }
            // Dọn dir settings rỗng
            root.listFiles()?.filter { it.isDirectory && (it.listFiles()?.isEmpty() != false) }?.forEach { it.delete() }
            "Segments: cleaned $deleted files, remaining ${total / (1024 * 1024)}MB"
        } catch (e: Exception) { "Segments: cleanup fail ${e.message}" }
    }

    /** Dọn cache temp: xoá file > ttlHours, LRU nếu vượt maxMb. Gọi onStart + WorkManager 1 lần/ngày. */
    fun cleanupCache(context: Context, maxMb: Int = 200, ttlHours: Int = 24): String {
        val roots = listOf(chunkTempDir(context), ttsCache(context), pdfCache(context), prepDir(context))
        val cutoff = System.currentTimeMillis() - ttlHours * 3600L * 1000L
        var deleted = 0; var freed = 0L
        roots.forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) { freed += f.length(); if (f.deleteRecursively()) deleted++ }
            }
        }
        // LRU nếu vẫn vượt maxMb
        val allChunks = roots.flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }.sortedBy { it.lastModified() }
        var totalMb = allChunks.sumOf { it.length() } / (1024 * 1024)
        for (f in allChunks) {
            if (totalMb <= maxMb) break
            val sz = f.length(); if (f.delete()) { deleted++; freed += sz; totalMb -= sz / (1024 * 1024) }
        }
        // A1.2: segment cache durable riêng (size-only, không TTL) — không nằm trong roots TTL/LRU trên.
        val segReport = try { cleanupSegmentCache(context) } catch (_: Exception) { "" }
        return "Cleaned $deleted files, freed ${freed / 1024} KB, remaining ${totalMb}MB; $segReport"
    }

    // Helpers
    fun ensureAll(context: Context) {
        bibotRoot(context); cacheRoot(context); noBackupRoot(context)
        pdfInbox(context); pdfOutbox(context); ttsCache(context); chunkTempDir(context); pdfChunkDir(context)
        audioBooks(context); musicDir(context); offlineModelsDir(context); stateDir(context)
        datastoreFile(context); roomDbFile(context)
    }

    fun debugDump(context: Context): String = buildString {
        appendLine("filesDir: ${context.filesDir.absolutePath}")
        appendLine("cacheDir: ${context.cacheDir.absolutePath} (chunks: ${chunkTempDir(context).listFiles()?.size ?: 0} files)")
        appendLine("noBackup: ${context.noBackupFilesDir.absolutePath}")
        appendLine("externalFiles: ${context.getExternalFilesDir(null)?.absolutePath}")
        appendLine("bibotRoot: ${bibotRoot(context).absolutePath}")
        appendLine("pdfInbox: ${pdfInbox(context).absolutePath} (${pdfInbox(context).listFiles()?.size ?: 0} files)")
        appendLine("ttsCache: ${ttsCache(context).absolutePath} (${ttsCache(context).listFiles()?.size ?: 0} wav)")
        appendLine("db: ${roomDbFile(context).absolutePath} exists=${roomDbFile(context).exists()}")
        appendLine("cleanup: ${cleanupCache(context, maxMb = 200, ttlHours = 24)} (dry-run preview)")
    }
}
