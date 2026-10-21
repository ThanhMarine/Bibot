package com.bibot.app.feature.reader

import java.io.File
import java.security.MessageDigest

/**
 * B3+F2 (Touch_point) — điểm đọc CHỦ ĐỘNG của user.
 *
 * - TEXT: neo = {chapterIdx, sentIdx} chính xác (câu user chạm).
 * - PAGE (PDF): neo = {pageIndex}. E2.1: text PDF trích THEO TRANG
 *   (BlockTextReader.iterPdfTextPages) → PdfPageMap ánh xạ pageIndex → câu
 *   chính xác (không còn "khoảng X%"); fallback tỉ lệ chỉ khi PDF không
 *   dựng được page-tree (stream-only / file >12MB).
 *
 * Persist: BookProgress(anchorPage, anchorOffset, readMode, fileHash) — neo sống
 * khi move/re-download nhờ fileHash (E1.3), không phụ thuộc path.
 */
enum class ReaderView { TEXT, PAGE }

enum class ReadMode { BROWSE, READ }

data class ReadAnchor(
    val view: ReaderView,
    val page: Int, // TEXT: chapterIdx; PAGE: pageIndex
    val offset: Int // TEXT: sentIdx; PAGE: 0
) {
    /** Câu TTS tương ứng trong chapter hiện tại (TEXT) hoặc ước lượng (PAGE). */
    fun resolveSentence(totalInChapter: Int, pageCount: Int, totalSentences: Int): Pair<Int, Int> {
        return if (view == ReaderView.TEXT) {
            page.coerceAtLeast(0) to offset.coerceIn(0, (totalInChapter - 1).coerceAtLeast(0))
        } else {
            if (pageCount <= 0 || totalSentences <= 0) return 0 to 0
            val frac = ((page + 0.5) / pageCount).coerceIn(0.0, 1.0)
            // PDF prep Bibot = 1 chương duy nhất → câu = tỉ lệ trên toàn sách.
            val sent = (frac * totalSentences).toInt().coerceIn(0, (totalSentences - 1).coerceAtLeast(0))
            0 to sent
        }
    }

    fun describe(): String = if (view == ReaderView.TEXT) "C${page + 1}:S${offset + 1}"
    else "P${page + 1}"
}

/** SHA-1 streaming của file (E1.3 identity, cap 64MB để không spike RAM). */
fun sha1File(file: File, capBytes: Long = 64L * 1024 * 1024): String {
    return try {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            var total = 0L
            while (total < capBytes) {
                val r = input.read(buf, 0, minOf(buf.size, (capBytes - total).toInt()))
                if (r < 0) break
                md.update(buf, 0, r)
                total += r
            }
        }
        md.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    } catch (_: Exception) {
        ""
    }
}
