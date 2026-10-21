package com.bibot.app.core.text

import java.io.File
import java.io.RandomAccessFile

/**
 * E2.1 — neo PAGE chính xác: ánh xạ pageIndex → chỉ số câu mở đầu trong chương
 * prep duy nhất của PDF, thay map tỉ lệ `(page+0.5)/pageCount` (ReadAnchor cũ).
 *
 * Nguồn dữ liệu:
 * - [BlockTextReader.iterPdfTextPages] — text gắn chỉ số trang (page-tree).
 * - Câu từ `.prep`/`.sent` (nguyên bản, display-faithful) — cùng nguồn text.
 *
 * Ký hiệu: 48 ký tự alphanumeric đầu trang (lowercase, giữ Unicode) — quét câu
 * tăng dần (monotonic) từ câu của trang trước, lấy câu ĐẦU TIÊN chứa sig.
 * Trang bắt đầu giữa câu → match câu đang bao trùm (đọc tiếp từ đầu câu đó).
 * Trang trắng / không match / không có text → giữ câu của trang trước.
 *
 * Cache: `<key>.pagemap` cạnh `.prep` trong prepDir —
 * `[magic][ver][srcSize][srcMtime][count][ints…]`; file đổi/hỏng → build lại.
 */
object PdfPageMap {

    private const val MAGIC = 0x50474D50 // "PGMP"
    private const val VERSION = 1
    private const val SIG_CHARS = 48
    private const val MAX_PAGES = 100_000

    /** Tên file cache (sanitize cùng BookPrepStore.sanitize). */
    fun file(dir: File, key: String): File {
        val k = key.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "book" }
        return File(dir, "$k.pagemap")
    }

    /** Giữ ký tự letter/digit (Unicode) — so khớp bất chấp whitespace/dấu câu clean khác nhau. */
    internal fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        return sb.toString()
    }

    /**
     * So khớp thuần (test được): `starts[page]` = chỉ số câu chứa ĐẦU trang.
     *
     * Kỹ thuật: ghép TẤT cả câu đã normalize thành 1 chuỗi concat — tìm sig trang
     * (48 ký tự đầu) bằng `indexOf` trong CONCAT (từ offset của cursor) → sig vượt
     * ranh giới câu vẫn khớp (bài học E2.1: contains trên từng câu = 0 match khi
     * câu ngắn hơn sig). Vị trí match → sentence index qua mảng offset.
     * Trang trắng / không match → giữ cursor. Trả IntArray rỗng khi input rỗng
     * hoặc concat quá lớn (tránh spike RAM sách khổng lồ → caller fallback tỉ lệ).
     */
    internal fun matchPageStarts(pages: List<BlockTextReader.PdfPageText>, sentences: List<String>): IntArray {
        if (pages.isEmpty() || sentences.isEmpty()) return IntArray(0)
        val normSent = Array(sentences.size) { normalize(sentences[it]) }
        val offsets = IntArray(normSent.size)
        var totalLen = 0
        for (i in normSent.indices) {
            offsets[i] = totalLen
            totalLen += normSent[i].length
        }
        if (totalLen > 16_000_000) return IntArray(0) // sách khổng lồ → fallback tỉ lệ
        val concat = StringBuilder(totalLen)
        for (s in normSent) concat.append(s)
        val starts = IntArray(pages.size)
        var cursor = 0
        for (i in pages.indices) {
            val sig = normalize(pages[i].text).take(SIG_CHARS)
            if (sig.length >= 8) {
                val p = concat.indexOf(sig, offsets[cursor])
                if (p >= 0) {
                    var j = cursor
                    while (j + 1 < offsets.size && offsets[j + 1] <= p) j++
                    cursor = j
                }
            }
            starts[i] = cursor // trang trắng/không match → giữ cursor (câu trang trước)
        }
        return starts
    }

    /** Đọc cache — null nếu thiếu/hỏng/src đổi (size hoặc mtime). */
    fun load(dir: File, key: String, srcFile: File): IntArray? {
        val f = file(dir, key)
        if (!f.exists()) return null
        return try {
            RandomAccessFile(f, "r").use { raf ->
                if (raf.readInt() != MAGIC) return null
                if (raf.readInt() != VERSION) return null
                if (raf.readLong() != srcFile.length()) return null
                if (raf.readLong() != srcFile.lastModified()) return null
                val count = raf.readInt()
                if (count < 0 || count > MAX_PAGES) return null
                val arr = IntArray(count)
                for (i in 0 until count) arr[i] = raf.readInt()
                arr
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Ghi cache best-effort (lỗi đĩa → bỏ qua, map vẫn trả về RAM). */
    fun save(dir: File, key: String, srcFile: File, starts: IntArray) {
        if (starts.isEmpty()) return
        try {
            if (!dir.exists()) dir.mkdirs()
            RandomAccessFile(file(dir, key), "rw").use { raf ->
                raf.setLength(0)
                raf.writeInt(MAGIC)
                raf.writeInt(VERSION)
                raf.writeLong(srcFile.length())
                raf.writeLong(srcFile.lastModified())
                raf.writeInt(starts.size)
                for (v in starts) raf.writeInt(v)
            }
        } catch (_: Exception) { /* cache best-effort */ }
    }

    /**
     * Build map từ PDF (streaming pages — chỉ giữ sig theo trang, không giữ text)
     * + ghi cache. Trả IntArray rỗng nếu PDF không trích được text theo trang.
     */
    fun build(srcFile: File, sentences: List<String>, cacheDir: File?, cacheKey: String): IntArray {
        val byPage = HashMap<Int, StringBuilder>()
        var maxPage = -1
        for (p in BlockTextReader.iterPdfTextPages(srcFile)) {
            if (p.page < 0) continue
            if (p.page > MAX_PAGES) continue
            maxPage = maxOf(maxPage, p.page)
            val sb = byPage.getOrPut(p.page) { StringBuilder() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(p.text)
        }
        if (maxPage < 0) return IntArray(0)
        val pages = ArrayList<BlockTextReader.PdfPageText>(maxPage + 1)
        for (i in 0..maxPage) {
            pages.add(BlockTextReader.PdfPageText(i, byPage[i]?.toString() ?: ""))
        }
        val starts = matchPageStarts(pages, sentences)
        if (cacheDir != null && starts.isNotEmpty()) save(cacheDir, cacheKey, srcFile, starts)
        return starts
    }
}
