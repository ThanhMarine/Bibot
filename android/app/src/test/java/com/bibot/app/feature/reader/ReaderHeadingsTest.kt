package com.bibot.app.feature.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * G3.2 — Quyết định đọc tên chương thành chunk đầu kèm pause (pure JVM).
 */
class ReaderHeadingsTest {

    @Test
    fun multiChapter_normalTitle_announces() {
        assertTrue(shouldAnnounceChapterTitle("Chương 5: Trận Khe Sanh", 7, "Sáng hôm đó, gió lùa qua đồi."))
        assertTrue(shouldAnnounceChapterTitle("## Part One", 12, "The war began in spring."))
    }

    @Test
    fun singleChapter_skips() {
        // PDF/đoạn liền = 1 "chương" (tên hay là tên file) → không đọc.
        assertFalse(shouldAnnounceChapterTitle("grimm10", 1, "There once was a king."))
        assertFalse(shouldAnnounceChapterTitle("How it Works", 1, null))
    }

    @Test
    fun blankOrTooLong_skips() {
        assertFalse(shouldAnnounceChapterTitle("", 5, "Câu đầu."))
        assertFalse(shouldAnnounceChapterTitle("   ", 5, "Câu đầu."))
        assertFalse(shouldAnnounceChapterTitle("x".repeat(201), 5, "Câu đầu."))
    }

    @Test
    fun syntheticParagraphTitle_skips() {
        // Sách không có heading → BlockTextReader tách "Đoạn N" (nhãn chế tạo).
        assertFalse(shouldAnnounceChapterTitle("Đoạn 12", 500, "Nội dung đoạn."))
        assertFalse(shouldAnnounceChapterTitle("Đoạn 3", 4, "Nội dung đoạn."))
        // "Đoạn 12" là nhãn, không chặn tên chương thật cùng số.
        assertTrue(shouldAnnounceChapterTitle("Hồi 12", 4, "Nội dung hồi ấy."))
    }

    @Test
    fun firstSentenceIsTitle_skips_dupGuard() {
        // EPUB: h1 nằm cả trong title lẫn câu đầu → đọc cả 2 là nghe lặp.
        assertFalse(shouldAnnounceChapterTitle("Chapter V.", 7, "Chapter V."))
        assertFalse(shouldAnnounceChapterTitle("Chapter V.", 7, "  chapter v.  ...  "))
    }

    @Test
    fun firstSentenceStartsWithTitle_skips() {
        assertFalse(shouldAnnounceChapterTitle("Chương 1: Khởi đầu", 10, "Chương 1: Khởi đầu của mọi chuyện."))
    }

    @Test
    fun firstSentencePrefixOfTitle_skips() {
        // Câu đầu đúng bằng phần đầu tiêu đề ("Chương 1" / title "Chương 1: ...").
        assertFalse(shouldAnnounceChapterTitle("Chương 1: Cuộc chiến", 10, "Chương 1"))
    }

    @Test
    fun unrelatedFirstSentence_announces() {
        assertTrue(shouldAnnounceChapterTitle("Lời giới thiệu", 4, "Cuốn sách này được viết trong ba năm."))
        assertTrue(shouldAnnounceChapterTitle("Chương 1", 3, "Ngày xửa ngày xưa, có một ông lão."))
    }

    @Test
    fun normalize_stripsMarkdownAndPunct() {
        assertEquals("chương 5 trận khe sanh", normalizeTitleForCompare("## **Chương 5**: Trận Khe Sanh!"))
        assertEquals("part one", normalizeTitleForCompare("Part  One…"))
    }
}
