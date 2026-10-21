package com.bibot.app.core.text

/**
 * E2.3 (phương án panel text-layer) — tìm trong text-theo-trang của PDF:
 * mỗi trang tối đa 1 hit (trang đầu chứa match) → danh sách = các TRANG có khớp
 * để nhảy trên view PAGE (PdfRenderer không expose glyph nên không highlight vị trí
 * trên bitmap — nhảy trang là dấu hiệu).
 */
data class PdfTextHit(val page: Int, val snippet: String)

object PdfTextSearch {

    /** Trích đoạn ±`snippetChars` quanh match, collapse whitespace, thêm `…` khi bị cắt. */
    private fun snippetAround(text: String, at: Int, needleLen: Int, snippetChars: Int): String {
        val s = (at - snippetChars / 3).coerceAtLeast(0)
        val e = (at + needleLen + snippetChars * 2 / 3).coerceAtMost(text.length)
        val body = text.substring(s, e).replace(Regex("""\s+"""), " ").trim()
        return (if (s > 0) "…" else "") + body + (if (e < text.length) "…" else "")
    }

    /**
     * Tìm `query` (không phân biệt hoa/thường, ≥2 ký tự) trong từng trang.
     * Trả hit ĐẦU TIÊN mỗi trang, tối đa [maxHits] hit theo thứ tự trang.
     */
    fun search(pages: List<String>, query: String, maxHits: Int = 50, snippetChars: Int = 90): List<PdfTextHit> {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return emptyList()
        val out = ArrayList<PdfTextHit>()
        for ((page, text) in pages.withIndex()) {
            if (out.size >= maxHits || text.isEmpty()) continue
            val p = text.lowercase().indexOf(needle)
            if (p < 0) continue
            out.add(PdfTextHit(page, snippetAround(text, p, needle.length, snippetChars)))
        }
        return out
    }
}
