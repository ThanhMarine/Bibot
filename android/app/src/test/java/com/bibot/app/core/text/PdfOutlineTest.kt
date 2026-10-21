package com.bibot.app.core.text

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * E2.3 — TOC từ bookmark `/Outlines` của PDF: DFS-preorder (/First → con → /Next),
 * dest `[N 0 R …]` → chỉ số trang theo page-tree, title literal (escape) / hex UTF-16.
 * PDF fixture tự chế (không nén, không xref — parser regex không cần).
 */
class PdfOutlineTest {

    /**
     * PDF `pages` trang (obj 1=Catalog, 2=Pages, trang i = 3+2i, content 4+2i)
     * + `extraObjs` (outline…) — sắp theo số obj khi ghi.
     */
    private fun buildRaw(pages: Int, extraObjs: Map<Int, String> = emptyMap()): String {
        val objs = LinkedHashMap<Int, String>()
        objs[1] = "<< /Type /Catalog /Pages 2 0 R >>"
        val kids = (0 until pages).joinToString(" ") { "${3 + 2 * it} 0 R" }
        objs[2] = "<< /Type /Pages /Kids [$kids] /Count $pages >>"
        for (i in 0 until pages) {
            val p = 3 + 2 * i
            val c = 4 + 2 * i
            objs[p] = "<< /Type /Page /Parent 2 0 R /Contents $c 0 R /MediaBox [0 0 612 792] >>"
            objs[c] = "<< /Length ${10 + i} >>\nstream\nBT (P$i) Tj ET\nendstream"
        }
        objs.putAll(extraObjs)
        val sb = StringBuilder("%PDF-1.4\n")
        for ((n, body) in objs.toSortedMap()) sb.append("$n 0 obj\n$body\nendobj\n")
        sb.append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
        return sb.toString()
    }

    private val rootAndChain = mapOf(
        10 to "<< /Type /Outlines /First 11 0 R /Last 14 0 R /Count 4 >>",
        // 11: Introduction (trang 0) có 2 con 12,13 rồi kế 14
        11 to "<< /Title (Introduction) /Parent 10 0 R /First 12 0 R /Next 14 0 R " +
            "/Dest [3 0 R /Fit] >>",
        12 to "<< /Title (1.1 Sub) /Parent 11 0 R /Next 13 0 R /Dest [5 0 R /Fit] >>",
        13 to "<< /Title (1.2 Sub) /Parent 11 0 R /Dest [7 0 R /Fit] >>",
        14 to "<< /Title (Appendix) /Parent 10 0 R /Dest [7 0 R /Fit] >>"
    )

    @Test
    fun outline_dfsPreorder_pagesFromPageTree() {
        val out = BlockTextReader.parsePdfOutlineRaw(buildRaw(3, rootAndChain))
        assertEquals(4, out.size)
        // Preorder: cây con 11 (12→13) xong mới tới 14.
        assertEquals(listOf("Introduction", "1.1 Sub", "1.2 Sub", "Appendix"), out.map { it.title })
        assertEquals(listOf(0, 1, 2, 2), out.map { it.page })
    }

    @Test
    fun title_literalEscapesAndNestedParen() {
        val raw = buildRaw(
            2,
            mapOf(
                10 to "<< /Type /Outlines /First 11 0 R >>",
                11 to "<< /Title (Chapter (Intro) \\(v2\\) \\101) /Parent 10 0 R " +
                    "/Dest [3 0 R /Fit] >>"
            )
        )
        val out = BlockTextReader.parsePdfOutlineRaw(raw)
        assertEquals(1, out.size)
        assertEquals("Chapter (Intro) (v2) A", out[0].title) // \\( \\) giữ ngoặc, \\101 = 'A'
        assertEquals(0, out[0].page)
    }

    @Test
    fun title_hexUtf16Be() {
        val text = "Chương 1"
        val hex = buildString {
            append("FEFF")
            for (b in text.toByteArray(Charsets.UTF_16BE)) append("%02X".format(b))
        }
        val raw = buildRaw(
            1,
            mapOf(
                10 to "<< /Type /Outlines /First 11 0 R >>",
                11 to "<< /Title <$hex> /Parent 10 0 R /Dest [3 0 R /Fit] >>"
            )
        )
        val out = BlockTextReader.parsePdfOutlineRaw(raw)
        assertEquals(1, out.size)
        assertEquals("Chương 1", out[0].title)
        assertEquals(0, out[0].page)
    }

    @Test
    fun dest_viaActionGoTo() {
        val raw = buildRaw(
            3,
            mapOf(
                10 to "<< /Type /Outlines /First 11 0 R >>",
                11 to "<< /Title (Goto) /Parent 10 0 R /A << /S /GoTo /D [7 0 R /XYZ 0 0 0] >> >>"
            )
        )
        val out = BlockTextReader.parsePdfOutlineRaw(raw)
        assertEquals(1, out.size)
        assertEquals(2, out[0].page) // obj 7 = trang index 2
    }

    @Test
    fun noOutlines_empty() {
        assertTrue(BlockTextReader.parsePdfOutlineRaw(buildRaw(2)).isEmpty())
    }

    @Test
    fun namedDestOrMissingPageRef_pageMinusOne() {
        val raw = buildRaw(
            2,
            mapOf(
                10 to "<< /Type /Outlines /First 11 0 R /Last 12 0 R >>",
                11 to "<< /Title (Named) /Parent 10 0 R /Dest (intro) /Next 12 0 R >>",
                12 to "<< /Title (Ghost) /Parent 10 0 R /Dest [99 0 R /Fit] >>"
            )
        )
        val out = BlockTextReader.parsePdfOutlineRaw(raw)
        assertEquals(2, out.size)
        assertEquals(-1, out[0].page) // dest theo tên → không map được
        assertEquals(-1, out[1].page) // ref trỏ obj không tồn tại
    }

    @Test
    fun rootWithoutFirst_orMissingFile_empty() {
        val raw = buildRaw(1, mapOf(10 to "<< /Type /Outlines /Count 0 >>"))
        assertTrue(BlockTextReader.parsePdfOutlineRaw(raw).isEmpty())
        assertTrue(BlockTextReader.parsePdfOutline(File("no-such-file.pdf")).isEmpty())
    }

    @Test
    fun titleMissing_entrySkipped() {
        val raw = buildRaw(
            2,
            mapOf(
                10 to "<< /Type /Outlines /First 11 0 R /Last 12 0 R >>",
                11 to "<< /Parent 10 0 R /Dest [3 0 R /Fit] /Next 12 0 R >>", // thiếu title
                12 to "<< /Title (Kept) /Parent 10 0 R /Dest [5 0 R /Fit] >>"
            )
        )
        val out = BlockTextReader.parsePdfOutlineRaw(raw)
        assertEquals(1, out.size)
        assertEquals("Kept", out[0].title)
        assertEquals(1, out[0].page)
    }
}
