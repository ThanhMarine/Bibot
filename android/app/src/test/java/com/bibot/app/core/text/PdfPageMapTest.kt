package com.bibot.app.core.text

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * E2.1 — PdfPageMap: match pageIndex → câu mở đầu (signature match, monotonic)
 * + cache .pagemap roundtrip (invalidates khi src đổi size/mtime).
 */
class PdfPageMapTest {

    private fun pages(vararg texts: String): List<BlockTextReader.PdfPageText> =
        texts.mapIndexed { i, t -> BlockTextReader.PdfPageText(i, t) }

    @Test
    fun normalize_lettersDigitsOnlyLowercase() {
        assertEquals("helloworld", PdfPageMap.normalize("Hello, World!"))
        assertEquals("trangmốt", PdfPageMap.normalize("Trang Mốt."))
        assertEquals("page45", PdfPageMap.normalize("page 45°")) // ° là symbol → bỏ
    }

    @Test
    fun match_exactPageStarts() {
        val p = pages(
            "Hello world from page one. Trailing words here.",
            "Second page begins here entirely.",
            "Third page opens with new ideas."
        )
        val sents = listOf(
            "Hello world from page one. Trailing words here.",
            "Second page begins here entirely.",
            "Third page opens with new ideas."
        )
        val starts = PdfPageMap.matchPageStarts(p, sents)
        assertArrayEquals(intArrayOf(0, 1, 2), starts)
    }

    @Test
    fun match_midSentencePageStart() {
        // Trang 2 bắt đầu giữa câu 1 → start trang 2 = câu 1 (chứa đầu trang).
        val p = pages(
            "The quick brown fox jumps over the lazy dog today okay more tail.",
            "the lazy dog today okay next page material here and more words."
        )
        val sents = listOf(
            "The quick brown fox jumps over the lazy dog today okay more tail.",
            "the lazy dog today okay next page material here and more words."
        )
        val starts = PdfPageMap.matchPageStarts(p, sents)
        assertArrayEquals(intArrayOf(0, 1), starts)
    }

    @Test
    fun match_blankPageKeepsCursor() {
        val p = pages(
            "Alpha beta gamma delta epsilon zeta.",
            "",
            "Eta theta iota kappa lambda mu nu."
        )
        val sents = listOf(
            "Alpha beta gamma delta epsilon zeta.",
            "Eta theta iota kappa lambda mu nu."
        )
        val starts = PdfPageMap.matchPageStarts(p, sents)
        assertArrayEquals(intArrayOf(0, 0, 1), starts)
    }

    @Test
    fun match_monotonicWhenSigMissing() {
        // Sig trang 2 không nằm trong câu nào → giữ cursor (không nhảy lung tung).
        val p = pages(
            "Known start sentence number one.",
            "ZZZZQQQQWWWWEEEERRRRTTTTYYYY nothing matches this sig anywhere.",
            "Known sentence number three back."
        )
        val sents = listOf(
            "Known start sentence number one.",
            "Known sentence number three back."
        )
        val starts = PdfPageMap.matchPageStarts(p, sents)
        assertEquals(3, starts.size)
        assertTrue(starts[1] >= starts[0])
        assertTrue(starts[2] >= starts[1])
        assertEquals(0, starts[0])
        assertEquals(1, starts[2])
    }

    @Test
    fun match_sigCrossesSentenceBoundary() {
        // Bài học E2.1 thật: sig 48 ký tự VƯỢT câu (câu ngắn hơn sig) —
        // contains-từng-câu = 0 match → cần match trên CONCAT.
        val p = pages(
            "PAGE-TWO-ALPHA opens this page with confidence. Sentence two continues the story line.",
            "PAGE-THREE-BETA opens this page with confidence. Sentence two continues the story line."
        )
        val sents = listOf(
            "PAGE-ONE-OMEGA ends the first page here.",
            "PAGE-TWO-ALPHA opens this page with confidence.",
            "Sentence two continues the story line.",
            "PAGE-THREE-BETA opens this page with confidence.",
            "Sentence two continues the story line."
        )
        val starts = PdfPageMap.matchPageStarts(p, sents)
        assertEquals(2, starts.size)
        assertEquals(1, starts[0]) // trang 1 → câu "PAGE-TWO-ALPHA…" (sig vượt sang câu 2 vẫn OK)
        assertEquals(3, starts[1]) // trang 2 → câu "PAGE-THREE-BETA…"
    }

    @Test
    fun match_emptyInputs() {
        assertEquals(0, PdfPageMap.matchPageStarts(pages("x y z"), emptyList()).size)
        assertEquals(0, PdfPageMap.matchPageStarts(emptyList(), listOf("a")).size)
    }

    @Test
    fun cache_roundTripAndInvalidateOnSrcChange() {
        val dir = File.createTempFile("pagemap", "dir").let { f -> f.delete(); f.mkdirs(); f }
        try {
            val src = File.createTempFile("e21src", ".pdf")
            src.deleteOnExit()
            src.writeBytes(byteArrayOf(1, 2, 3, 4))
            val starts = intArrayOf(0, 5, 12)
            PdfPageMap.save(dir, "bk1", src, starts)
            assertArrayEquals(starts, PdfPageMap.load(dir, "bk1", src))
            // Đổi src (khác size) → invalidate.
            src.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            assertNull(PdfPageMap.load(dir, "bk1", src))
            // Key lạ → null.
            src.writeBytes(byteArrayOf(1, 2, 3, 4))
            Thread.sleep(10) // mtime khác nhau
            src.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertNull(PdfPageMap.load(dir, "nope", src))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun build_fromRealPdfFixture_mapsPageToSentence() {
        // PDF 3 trang tự chế (cùng fixture style BlockTextReaderPdfPagesTest).
        val body = StringBuilder("%PDF-1.4\n")
        body.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        body.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R] /Count 3 >>\nendobj\n")
        val texts = listOf("PAGE-ONE alpha beta.", "PAGE-TWO gamma delta.", "PAGE-THREE epsilon zeta.")
        for (i in texts.indices) {
            val pageObj = 3 + 2 * i
            val contObj = 4 + 2 * i
            body.append("$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /Contents $contObj 0 R >>\nendobj\n")
            body.append("$contObj 0 obj\n<< /Length 8 >>\nstream\nBT (${texts[i]}) Tj ET\nendstream\nendobj\n")
        }
        body.append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
        val pdf = File.createTempFile("e21map", ".pdf")
        pdf.deleteOnExit()
        pdf.writeText(body.toString(), Charsets.ISO_8859_1)
        val sents = listOf(
            "PAGE-ONE alpha beta.",
            "PAGE-TWO gamma delta.",
            "PAGE-THREE epsilon zeta."
        )
        val dir = File.createTempFile("pagemap2", "dir").let { f -> f.delete(); f.mkdirs(); f }
        try {
            val starts = PdfPageMap.build(pdf, sents, dir, "bk2")
            assertArrayEquals(intArrayOf(0, 1, 2), starts)
            // Cache hit lần 2 (không cần build lại).
            assertArrayEquals(starts, PdfPageMap.load(dir, "bk2", pdf))
        } finally {
            dir.deleteRecursively()
        }
    }
}
