package com.bibot.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2.4 (annotation persist) — test logic thuần highlight PDF: chuẩn hóa selection
 * (handlesCrossed + clamp), snippet, range nền (offsets + fallback indexOf), nhãn chip.
 */
class PdfAnnotationLogicTest {

    // --- clampSelection ---
    @Test fun `clamp selection binh thuong`() {
        assertEquals(2 until 7, PdfAnnotationLogic.clampSelection(2, 7, 10))
    }

    @Test fun `clamp selection handlesCrossed dao nguoc van ra range thuan`() {
        assertEquals(2 until 7, PdfAnnotationLogic.clampSelection(7, 2, 10))
    }

    @Test fun `clamp selection vuot qua do dai text bi kep vao`() {
        assertEquals(0 until 10, PdfAnnotationLogic.clampSelection(-3, 99, 10))
    }

    @Test fun `clamp selection rong hoac nghienh tra ve null`() {
        assertNull(PdfAnnotationLogic.clampSelection(4, 4, 10)) // start == end
        assertNull(PdfAnnotationLogic.clampSelection(0, 0, 0)) // text rỗng
    }

    // --- snippet ---
    @Test fun `snippet collapse whitespace va cat max`() {
        assertEquals("hello world", PdfAnnotationLogic.snippet("hello \n\t world", 0, 15))
        assertEquals("abc", PdfAnnotationLogic.snippet("abcdef", 0, 6, maxChars = 3))
    }

    @Test fun `snippet range rong tra ve rong`() {
        assertEquals("", PdfAnnotationLogic.snippet("abc", 1, 1))
    }

    // --- highlightRanges ---
    @Test fun `highlight offsets con khop dung offsets`() {
        val ranges = PdfAnnotationLogic.highlightRanges(
            "hello beautiful world",
            listOf(Triple(6, 15, "beautiful"))
        )
        assertEquals(listOf(6 until 15), ranges)
    }

    @Test fun `highlight offsets lech fallback indexOf`() {
        // offset trỏ ra ngoài (77 > textLen) nhưng text còn → indexOf tìm lại được
        val ranges = PdfAnnotationLogic.highlightRanges(
            "hello beautiful world",
            listOf(Triple(77, 99, "beautiful"))
        )
        assertEquals(listOf(6 until 15), ranges)
    }

    @Test fun `highlight khong khop gi ca bo qua`() {
        // Cả 2 case: offsets ngoài bounds + text không có trong page → bỏ qua.
        // (Offsets in-bounds luôn được trust — cùng fileHash → deterministic;
        // snippet có thể đã collapse whitespace nên không verify substring.)
        val ranges = PdfAnnotationLogic.highlightRanges(
            "hello world",
            listOf(Triple(77, 99, "vanish"), Triple(50, 60, "also-gone"))
        )
        assertTrue(ranges.isEmpty())
    }

    @Test fun `highlight nhieu annotation cung trang`() {
        val ranges = PdfAnnotationLogic.highlightRanges(
            "alpha beta gamma",
            listOf(Triple(0, 5, "alpha"), Triple(10, 15, "gamma"))
        )
        assertEquals(listOf(0 until 5, 10 until 15), ranges)
    }

    // --- chipLabel ---
    @Test fun `chip label hien thi trang 1-based`() {
        assertEquals("P3 • snippet…", PdfAnnotationLogic.chipLabel(2, "snippet…"))
    }
}
