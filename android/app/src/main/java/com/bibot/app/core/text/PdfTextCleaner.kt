package com.bibot.app.core.text

import java.util.regex.Pattern

/**
 * Port Pi services/text_cleaner.py:62 + pdf_to_md.py clean_for_tts
 * Header/footer lặp ≥40% trang, số trang, URL, blanks.
 */
object PdfTextCleaner {
    private val urlRegex = Regex("""(?i)(?:https?|ftp)://[^\s/$.?#].[^\s]*|www\.[^\s/$.?#].[^\s]*|[a-z0-9-]+\.(?:com|vn|org|net|edu|gov|io|co|info|biz|me|xyz|top|site|online|tech|app|dev|ai)[^\s]*""")
    private val pageNumRegex = Regex("""(?m)^\s*\d+\s*$|^\s*[-\u2013\u2014]\s*\d+\s*[-\u2013\u2014]\s*$|^\s*(?:trang|page|p\.)\s*\d+\s*(?:/\s*\d+)?\s*$""", RegexOption.IGNORE_CASE)
    private val headerHints = listOf(
        "truyen","truyện","sach","sách","book","chapter","chuong","chương",
        "tac gia","tác giả","author","nxb","nha xuat ban","copyright","ban quyen","bản quyền","all rights reserved",
        "download","tai lieu","tải liệu","pdf","epub","mobi","trang chu","trang chủ","home","menu","muc luc","mục lục",
        "www.","http",".com",".vn",".org",".net","facebook","youtube","instagram","tiktok","telegram","zalo",
        "email:","e-mail:","lien he","liên hệ","hotline","so dien thoai"
    )
    private const val MAX_HF_LENGTH = 120
    private const val HF_REPEAT_THRESHOLD = 0.4

    private fun isLikelyHeaderFooter(line: String): Boolean {
        val lower = line.lowercase().trim()
        if (lower.isEmpty()) return false
        if (lower.length > MAX_HF_LENGTH) return false
        if (pageNumRegex.containsMatchIn(lower)) return true
        if (headerHints.any { it in lower }) return true
        if (Regex("""^[\d\W_]+$""").containsMatchIn(lower)) return true
        return false
    }

    fun detectHeaderFooter(pagesText: List<String>, topN: Int = 3, bottomN: Int = 3): Set<String> {
        if (pagesText.size < 3) return emptySet()
        val topCounter = mutableMapOf<String, Int>()
        val bottomCounter = mutableMapOf<String, Int>()
        for (pt in pagesText) {
            val lines = pt.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            for (ln in lines.take(topN)) if (isLikelyHeaderFooter(ln)) topCounter[ln] = (topCounter[ln] ?: 0) + 1
            for (ln in lines.takeLast(bottomN)) if (isLikelyHeaderFooter(ln)) bottomCounter[ln] = (bottomCounter[ln] ?: 0) + 1
        }
        val minCount = maxOf(2, (pagesText.size * HF_REPEAT_THRESHOLD).toInt())
        val hf = mutableSetOf<String>()
        for ((k, c) in topCounter) if (c >= minCount) hf.add(k)
        for ((k, c) in bottomCounter) if (c >= minCount) hf.add(k)
        return hf
    }

    fun removeHeaderFooter(text: String, hfLines: Set<String>): String {
        if (text.isEmpty() || hfLines.isEmpty()) return text
        return text.lines().filter { it.trim() !in hfLines }.joinToString("\n")
    }

    fun removeUrls(text: String): String = if (text.isEmpty()) "" else urlRegex.replace(text, " ")

    fun removePageNumbers(text: String): String {
        if (text.isEmpty()) return ""
        return text.lines().filter { !pageNumRegex.containsMatchIn(it.trim()) }.joinToString("\n")
    }

    fun cleanPdfText(text: String, hfLines: Set<String>? = null): String {
        if (text.isEmpty()) return ""
        var t = text
        // TCVN3/VNI → UTF-8 convert (cho PDF cũ encode tiếng Việt)
        t = convertTcvn3VniToUtf8(t)
        // Markup thừa TRƯỚC mọi thứ khác: comment `<!-- CHAPTER-BREAK -->`, tag `<a id=…>`,
        // bảng `|---|` (nếu không sẽ thành PAUSE2SEC oan) — phải chạy trước khi gộp dòng
        // để regex theo dòng (`^#`, `^>`, `^-`) còn bắt được.
        t = TextCleaner.stripHtml(t)
        t = TextCleaner.stripMarkup(t)
        if (hfLines != null) t = removeHeaderFooter(t, hfLines)
        t = removePageNumbers(t)
        t = removeUrls(t)
        t = TextCleaner.normalizeBlanks(t)
        t = Regex("""\s+""").replace(t, " ").trim()
        return t
    }

    /**
     * Chuyển TCVN3 (TCVN 5712-3 / ABC) sang Unicode — full byte-level.
     * Bảng chuẩn vietunicode.sourceforge.net (74 slot + 60 cặp ghép HOA),
     * có detect chống phá text Latin/Unicode đúng (xem [Tcvn3]).
     * VNI KHÔNG convert ở đây (VNI dùng tone rời/combining, cần engine riêng).
     */
    fun convertTcvn3VniToUtf8(s: String): String = Tcvn3.convertIfTcvn3(s)

    fun preprocessPdfPages(pagesText: List<String>): Pair<List<String>, Set<String>> {
        val hf = detectHeaderFooter(pagesText)
        val cleaned = pagesText.map { cleanPdfText(it, hf) }
        return cleaned to hf
    }
}
