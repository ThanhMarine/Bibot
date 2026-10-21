package com.bibot.app.core.text

import org.junit.Assert.*
import org.junit.Test

/**
 * E2.3 panel text-layer — tìm text-theo-trang: 1 hit/trang (trang có khớp để nhảy PAGE),
 * không phân biệt hoa/thường, snippet có ngữ cảnh + …, trần số hit.
 */
class PdfTextSearchTest {

    private val pages = listOf(
        "MARKER-PAGE-01 opens this page with confidence.",
        "Nothing interesting on this second sheet at all.",
        "PAGE-THREE mentions Piston twice? Piston again for good measure.",
        "Piston appears early here."
    )

    @Test
    fun search_basicCaseInsensitive() {
        val hits = PdfTextSearch.search(pages, "piston")
        assertEquals(listOf(2, 3), hits.map { it.page }) // 1 hit/trang, trang 1&2 không khớp
        assertTrue(hits[0].snippet.contains("Piston"))
    }

    @Test
    fun search_minLengthOrBlank_noHits() {
        assertTrue(PdfTextSearch.search(pages, "p").isEmpty())
        assertTrue(PdfTextSearch.search(pages, "   ").isEmpty())
    }

    @Test
    fun search_noMatch_empty() {
        assertTrue(PdfTextSearch.search(pages, "unicorn").isEmpty())
    }

    @Test
    fun search_snippetContextWithEllipsis() {
        // Trang 3: "Piston" ở giữa → snippet có … 2 đầu + whitespace collapse.
        val hits = PdfTextSearch.search(listOf(pages[2]), "piston", snippetChars = 40)
        assertEquals(1, hits.size)
        val sn = hits[0].snippet
        assertTrue(sn.startsWith("…"))
        assertTrue(sn.endsWith("…"))
        assertFalse(sn.contains("  ")) // whitespace đã collapse
        assertTrue(sn.contains("Piston"))
    }

    @Test
    fun search_snippetAtStart_noLeadingEllipsis() {
        val hits = PdfTextSearch.search(listOf(pages[3]), "piston", snippetChars = 40)
        assertEquals(1, hits.size)
        assertFalse(hits[0].snippet.startsWith("…"))
    }

    @Test
    fun search_maxHitsCap() {
        val many = List(10) { "Piston page $it." }
        val hits = PdfTextSearch.search(many, "piston", maxHits = 4)
        assertEquals(4, hits.size)
        assertEquals(listOf(0, 1, 2, 3), hits.map { it.page })
    }

    @Test
    fun search_emptyPagesList_empty() {
        assertTrue(PdfTextSearch.search(emptyList(), "piston").isEmpty())
    }
}
