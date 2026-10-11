package com.bibot.app.core.text

import com.bibot.app.core.tts.VietnameseChunker
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * D6/G2 (gom chunk ngoặc/trích dẫn + Fig./No.) và D7/G3 (đề mục dòng ngắn).
 * Chạy: :app:testDemoDebugUnitTest. BlockTextReader/TextCleaner pure-JVM.
 */
class TextRulesTest {

    @Test
    fun chunker_keepsParentheticalWhole() {
        val chunks = VietnameseChunker.split("Xem minh họa ở (Fig. 5) trong sách này.")
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.none { it.trimEnd().endsWith("(Fig.") })
        assertTrue(chunks.any { it.contains("(Fig. 5)") })
    }

    @Test
    fun chunker_keepsQuoteWhole() {
        val chunks = VietnameseChunker.split("Cô ấy nói \"Hôm nay trời đẹp quá các bạn ơi\" rồi mỉm cười.")
        assertTrue(chunks.any { it.contains("\"Hôm nay trời đẹp quá các bạn ơi\"") })
    }

    @Test
    fun chunker_noSplitAfterFigDot() {
        val c = VietnameseChunker()
        val out = mutableListOf<String>()
        out += c.feed("Kết quả xem Fig.")
        out += c.feed(" 5 rất tốt và còn nhiều điều nữa.")
        out += c.flush()
        assertTrue(out.none { it.trimEnd().endsWith("Fig.") })
        assertTrue(out.joinToString(" ").contains("Fig. 5"))
    }

    @Test
    fun figureRefs_viEn() {
        assertEquals("Xem (hình 5) nhé.", TextCleaner.readFigureRefs("Xem (Fig. 5) nhé.", "vi"))
        assertEquals("Trích số 12 hay.", TextCleaner.readFigureRefs("Trích No. 12 hay.", "vi"))
        assertEquals("See (figure 5).", TextCleaner.readFigureRefs("See (Fig. 5).", "en"))
        assertEquals("Bảng number 3.", TextCleaner.readFigureRefs("Bảng No. 3.", "en"))
        // Không chấm → giữ nguyên (không ăn "no 5" tiếng Anh).
        assertEquals("There is no 5 here.", TextCleaner.readFigureRefs("There is no 5 here.", "en"))
        // Qua pipeline đầy đủ.
        assertEquals("Xem (hình 5) nhé.", TextCleaner.cleanForTts("Xem (Fig. 5) nhé.", "vi"))
        assertEquals("See (figure 5).", TextCleaner.cleanForTts("See (Fig. 5).", "en"))
    }

    @Test
    fun citeAbbrev_guard() {
        assertTrue(TextCleaner.endsWithCiteAbbrev("xem minh họa (Fig."))
        assertTrue(TextCleaner.endsWithCiteAbbrev("Mục No."))
        assertFalse(TextCleaner.endsWithCiteAbbrev("câu này."))
        assertFalse(TextCleaner.endsWithCiteAbbrev("xong!"))
    }

    private fun chaptersOf(text: String): List<String> {
        val f = File.createTempFile("g3rule", ".md")
        try {
            f.writeText(text)
            return BlockTextReader.readChapters(f, "T", BlockTextReader.Heading.MARKDOWN_OR_STORY)
                .map { it.title }
        } finally {
            f.delete()
        }
    }

    @Test
    fun shortHeading_splitsChapter() {
        // G3 chỉ tách SAU khi đã vào sách (readingStarted): đề mục ở phần đầu
        // (chưa mốc) vẫn buffer theo D1 — "Tác giả/NXB" không thành chương.
        val titles = chaptersOf(
            "Chương 1\n" +
                "Nội dung chương một bắt đầu ở đây với câu chuyện dài hơi hơn nhiều.\n" +
                "Tiểu sử tác giả\n" +
                "Đoạn văn tiếp theo kể về cuộc đời với nhiều chi tiết hơn nữa.\n"
        )
        assertTrue("want short-heading chapter, got $titles", titles.contains("Tiểu sử tác giả"))
        assertTrue(titles.size >= 2)
    }

    @Test
    fun shortHeading_negatives() {
        // Dòng sau viết thường → wrap dở câu, không tách.
        assertEquals(
            1,
            chaptersOf("gió đưa\ncành trúc la đà\nĐoạn văn dài sau đó tiếp tục kể câu chuyện với nhiều chi tiết hơn nữa.\n").size
        )
        // Số trang đứng riêng → không tách.
        assertEquals(
            1,
            chaptersOf("12\nNội dung câu chuyện bắt đầu từ đây và kéo dài thêm nhiều chữ nữa cho đủ dài.\n").size
        )
        // Mục lục → không tách.
        assertEquals(
            1,
            chaptersOf("Mục lục\nNội dung câu chuyện bắt đầu từ đây và kéo dài thêm nhiều chữ nữa cho đủ dài.\n").size
        )
        // Kết bằng dấu câu → không tách.
        assertEquals(
            1,
            chaptersOf("Hắn mỉm cười, nói:\nNội dung câu chuyện bắt đầu từ đây và kéo dài thêm nhiều chữ nữa.\n").size
        )
    }

    @Test
    fun keywordHeading_stillWorks() {
        val titles = chaptersOf(
            "Chương 1\n" +
                "Nội dung chương một bắt đầu ở đây với câu chuyện dài hơi hơn nhiều.\n"
        )
        assertEquals("Chương 1", titles.first())
    }
}
