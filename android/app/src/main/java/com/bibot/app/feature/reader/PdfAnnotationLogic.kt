package com.bibot.app.feature.reader

/**
 * E2.4 (annotation persist) — logic thuần highlight theo text đã trích của PDF:
 * PdfRenderer không expose glyph nên highlight nằm ở text panel, neo offsets
 * deterministic theo fileHash — cùng file → cùng extract → cùng offsets (E1.3).
 * Không phụ thuộc Android/Room → test JVM thuần.
 */
object PdfAnnotationLogic {

    /** Chuẩn hóa selection (handlesCrossed → start > end) → IntRange kẹp [0, textLen]; null nếu rỗng. */
    fun clampSelection(start: Int, end: Int, textLen: Int): IntRange? {
        val s = minOf(start, end).coerceIn(0, textLen)
        val e = maxOf(start, end).coerceIn(0, textLen)
        return if (e > s) s until e else null
    }

    /** Snippet lưu/hiện chip (collapse whitespace, cắt max — chống phình row từ selection kéo dài). */
    fun snippet(text: String, start: Int, end: Int, maxChars: Int = 200): String {
        val r = clampSelection(start, end, text.length) ?: return ""
        return text.substring(r.first, r.last + 1)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)
    }

    /**
     * Range nền highlight cho pageText: offsets còn khớp → dùng trực tiếp;
     * lệch (extract đổi — hiếm, cùng fileHash thường ổn định) → fallback
     * indexOf theo text đã lưu. (start, end, textSnippet) = dữ liệu đã lưu.
     */
    fun highlightRanges(pageText: String, saved: List<Triple<Int, Int, String>>): List<IntRange> =
        saved.mapNotNull { (start, end, stored) ->
            clampSelection(start, end, pageText.length)
                ?: pageText.indexOf(stored).takeIf { i -> i >= 0 }
                    ?.let { i -> i until minOf(i + stored.length, pageText.length) }
        }

    /** Nhãn chip list highlights: "P3 • snippet". */
    fun chipLabel(page: Int, snippet: String): String = "P${page + 1} • $snippet"
}
