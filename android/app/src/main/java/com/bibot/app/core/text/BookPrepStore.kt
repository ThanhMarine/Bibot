package com.bibot.app.core.text

import android.util.Log
import com.bibot.app.core.tts.VietnameseChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * P1 — Prepared file cache (item 5+6) + sentence cache nền (item 5) cho Story/Book.
 *
 * Mục tiêu: lần mở thứ 2 "tức thì" + RAM không giữ content cả sách:
 *  - `.prep` (cache/prep/<key>.prep): index [title, byteStart, byteLen] + blob content ĐÃ clean
 *    → mở lại bỏ hẳn resolveHeading (pass 1) + readChapters/clean (pass 2);
 *    chapters chỉ giữ title + offset (item 6), content đọc per-chương bằng seek.
 *  - `.sent` (cùng tên .sent): kết quả VietnameseChunker.split từng chương,
 *    build NỀN sau lần mở đầu (song song 3-4 luồng, ghi streaming từng batch) → mở lại
 *    switch chương tức thì + total câu THẬT (không còn ước tính len/95).
 *
 * Invalidation: header `.prep` đối chiếu src size+mtime + PREP_VERSION + heading mode;
 * `.sent` đối chiếu độ dài file `.prep` + version + chapterCount + completed=1.
 * Mọi lệch → tự rebuild (fallback an toàn, không bao giờ trả dữ liệu sai).
 *
 * Bump PREP_VERSION khi đổi: TextCleaner.cleanForTts / PdfTextCleaner.cleanPdfText,
 * BlockTextReader heading detect, hoặc VietnameseChunker split semantics (DELIM...).
 */
object BookPrepStore {

    /** Bump khi đổi clean pipeline / heading detect / split semantics (xem doc trên). */
    // v2 (2026-10-10): trích PDF bám font (ToUnicode CID + TCVN3 + xuống dòng Td/Tm),
    // PDF scan/ảnh trả rỗng + báo rõ — cache salad bản cũ phải build lại.
    const val PREP_VERSION = 2

    // Ước tính tổng câu — TB ~95 ký tự/câu (dùng khi sentence cache chưa build xong)
    const val EST_CHARS_PER_SENT = 95

    private const val MAGIC_PREP = 0x42503142 // "BP1B"
    private const val MAGIC_SENT = 0x42505331 // "BPS1"
    // Header cố định 40 bytes (cả 2 format) — phần thân nằm sau.
    private const val PREP_HEADER = 40
    private const val SENT_HEADER = 40
    // Batch chương split song song (RAM peak ≈ batch × chương + kết quả, không toàn sách)
    private const val SENT_BATCH = 3
    private const val MAX_TITLE_BYTES = 4096
    private const val MAX_SENT_PER_CHAPTER = 1_000_000

    /** 1 chương trong index: title + vị trí byte content trong blob (offset tuyệt đối trong file). */
    data class ChapterRef(val title: String, val start: Long, val len: Int)

    // ------------------------------------------------------------------ .prep

    /** Handle đọc `.prep` — giữ RandomAccessFile mở, đọc chapter bằng seek (thread-safe qua synchronized). */
    class PrepFile internal constructor(
        val file: File,
        val estimateTotal: Int,
        val titles: List<String>,
        private val refs: List<ChapterRef>,
        private val raf: RandomAccessFile
    ) : Closeable {
        fun readChapter(idx: Int): String {
            val c = refs[idx]
            synchronized(raf) {
                raf.seek(c.start)
                val buf = ByteArray(c.len)
                raf.readFully(buf)
                return String(buf, Charsets.UTF_8)
            }
        }

        override fun close() {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    fun prepFile(dir: File, key: String): File = File(dir, sanitize(key) + ".prep")
    fun sentFile(dir: File, key: String): File = File(dir, sanitize(key) + ".sent")

    private fun sanitize(key: String): String =
        key.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "book" }

    /**
     * Mở cache nếu hợp lệ (đối chiếu src size+mtime+version+heading) — null nếu miss/hỏng
     * (file hỏng bị xoá để lần sau build lại).
     */
    fun open(dir: File, key: String, srcFile: File, heading: BlockTextReader.Heading): PrepFile? {
        val f = prepFile(dir, key)
        if (!f.exists()) return null
        val raf = try { RandomAccessFile(f, "r") } catch (_: Exception) { return null }
        try {
            if (raf.length() < PREP_HEADER) throw IOException("short")
            raf.seek(0)
            if (raf.readInt() != MAGIC_PREP) throw IOException("magic")
            if (raf.readInt() != PREP_VERSION) throw IOException("version")
            val srcSize = raf.readLong()
            val srcMtime = raf.readLong()
            val h = raf.readByte().toInt()
            raf.skipBytes(3)
            val count = raf.readInt()
            val indexStart = raf.readLong()
            if (srcSize != srcFile.length()) throw IOException("src changed")
            if (srcMtime != srcFile.lastModified()) throw IOException("src mtime")
            if (h != heading.ordinal) throw IOException("heading")
            if (count < 1 || indexStart < PREP_HEADER || indexStart >= raf.length()) throw IOException("index")
            raf.seek(indexStart)
            val estimate = raf.readInt()
            val refs = ArrayList<ChapterRef>(count)
            for (i in 0 until count) {
                val tl = raf.readInt()
                if (tl < 0 || tl > MAX_TITLE_BYTES) throw IOException("title len")
                val tb = ByteArray(tl)
                raf.readFully(tb)
                val start = raf.readLong()
                val len = raf.readInt()
                if (start < PREP_HEADER || len < 0 || start + len > raf.length()) throw IOException("range")
                refs.add(ChapterRef(String(tb, Charsets.UTF_8), start, len))
            }
            // Giữ file nóng trong TTL của cleanupCache (24h) — sách nghe hằng ngày không bị xoá oan
            f.setLastModified(System.currentTimeMillis())
            return PrepFile(f, estimate, refs.map { it.title }, refs, raf)
        } catch (e: Exception) {
            Log.w("BookPrep", "open fail key=$key: ${e.message}")
            try { raf.close() } catch (_: Exception) {}
            f.delete()
            return null
        }
    }

    /**
     * Build `.prep` bằng readChapters streaming (onChapter sink — KHÔNG tích lũy content trong RAM),
     * ghi tmp → rename atomic. Trả null nếu build lỗi (caller fallback legacy in-memory).
     */
    fun build(
        dir: File,
        key: String,
        srcFile: File,
        heading: BlockTextReader.Heading,
        defaultTitle: String
    ): PrepFile? {
        val dest = prepFile(dir, key)
        val tmp = File(dir, dest.name + ".tmp")
        var writer: PrepWriter? = null
        try {
            if (!dir.exists() && !dir.mkdirs()) throw IOException("mkdir")
            tmp.delete()
            writer = PrepWriter(tmp, srcFile.length(), srcFile.lastModified(), heading)
            BlockTextReader.readChapters(srcFile, defaultTitle, heading, onChapter = writer::append)
            if (writer.count == 0) writer.append(defaultTitle, "")
            writer.finish(dest)
            writer = null
            return open(dir, key, srcFile, heading)
        } catch (_: Exception) {
            try { writer?.abort() } catch (_: Exception) {}
            tmp.delete()
            return null
        }
    }

    /** Ghi `.prep`: [header 40B][blob content][index: estimate:int + entries]. */
    private class PrepWriter(
        private val tmp: File,
        srcSize: Long,
        srcMtime: Long,
        heading: BlockTextReader.Heading
    ) {
        private val raf = RandomAccessFile(tmp, "rw")
        private val refs = ArrayList<ChapterRef>(128)
        var count: Int = 0
            private set
        private var estimate = 0

        init {
            raf.seek(0)
            raf.writeInt(MAGIC_PREP)
            raf.writeInt(PREP_VERSION)
            raf.writeLong(srcSize)
            raf.writeLong(srcMtime)
            raf.writeByte(heading.ordinal)
            raf.write(byteArrayOf(0, 0, 0))
            raf.writeInt(0)   // chapterCount — chốt ở finish()
            raf.writeLong(0)  // indexStart — chốt ở finish()
            check(raf.filePointer == PREP_HEADER.toLong())
        }

        fun append(title: String, content: String) {
            val start = raf.filePointer
            val b = content.toByteArray(Charsets.UTF_8)
            raf.write(b)
            refs.add(ChapterRef(title, start, b.size))
            count++
            estimate += (content.length / EST_CHARS_PER_SENT).coerceAtLeast(1)
        }

        fun finish(dest: File) {
            val indexStart = raf.filePointer
            raf.writeInt(estimate)
            for (e in refs) {
                val tb = e.title.toByteArray(Charsets.UTF_8)
                raf.writeInt(tb.size)
                raf.write(tb)
                raf.writeLong(e.start)
                raf.writeInt(e.len)
            }
            raf.seek(28)          // chapterCount @28, indexStart @32
            raf.writeInt(refs.size)
            raf.writeLong(indexStart)
            raf.close()
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("rename prep")
        }

        fun abort() {
            try { raf.close() } catch (_: Exception) {}
            tmp.delete()
        }
    }

    // ------------------------------------------------------------------ .sent

    /** Handle đọc sentence cache — offsets mỗi chương, đọc section bằng seek. */
    class SentenceFile internal constructor(
        val totalSentences: Int,
        private val raf: RandomAccessFile,
        private val offsets: LongArray
    ) : Closeable {
        /** Trả null nếu chương không có (idx lệch) hoặc file hỏng → caller fallback split. */
        fun readChapter(idx: Int): List<String>? {
            if (idx < 0 || idx >= offsets.size - 1) return null
            synchronized(raf) {
                raf.seek(offsets[idx])
                val count = raf.readInt()
                if (count < 0 || count > MAX_SENT_PER_CHAPTER) throw IOException("sent count")
                val out = ArrayList<String>(count)
                for (i in 0 until count) {
                    val len = raf.readInt()
                    if (len < 0) throw IOException("sent len")
                    val b = ByteArray(len)
                    raf.readFully(b)
                    out.add(String(b, Charsets.UTF_8))
                }
                return out
            }
        }

        override fun close() {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /** Mở `.sent` nếu hợp lệ với `.prep` hiện tại — null nếu chưa build/hỏng/đang dở. */
    fun openSentences(prep: PrepFile): SentenceFile? {
        val f = File(prep.file.parentFile, prep.file.nameWithoutExtension + ".sent")
        if (!f.exists()) return null
        val raf = try { RandomAccessFile(f, "r") } catch (_: Exception) { return null }
        try {
            if (raf.length() < SENT_HEADER) throw IOException("short")
            raf.seek(0)
            if (raf.readInt() != MAGIC_SENT) throw IOException("magic")
            if (raf.readInt() != PREP_VERSION) throw IOException("version")
            val prepLen = raf.readLong()
            val count = raf.readInt()
            val completed = raf.readInt()
            val total = raf.readInt()
            raf.readInt()          // reserved @28 — BẮT BUỘC đọc để con trỏ về @32
            val offsetsStart = raf.readLong()
            if (prepLen != prep.file.length()) throw IOException("prep changed")
            if (count != prep.titles.size) throw IOException("count")
            if (completed != 1 || total < 0) throw IOException("incomplete")
            if (offsetsStart < SENT_HEADER || offsetsStart >= raf.length()) throw IOException("off")
            raf.seek(offsetsStart)
            val offsets = LongArray(count + 1)
            for (i in offsets.indices) offsets[i] = raf.readLong()
            if (offsets[0] != SENT_HEADER.toLong()) throw IOException("off0")
            for (i in 1 until offsets.size) {
                if (offsets[i] <= offsets[i - 1] || offsets[i] > raf.length()) throw IOException("off order")
            }
            f.setLastModified(System.currentTimeMillis())
            return SentenceFile(total, raf, offsets)
        } catch (e: Exception) {
            Log.w("BookPrep", "openSentences fail: ${e.message}")
            try { raf.close() } catch (_: Exception) {}
            return null
        }
    }

    // Chỉ 1 builder chạy 1 lần (không tranh CPU với builder sách khác); cùng sách → bỏ qua.
    private val buildMutex = Mutex()
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Song song split: cap 4 (hoặc cores−1 trên máy 2 lõi) — đủ nhanh, chừa lõi cho UI/thermal.
    private val splitDispatcher by lazy {
        val cores = Runtime.getRuntime().availableProcessors()
        Dispatchers.Default.limitedParallelism(minOf(4, maxOf(1, cores - 1)))
    }

    /**
     * Build `.sent` NỀN: đọc từng chương từ `.prep` (seek), split song song batch 3 chương,
     * ghi streaming đúng thứ tự → tmp → rename atomic. Trả về total câu thật (null nếu bỏ qua/lỗi).
     * An toàn gọi từ coroutine bất kỳ — không giữ kết quả trong RAM.
     */
    suspend fun buildSentences(dir: File, key: String, srcFile: File, heading: BlockTextReader.Heading): Int? {
        val dest = sentFile(dir, key)
        if (!inFlight.add(dest.absolutePath)) return null
        try {
            return buildMutex.withLock {
                // Đã có bản hợp lệ → không build lại
                open(dir, key, srcFile, heading)?.use { prep ->
                    openSentences(prep)?.use { return@withLock it.totalSentences }
                    withContext(Dispatchers.IO) { buildSentencesInternal(prep, dest) }
                } ?: return@withLock null
            }
        } finally {
            inFlight.remove(dest.absolutePath)
        }
    }

    private suspend fun buildSentencesInternal(prep: PrepFile, dest: File): Int? {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val n = prep.titles.size
        try {
            tmp.delete()
            val r = RandomAccessFile(tmp, "rw")
            try {
                r.seek(0)
                r.writeInt(MAGIC_SENT)
                r.writeInt(PREP_VERSION)
                r.writeLong(prep.file.length())
                r.writeInt(n)
                r.writeInt(0)      // completed — chốt ở finish
                r.writeInt(0)      // totalSentences — chốt ở finish
                r.writeInt(0)      // reserved
                r.writeLong(0)     // offsetsStart — chốt ở finish
                check(r.filePointer == SENT_HEADER.toLong())
                val offsets = LongArray(n + 1)
                offsets[0] = SENT_HEADER.toLong()
                var total = 0
                var i = 0
                while (i < n) {
                    val batchEnd = minOf(i + SENT_BATCH, n)
                    // 1) Đọc content batch từ prep (I/O)
                    val texts = withContext(Dispatchers.IO) { (i until batchEnd).map { prep.readChapter(it) } }
                    // 2) Split song song batch (CPU — LimitedParallelism 3-4, giữ thứ tự)
                    val results = withContext(splitDispatcher) {
                        coroutineScope { texts.map { t -> async { VietnameseChunker.split(t) } }.awaitAll() }
                    }
                    // 3) Ghi streaming đúng thứ tự rồi thả kết quả
                    withContext(Dispatchers.IO) {
                        for ((k, list) in results.withIndex()) {
                            r.writeInt(list.size)
                            for (s in list) {
                                val b = s.toByteArray(Charsets.UTF_8)
                                r.writeInt(b.size)
                                r.write(b)
                            }
                            total += list.size
                            offsets[i + k + 1] = r.filePointer
                        }
                    }
                    i = batchEnd
                }
                val offsetsStart = r.filePointer
                for (o in offsets) r.writeLong(o)
                r.seek(20)         // completed @20, totalSentences @24, offsetsStart @32
                r.writeInt(1)
                r.writeInt(total)
                r.seek(32)
                r.writeLong(offsetsStart)
                r.close()
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("rename sent")
                return total
            } catch (e: Exception) {
                try { r.close() } catch (_: Exception) {}
                throw e
            }
        } catch (e: Exception) {
            tmp.delete()
            Log.w("BookPrep", "buildSentencesInternal abort: ${e.message}")
            return null
        }
    }

    // ------------------------------------------------------------------ Session

    /**
     * Phiên đọc 1 sách: title list (không giữ content cả sách) + truy cập per-chương.
     *  - `sentences(idx)`: LRU trong RAM → `.sent` → fallback load content + split.
     *  - `content(idx)`  : LRU trong RAM → `.prep` seek (hoặc in-memory fallback).
     * Mọi lỗi đọc trả rỗng (không crash coroutine đang chạy khi session bị close giữa chừng).
     */
    class PrepSession internal constructor(
        val titles: List<String>,
        val estimateTotal: Int,
        private val sentFile: SentenceFile?,
        private val loadContent: suspend (Int) -> String,
        private val onClose: () -> Unit
    ) {
        /** Tổng câu THẬT — cập nhật khi build sentence cache nền xong; null = chưa có (dùng estimateTotal). */
        @Volatile
        var sentenceTotal: Int? = sentFile?.totalSentences

        private val sentLru = LinkedHashMap<Int, List<String>>(3, 0.75f, true)
        private val conLru = LinkedHashMap<Int, String>(2, 0.75f, true)

        @Volatile
        private var closed = false

        suspend fun sentences(idx: Int): List<String> {
            if (closed || idx !in titles.indices) return emptyList()
            synchronized(sentLru) { sentLru[idx] }?.let { return it }
            val cached = try {
                if (closed) null else sentFile?.readChapter(idx)
            } catch (_: Exception) { null }
            val list = if (cached != null) cached else {
                val c = content(idx)
                if (closed) return emptyList()
                VietnameseChunker.split(c)
            }
            synchronized(sentLru) { putLru(sentLru, idx, list, 3) }
            return list
        }

        suspend fun content(idx: Int): String {
            if (closed || idx !in titles.indices) return ""
            synchronized(conLru) { conLru[idx] }?.let { return it }
            val c = try {
                if (closed) "" else loadContent(idx)
            } catch (_: Exception) { "" }
            if (c.isNotEmpty()) synchronized(conLru) { putLru(conLru, idx, c, 2) }
            return c
        }

        fun close() {
            if (closed) return
            closed = true
            synchronized(sentLru) { sentLru.clear() }
            synchronized(conLru) { conLru.clear() }
            try { onClose() } catch (_: Exception) {}
        }

        private fun <K, V> putLru(map: LinkedHashMap<K, V>, k: K, v: V, max: Int) {
            map[k] = v
            while (map.size > max) {
                val it = map.entries.iterator()
                it.next()
                it.remove()
            }
        }

        companion object {
            /** Từ `.prep` (đã mở) + `.sent` (nullable). */
            fun of(prep: PrepFile, sent: SentenceFile?): PrepSession =
                PrepSession(prep.titles, prep.estimateTotal, sent, { prep.readChapter(it) }, {
                    prep.close()
                    sent?.close()
                })

            /** Fallback in-memory khi không build được `.prep` (giữ content như bản cũ). */
            fun inMemory(titles: List<String>, contents: List<String>): PrepSession {
                var est = 0
                for (c in contents) est += (c.length / EST_CHARS_PER_SENT).coerceAtLeast(1)
                return PrepSession(titles, est, null, { contents[it] }, {})
            }
        }
    }
}
