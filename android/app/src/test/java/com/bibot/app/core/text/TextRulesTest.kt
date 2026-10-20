package com.bibot.app.core.text

import com.bibot.app.core.tts.VietnameseChunker
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * D6/G2 (gom chunk ngoặc/trích dẫn + Fig./No.) và D7/G3 (đề mục dòng ngắn).
 * Chạy: :app:testDemoDebugUnitTest. BlockTextReader/TextCleaner pure-JVM.
 */
class TextRulesTest {

    companion object {
        // H2.1: hàm cứng đã xóa — cleanForTts chỉ còn đường ruler; nạp JSON thật
        // (giống ReadingRulesTest) để test chạy đường production. Reset sau để
        // không rò rỉ sang test class khác (chunkGuard default).
        @BeforeClass
        @JvmStatic
        fun setupRules() {
            val candidates = listOf(
                File("src/main/assets/reading-rules.json"),
                File("app/src/main/assets/reading-rules.json")
            )
            val f = requireNotNull(candidates.firstOrNull { it.exists() }) {
                "thiếu assets reading-rules.json (chạy từ module :app)"
            }
            ReadingRules.initForTest(ReadingRules.parse(f.readText(Charsets.UTF_8)))
        }

        @AfterClass
        @JvmStatic
        fun teardownRules() {
            ReadingRules.resetForTest()
        }
    }

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
        // H2.1: readFigureRefs đã xóa — khóa hành vi qua pipeline đầy đủ (ruler fig/no).
        // G2.2: vỏ ngoặc ngoài thành phẩy (nghe trôi, không phát âm ngoặc).
        assertEquals("Xem, hình 5 nhé.", TextCleaner.cleanForTts("Xem (Fig. 5) nhé.", "vi"))
        assertEquals("Trích số 12 hay.", TextCleaner.cleanForTts("Trích No. 12 hay.", "vi"))
        assertEquals("See, figure 5.", TextCleaner.cleanForTts("See (Fig. 5).", "en"))
        assertEquals("Bảng number 3.", TextCleaner.cleanForTts("Bảng No. 3.", "en"))
        // Không chấm → giữ nguyên (không ăn "no 5" tiếng Anh).
        assertEquals("There is no 5 here.", TextCleaner.cleanForTts("There is no 5 here.", "en"))
    }

    @Test
    fun g22_abbrevReadAsWords() {
        // G2.2: e.g./i.e./vs./etc. đọc RA TỪ — nếu bỏ chấm kiểu cũ thì engine nghe "e g".
        assertEquals("nhiều loài, ví dụ mèo, chó nhé.", TextCleaner.cleanForTts("nhiều loài (e.g. mèo, chó) nhé.", "vi"))
        assertEquals("không ai, nghĩa là tôi đến.", TextCleaner.cleanForTts("không ai (i.e. tôi) đến.", "vi"))
        assertEquals("A so với B hay hơn.", TextCleaner.cleanForTts("A vs. B hay hơn.", "vi"))
        assertEquals("mèo, chó, vân vân đều được.", TextCleaner.cleanForTts("mèo, chó, etc. đều được.", "vi"))
        assertEquals("the winner, that is me wins.", TextCleaner.cleanForTts("the winner (i.e. me) wins.", "en"))
        assertEquals("cats, dogs, and so on live here.", TextCleaner.cleanForTts("cats, dogs, etc. live here.", "en"))
        // Không chấm / không phải viết tắt → giữ nguyên.
        assertEquals("eg giữ nguyên", TextCleaner.cleanForTts("eg giữ nguyên", "vi"))
        assertEquals("an egg stays", TextCleaner.cleanForTts("an egg stays", "en"))
    }

    @Test
    fun g22_outerParensBecomeCommas() {
        // G2.2: vỏ ngoặc ngoài → phẩy; ngoặc rỗng (); lồng nhau chỉ còn phẩy.
        assertEquals("Chọn loài, ví dụ mèo, chó nhé.", TextCleaner.cleanForTts("Chọn loài (e.g. mèo, chó) nhé.", "vi"))
        assertEquals("All, cats, dogs, and so on live.", TextCleaner.cleanForTts("All (cats, dogs, etc.) live.", "en"))
        assertEquals("Có đây.", TextCleaner.cleanForTts("Có () đây.", "vi"))
        assertEquals("Lồng a, b c ra.", TextCleaner.cleanForTts("Lồng (a (b) c) ra.", "vi"))
        // Display KHÔNG đổi (chỉ đường TTS mới rewrite).
        assertTrue(TextCleaner.cleanForDisplay("Xem (Fig. 5) nhé.").contains("(Fig. 5)"))
    }

    @Test
    fun citeAbbrev_guard() {
        assertTrue(TextCleaner.endsWithCiteAbbrev("xem minh họa (Fig."))
        assertTrue(TextCleaner.endsWithCiteAbbrev("Mục No."))
        assertFalse(TextCleaner.endsWithCiteAbbrev("câu này."))
        assertFalse(TextCleaner.endsWithCiteAbbrev("xong!"))
    }

    // ---- A: tách display-faithful vs TTS-smart ----

    @Test
    fun display_keepsOriginalPunctuation() {
        // Dấu —, số, TOC chấm: display giữ nguyên (TTS mới rewrite ở speak-time).
        val t = TextCleaner.cleanForDisplay("How the cut-off is managed—Limit of working.... 17")
        assertTrue(t.contains("—"))
        assertTrue(t.contains("17"))
        assertFalse(t.contains("PAUSE"))
        // Trái lại đường TTS vẫn đổi — → phẩy.
        assertFalse(TextCleaner.cleanForTts("a—b", "en").contains("—"))
    }

    @Test
    fun display_stripsTagsButKeepsWords() {
        val t = TextCleaner.cleanForDisplay("<p>Xin <b>chào</b> &amp; tạm biệt.</p>")
        assertEquals("Xin chào & tạm biệt.", t)
    }

    @Test
    fun originalChapters_keepTocAndFrontMatter() {
        val f = File.createTempFile("orig", ".md")
        try {
            f.writeText(
                "Tác giả NXB\nMục lục\n1 TRUYỆN MỞ ĐẦU .... 3\n\n## Chương 1\nNội dung — vẫn giữ.\n"
            )
            val smart = BlockTextReader.readChapters(f, "T", BlockTextReader.Heading.MARKDOWN_OR_STORY)
            val orig = BlockTextReader.readOriginalChapters(f, "T", BlockTextReader.Heading.MARKDOWN_OR_STORY)
            // Titles shaping tương đồng: smart ⊂ faithful theo thứ tự
            // (faithful giữ thêm chương phần đầu mà smart đã bỏ + filter trắng).
            val st = smart.map { it.title }
            val ot = orig.map { it.title }
            var j = 0
            for (t in st) {
                while (j < ot.size && ot[j] != t) j++
                assertTrue("mất title smart: $t", j < ot.size)
                j++
            }
            // Content nguyên bản: giữ TOC chấm + dấu —, giữ phần đầu.
            val body = orig.joinToString("\n") { it.content }
            assertTrue(body.contains("...."))
            assertTrue(body.contains("—"))
            assertTrue(body.contains("Tác giả NXB"))
            // Đường smart thì bỏ phần đầu + TOC.
            val smartBody = smart.joinToString("\n") { it.content }
            assertFalse(smartBody.contains("TRUYỆN MỞ ĐẦU"))
            assertFalse(smartBody.contains("...."))
        } finally {
            f.delete()
        }
    }

    @Test
    fun ttsSpeakable_stripsJunkLines() {
        // Dòng số trang / TOC chấm: TTS lướt.
        assertEquals("", BlockTextReader.ttsSpeakable("17"))
        assertEquals("", BlockTextReader.ttsSpeakable("1 THE GOLDEN BIRD ........ 17"))
        // Câu thường giữ nguyên để cleanForTts xử tiếp.
        assertEquals("Xin chào các bạn.", BlockTextReader.ttsSpeakable("Xin chào các bạn."))
        // Dòng rác lẫn trong câu nhiều dòng → chỉ bỏ dòng rác.
        val mixed = "Mở đầu.\n23\nKết thúc."
        val out = BlockTextReader.ttsSpeakable(mixed)
        assertTrue(out.contains("Mở đầu."))
        assertTrue(out.contains("Kết thúc."))
        assertFalse(out.lines().any { it.trim() == "23" })
    }

    @Test
    fun epubPagenoSpan_scopeB() {
        // Span số trang in Gutenberg RỖNG (số nằm ở attribute) → không text.
        val html = "<p>Text Tam<span class=\"x-ebookmaker-pageno\" title=\"[Pg 62]\"><a id=\"Page_62\"></a></span> continues.</p>"
        val t = EpubReader.xhtmlToText(html)
        assertTrue(t.contains("Text Tam"))
        assertTrue(t.contains("continues."))
        assertFalse(t.contains("Pg 62"))
        // Sách khác inline "[Pg 62]" dạng text: display GIỮ, TTS bỏ (rule ruler).
        assertTrue(TextCleaner.cleanForDisplay("a [Pg 62] b").contains("[Pg 62]"))
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

    @Test
    fun bookChunking_60words() {
        // B6.4: pipeline sách 60 từ/300 ký tự (liền mạch); default voice-chat giữ nguyên.
        val text = (1..30).joinToString(" ") { "từchuyện$it" } + ". " +
            (1..30).joinToString(" ") { "câukể$it" } + "."
        val long = VietnameseChunker.split(text, maxWords = 60, maxChars = 300)
        val short = VietnameseChunker.split(text)
        assertTrue(long.isNotEmpty() && short.isNotEmpty())
        assertTrue(long.size <= short.size)
        val wc = { s: String -> s.trim().split(Regex("\\s+")).size }
        assertTrue(long.all { wc(it) <= 70 })
        assertEquals(short.joinToString(" "), long.joinToString(" "))
    }
}
