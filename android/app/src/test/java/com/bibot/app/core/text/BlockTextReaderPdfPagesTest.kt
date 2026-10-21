package com.bibot.app.core.text

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * E2.1 — trích text PDF theo TRANG: page-tree (Catalog → Pages → Kids) →
 * content-obj → pageIndex; fallback page=-1 khi không có page-tree.
 * PDF fixture tự chế (không nén, uncompressed stream) — parser.inflate fail
 * → tầng raw substring, chữ nằm trong `(…) Tj`.
 */
class BlockTextReaderPdfPagesTest {

    /** Sinh PDF n trang: obj 1=Catalog, 2=Pages, page i = (3+2i) + content (4+2i). */
    private fun buildPdf(texts: List<String>, withTree: Boolean = true): File {
        val sb = StringBuilder("%PDF-1.4\n")
        val objs = LinkedHashMap<Int, String>()
        if (withTree) {
            val kids = texts.indices.joinToString(" ") { "${3 + 2 * it} 0 R" }
            objs[1] = "<< /Type /Catalog /Pages 2 0 R >>"
            objs[2] = "<< /Type /Pages /Kids [$kids] /Count ${texts.size} >>"
        }
        for ((i, t) in texts.withIndex()) {
            val pageObj = 3 + 2 * i
            val contObj = 4 + 2 * i
            objs[pageObj] = "<< /Type /Page /Parent 2 0 R /Contents $contObj 0 R /MediaBox [0 0 612 792] >>"
            objs[contObj] = "<< /Length ${t.length + 10} >>\nstream\nBT ($t) Tj ET\nendstream"
        }
        for ((n, body) in objs.toSortedMap()) {
            sb.append("$n 0 obj\n$body\nendobj\n")
        }
        sb.append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
        val f = File.createTempFile("e21", ".pdf")
        f.deleteOnExit()
        f.writeText(sb.toString(), Charsets.ISO_8859_1)
        return f
    }

    @Test
    fun iterPdfTextPages_pageTreeOrder() {
        val f = buildPdf(
            listOf(
                "PAGE-ONE-MARKER alpha beta gamma.",
                "PAGE-TWO-MARKER delta epsilon.",
                "PAGE-THREE-MARKER zeta eta."
            )
        )
        val pages = BlockTextReader.iterPdfTextPages(f).toList()
        assertEquals(3, pages.size)
        assertEquals(0, pages[0].page)
        assertEquals(1, pages[1].page)
        assertEquals(2, pages[2].page)
        assertTrue(pages[0].text.contains("PAGE-ONE-MARKER"))
        assertTrue(pages[1].text.contains("PAGE-TWO-MARKER"))
        assertTrue(pages[2].text.contains("PAGE-THREE-MARKER"))
    }

    @Test
    fun iterPdfText_parityWithPages() {
        val f = buildPdf(listOf("AAA one.", "BBB two."))
        val flat = BlockTextReader.iterPdfText(f).toList()
        assertEquals(listOf("AAA one.", "BBB two."), flat)
    }

    @Test
    fun iterPdfTextPages_pageDictsNoTree_fallbackObjOrder() {
        // Có dict /Type /Page nhưng thiếu Catalog/Pages → fallback thứ tự obj (=0).
        val f = buildPdf(listOf("ORPHAN-TEXT hello."), withTree = false)
        val pages = BlockTextReader.iterPdfTextPages(f).toList()
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].page)
        assertTrue(pages[0].text.contains("ORPHAN-TEXT"))
    }

    @Test
    fun iterPdfTextPages_noPageDicts_pageMinusOne() {
        // Chỉ có stream text, không có dict trang nào → page=-1 (fallback tỉ lệ).
        val body = StringBuilder("%PDF-1.4\n")
        body.append("1 0 obj\n<< /Length 9 >>\nstream\nBT (ORPHAN) Tj ET\nendstream\nendobj\n")
        body.append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
        val f = File.createTempFile("e21np", ".pdf")
        f.deleteOnExit()
        f.writeText(body.toString(), Charsets.ISO_8859_1)
        val pages = BlockTextReader.iterPdfTextPages(f).toList()
        assertEquals(1, pages.size)
        assertEquals(-1, pages[0].page)
        assertTrue(pages[0].text.contains("ORPHAN"))
    }

    @Test
    fun iterPdfTextPages_contentsArray_samePage() {
        // Một trang /Contents là MẢNG 2 stream → 2 block cùng page index.
        val body = StringBuilder("%PDF-1.4\n")
        body.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        body.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        body.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents [4 0 R 5 0 R] >>\nendobj\n")
        body.append("4 0 obj\n<< /Length 9 >>\nstream\nBT (PART-A) Tj ET\nendstream\nendobj\n")
        body.append("5 0 obj\n<< /Length 9 >>\nstream\nBT (PART-B) Tj ET\nendstream\nendobj\n")
        body.append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
        val f = File.createTempFile("e21arr", ".pdf")
        f.deleteOnExit()
        f.writeText(body.toString(), Charsets.ISO_8859_1)
        val pages = BlockTextReader.iterPdfTextPages(f).toList()
        assertEquals(2, pages.size)
        assertEquals(0, pages[0].page)
        assertEquals(0, pages[1].page)
        assertTrue(pages[0].text.contains("PART-A"))
        assertTrue(pages[1].text.contains("PART-B"))
    }

    @Test
    fun buildContentPageMap_pageWithNoContents_stillCountsIndex() {
        // Trang 2 (obj 5) không có /Contents (ảnh) — trang 3 (obj 7) vẫn là index 2.
        val body = StringBuilder("%PDF-1.4\n")
        body.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        body.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R] /Count 3 >>\nendobj\n")
        body.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>\nendobj\n")
        body.append("4 0 obj\n<< /Length 9 >>\nstream\nBT (P1TEXT) Tj ET\nendstream\nendobj\n")
        body.append("5 0 obj\n<< /Type /Page /Parent 2 0 R >>\nendobj\n")
        body.append("7 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 8 0 R >>\nendobj\n")
        body.append("8 0 obj\n<< /Length 9 >>\nstream\nBT (P3TEXT) Tj ET\nendstream\nendobj\n")
        body.append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
        val raw = body.toString()
        val map = BlockTextReader.buildContentPageMap(raw)
        assertEquals(0, map[4])
        assertEquals(2, map[8])
        assertFalse(map.containsKey(6)) // không có content obj cho trang ảnh
    }
}
