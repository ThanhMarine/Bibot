package com.bibot.app.core.tts

import com.bibot.app.core.text.ReadingRules
import org.junit.Assert.*
import org.junit.Test

/**
 * H2.3 chunker đọc guard từ ruler — behavior parity với hardcode cũ (D6).
 * Pure-JVM (split/feed thuần, không cần Context).
 * Chạy: :app:testDemoDebugUnitTest.
 */
class VietnameseChunkerTest {

    @Test
    fun split_basicSentences() {
        // minWords=4 nên câu ngắn bị gom — dùng câu đủ dài.
        val parts = VietnameseChunker.split("Xin chào các bạn của tôi hôm nay. Tạm biệt và hẹn gặp lại các bạn sau.")
        assertEquals(listOf("Xin chào các bạn của tôi hôm nay.", "Tạm biệt và hẹn gặp lại các bạn sau."), parts)
    }

    @Test
    fun citeAbbrev_notSplit() {
        // "(Fig." cuối chunk không phải hết câu → không chẻ ở đó.
        val parts = VietnameseChunker.split("Xem hình (Fig. 5) minh họa. Hết câu.")
        assertFalse(parts.any { it.trimEnd().endsWith("(Fig.") })
        assertTrue(parts.any { it.contains("Fig. 5") })
    }

    @Test
    fun bracketHold_andOverrun() {
        // Ngoặc chưa đóng + ngắn → gom, không ngắt giữa chừng.
        val c = VietnameseChunker(maxWords = 40, maxChars = 200)
        val held = c.feed("Bắt đầu (ghi chú còn")
        assertTrue(held.isEmpty())
        c.reset()
        // Quá overrun (maxWords + 8) mà gặp hết câu → ép yield, không kẹt vô hạn.
        val words = (1..25).joinToString(" ") { "w$it" }
        val forced = VietnameseChunker.split("Mở ngoặc $words. Hết.", maxWords = 10)
        assertTrue(forced.isNotEmpty())
        assertTrue(forced[0].contains("Mở ngoặc"))
    }

    @Test
    fun guard_fromRuleDrivesBrackets() {
        // Guard rỗng ngoặc → "(..." không còn giữ chunk (parity đảo được).
        // feed (chưa flush): default giữ lại vì ngoặc chưa đóng.
        assertTrue(VietnameseChunker().feed("mot hai (ba bon nam.").isEmpty())
        val noBracket = VietnameseChunker(
            guard = ReadingRules.ChunkGuard(
                bracketsOpen = "", bracketsClose = "",
                quotes = "\"“”", citeAbbrev = emptySet(),
                overrunWords = 8, overrunCharsMult = 2
            )
        )
        assertEquals(listOf("mot hai (ba bon nam."), noBracket.feed("mot hai (ba bon nam."))
    }

    @Test
    fun guardCiteAbbrev_fromRule() {
        val g = ReadingRules.ChunkGuard(
            bracketsOpen = "([{", bracketsClose = ")]}",
            quotes = "\"“”", citeAbbrev = setOf("fig", "no"),
            overrunWords = 8, overrunCharsMult = 2
        )
        val parts = VietnameseChunker(guard = g).let {
            val r = mutableListOf<String>()
            r += it.feed("Xem (Fig. 9) xong. Hết.")
            r += it.flush()
            r
        }
        assertFalse(parts.any { it.trimEnd().endsWith("(Fig.") })
    }
}
