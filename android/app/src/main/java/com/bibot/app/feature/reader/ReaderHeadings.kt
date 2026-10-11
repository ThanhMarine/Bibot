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
