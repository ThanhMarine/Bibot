package com.bibot.app.feature.reader

/**
 * R0 — Heading pattern dùng chung cho mọi kho (F0.1).
 * Chuyển nguyên văn từ StoryScreen (định nghĩa gốc duy nhất); BookScreen vốn
 * import chéo từ feature.story — nay cả hai cùng dùng bản này.
 * Giữ hàm forward ở feature.story để không vỡ import cũ (deprecated).
 */
private val headingSearchRegex = Regex(
    """(?m)^(?:##\s+|Chương\s|Chuong\s|Phần\s|Phan\s|Hồi\s|Hoi\s|Quyển\s|Quyen\s|Tập\s|Tap\s|Mục\s|Muc\s|Truyện\s|Truyen\s|Chuyện\s|Chuyen\s|Sự tích\s|Su tich\s|Chapter\s|Part\s|Section\s|Book\s)""",
    RegexOption.IGNORE_CASE
)

/** Vị trí heading (#) kế tiếp sau [currentPos], null nếu hết. */
fun findNextHeading(text: String, currentPos: Int): Int? {
    val matches = headingSearchRegex.findAll(text).toList()
    for (m in matches) {
        if (m.range.first > currentPos) return m.range.first
    }
    return null
}

/** Vị trí heading (#) ngay trước [currentPos], null nếu đang ở đầu. */
fun findPrevHeading(text: String, currentPos: Int): Int? {
    val matches = headingSearchRegex.findAll(text).toList()
    for (i in matches.lastIndex downTo 0) {
        if (matches[i].range.first < currentPos) return matches[i].range.first
    }
    return null
}

// --- G3.2 — Tiêu đề chương đọc-pause -------------------------------------------------

/** Sách không có heading chuẩn → BlockTextReader tách "Đoạn N" (nhãn chế tạo, không đọc). */
private val syntheticParagraphTitle = Regex("""^Đoạn\s+\d+$""", RegexOption.IGNORE_CASE)

/** Tiêu đề chương quá dài (>200) không đọc — tránh TTS đọc 1 mạch nguyên dòng. */
private const val MAX_TITLE_ANNOUNCE_CHARS = 200

/** So khớp tiêu đề ↔ câu đầu: bỏ markdown/dấu câu/nhân đôi khoảng trắng. */
internal fun normalizeTitleForCompare(s: String): String = s
    .lowercase()
    .replace(Regex("""[#*`_~"“”'’()\[\]{}:;,.\-–—…!?]+"""), " ")
    .replace(Regex("""\s+"""), " ")
    .trim()

/**
 * G3.2 — Tiêu đề chương có nên phát thành CHUNK ĐẦU TIÊN kèm pause không?
 *
 * @param chapterCount tổng số chương của sách (1 chương = sách PDF/đoạn liền → không đọc)
 * @param firstSentence câu ĐẦU TIÊN sẽ đọc tại vị trí đang vào (dup-guard với tiêu đề)
 *
 * Loại: rỗng, quá dài, nhãn "Đoạn N" chế tạo, và tiêu đề đã nằm trong câu đầu
 * (EPUB h1/heading lọt vào content → đọc cả 2 là nghe lặp).
 */
fun shouldAnnounceChapterTitle(title: String, chapterCount: Int, firstSentence: String?): Boolean {
    val t = title.trim()
    if (t.isEmpty() || t.length > MAX_TITLE_ANNOUNCE_CHARS) return false
    if (chapterCount <= 1) return false
    if (syntheticParagraphTitle.matches(t)) return false
    val nt = normalizeTitleForCompare(t)
    if (nt.isEmpty()) return false
    val ns = normalizeTitleForCompare(firstSentence ?: "")
    if (ns.isEmpty()) return true
    // Câu đầu là tiêu đề hoặc mở đầu bằng tiêu đề → không đọc lại.
    if (ns == nt) return false
    if (nt.length >= 4 && (ns.startsWith(nt) || nt.startsWith(ns))) return false
    return true
}
