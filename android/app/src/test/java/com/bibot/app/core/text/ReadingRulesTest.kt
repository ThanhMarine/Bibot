package com.bibot.app.core.text

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * H2 ruler v2 — rulepack `.ruler` → `.json` là truth cho nhận diện/xử lý đọc sách.
 * Test đọc JSON THẬT từ src/main/assets (không copy) để chống drift ruler/code.
 * Chạy: :app:testDemoDebugUnitTest. Pure-JVM (parse/apply, không cần Context).
 */
class ReadingRulesTest {

    private fun loadPack(): List<ReadingRules.Rule> {
        val candidates = listOf(
            File("src/main/assets/reading-rules.json"),
            File("app/src/main/assets/reading-rules.json")
        )
        val f = requireNotNull(candidates.firstOrNull { it.exists() }) {
            "thiếu assets reading-rules.json (chạy từ module :app)"
        }
        return ReadingRules.parse(f.readText(Charsets.UTF_8))
    }

    @Test
    fun pack_v2_shape() {
        val rules = loadPack()
        // v4 (G2.2): 17 rules v3 + 12 rule viết-tắt/vỏ-ngoặc — đổi số này khi thêm rule.
        assertEquals(29, rules.size)
        assertTrue(rules.any { it.id == "date.full" })
        assertTrue(rules.any { it.id == "roman.range" })
        assertTrue(rules.any { it.id == "drop.toc_leader" })
        assertTrue(rules.any { it.id == "start.intro" })
        assertTrue(rules.any { it.id == "start.first_chapter" })
        val guard = rules.firstOrNull { it.kind == "split_guard" }
        assertNotNull(guard)
        assertEquals("([{", guard!!.params["brackets_open"])
        // priority đã sort tăng dần (pipeline: drop/start → ngày → độ → La Mã → trích dẫn).
        val prios = rules.map { it.priority }
        assertEquals(prios.sorted(), prios)
    }

    @Test
    fun rewrite_parityWithHardcode() {
        val rules = loadPack()
        // Ngày kèm năm (code: dateFullRegex).
        assertEquals(
            "ngày ba mươi mốt tháng mười hai năm hai nghìn hai mươi sáu",
            ReadingRules.apply("31-12-2026", "vi", rules)
        )
        // Ngày ngắn (code: dateShortRegex).
        assertEquals(
            "Gặp nhau ngày ba mươi mốt tháng mười hai nhé",
            ReadingRules.apply("Gặp nhau ngày 31/12 nhé", "vi", rules)
        )
        // Nhiệt độ (code: normalizeDegrees).
        assertEquals("25 độ C hôm nay", ReadingRules.apply("25°C hôm nay", "vi", rules))
        assertEquals("30 độ C hôm nay", ReadingRules.apply("30độ C hôm nay", "vi", rules))
        // La Mã khoảng + đơn (code: romanRange/romanSingle, quirk "mười mốt" parity).
        assertEquals(
            "thế kỷ mười mốt đến thế kỷ mười chín rực rỡ",
            ReadingRules.apply("thế kỷ XI-XIX rực rỡ", "vi", rules)
        )
        assertEquals("Chương mười mở đầu", ReadingRules.apply("Chương X mở đầu", "vi", rules))
        // Trích dẫn theo lang (code: readFigureRefs) — G2.2: vỏ ngoặc ngoài → phẩy.
        assertEquals("Xem, hình 5 nhé", ReadingRules.apply("Xem (Fig. 5) nhé", "vi", rules))
        assertEquals("See, figure 5.", ReadingRules.apply("See (Fig. 5).", "en", rules))
        assertEquals("There is no 5 here.", ReadingRules.apply("There is no 5 here.", "en", rules))
        // G2.2: viết tắt đọc ra từ (vi/en) — gói đầy đủ nên ngoặc ngoài cũng đã thành phẩy.
        assertEquals("nhiều loài, ví dụ mèo, chó", ReadingRules.apply("nhiều loài (e.g. mèo, chó)", "vi", rules))
        assertEquals("mèo, chó, vân vân đều được", ReadingRules.apply("mèo, chó, etc. đều được", "vi", rules))
        assertEquals("A so với B hay hơn", ReadingRules.apply("A vs. B hay hơn", "vi", rules))
        assertEquals("the winner, that is me wins", ReadingRules.apply("the winner (i.e. me) wins", "en", rules))
        assertEquals("cats, dogs, and so on live here.", ReadingRules.apply("cats, dogs, etc. live here.", "en", rules))
        // Negatives giữ nguyên.
        assertEquals("1.440 đồng", ReadingRules.apply("1.440 đồng", "vi", rules))
        assertEquals("Louis XIV đến chơi", ReadingRules.apply("Louis XIV đến chơi", "vi", rules))
    }

    @Test
    fun dropAndMarker_parityWithHardcode() {
        val rules = loadPack()
        assertTrue(ReadingRules.isDropLine("12", "vi", rules))
        assertTrue(ReadingRules.isDropLine("Trang 12", "vi", rules))
        assertTrue(ReadingRules.isDropLine("Chương 1 .... 5", "vi", rules))
        assertFalse(ReadingRules.isDropLine("năm 2026 rồi", "vi", rules))
        assertFalse(ReadingRules.isDropLine("Nội dung câu chuyện bình thường", "vi", rules))
        assertTrue(ReadingRules.isStartMarker("Lời giới thiệu", "vi", rules))
        assertTrue(ReadingRules.isStartMarker("## Phần I", "vi", rules))
        assertTrue(ReadingRules.isStartMarker("Chương 1", "vi", rules))
        assertFalse(ReadingRules.isStartMarker("Chương 2", "vi", rules))
        assertFalse(ReadingRules.isStartMarker("Nội dung chương một", "vi", rules))
    }

    @Test
    fun apply_idempotent() {
        // Chạy 2 lần = 1 lần (an toàn khi cleanForTts gọi ruler rồi hàm cứng chạy tiếp).
        val rules = loadPack()
        val samples = listOf(
            "Gặp nhau 31/12/2026 nhé" to "vi",
            "25°C hôm nay" to "vi",
            "thế kỷ XI-XIX rực rỡ" to "vi",
            "Xem (Fig. 5) nhé" to "vi",
            "Nội dung bình thường không gì đặc biệt." to "vi"
        )
        for ((s, lang) in samples) {
            val once = ReadingRules.apply(s, lang, rules)
            assertEquals("không idempotent: $s", once, ReadingRules.apply(once, lang, rules))
        }
    }

    @Test
    fun chunkGuard_matchesHardcode() {
        val rules = loadPack()
        // Params split_guard phải khớp hành vi cứng VietnameseChunker/TextCleaner (D6).
        val p = rules.first { it.kind == "split_guard" }.params
        assertEquals("([{", p["brackets_open"])
        assertEquals(")]}", p["brackets_close"])
        assertTrue(p["quotes"]!!.contains("\""))
        val abbrev = p["cite_abbrev"]!!.split(Regex("\\s+")).toSet()
        // Mọi từ trong TextCleaner.citeAbbrevWords đều phải có trong ruler.
        for (w in listOf("fig", "no", "st", "mr", "mrs", "ms", "dr", "prof", "vs",
            "etc", "ie", "eg", "am", "pm", "tr", "tp", "nxb",
            "p", "pp", "ch", "sec", "vol", "ed")) {
            assertTrue("thiếu abbrev $w", abbrev.contains(w))
        }
        assertEquals("8", p["overrun_words"])
        assertEquals("2", p["overrun_chars_mult"])
        // Chưa init (unit test không Context) → guard default = hành vi cứng.
        val g = ReadingRules.chunkGuard()
        assertEquals("([{", g.bracketsOpen)
        assertEquals(8, g.overrunWords)
    }

    @Test
    fun pagenoInline_scopeB() {
        val rules = loadPack()
        // Scope B: display giữ "[Pg 62]", TTS (apply) bỏ qua.
        assertEquals("Text Tam continues.", ReadingRules.apply("Text Tam[Pg 62] continues.", "en", rules))
        assertEquals("Xem  nhé", ReadingRules.apply("Xem [Pg. 7] nhé", "vi", rules))
        assertEquals("Trang 12 rồi", ReadingRules.apply("Trang 12 rồi", "vi", rules))
    }
}
