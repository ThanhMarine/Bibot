package com.bibot.app.data.library

import com.bibot.app.data.db.BookProgress
import org.junit.Assert.*
import org.junit.Test

/**
 * A2.4a: Vị trí đọc vạn năng — pure JVM, Shell_Manager dùng chung.
 */
class ReadingPositionTest {

    @Test
    fun fromLegacy_roundtrip() {
        assertEquals(ReadingPosition(0, 0), ReadingPosition.fromLegacy(0))
        assertEquals(ReadingPosition(4, 2), ReadingPosition.fromLegacy(402))
        assertEquals(402, ReadingPosition(4, 2).toLegacy())
    }

    @Test
    fun chapterOver100_notClamped() {
        // Bug cũ: chap*100+sent sai khi chương >100 câu. Model mới giữ nguyên số.
        val pos = ReadingPosition(chap = 5, sent = 150)
        assertEquals(5, pos.chap)
        assertEquals(150, pos.sent)
        assertEquals("Chương 6 Câu 151", pos.resumeLabel())
        assertTrue(pos.isStarted)
        assertFalse(ReadingPosition().isStarted)
    }

    @Test
    fun fromProgress_prefersNewColumns() {
        val p = BookProgress("b", "p", position = 402, total = 500, chapterIdx = 5, sentenceIdx = 150)
        val pos = ReadingPosition.fromProgress(p)
        assertEquals(5, pos.chap)
        assertEquals(150, pos.sent)
    }

    @Test
    fun fromProgress_fallbackAnchorThenLegacy() {
        // Neo F2 (TEXT anchorPage=chap, anchorOffset=sent).
        val anchored = BookProgress("b", "p", position = 0, total = 0, chapterIdx = -1, anchorPage = 3, anchorOffset = 7, readMode = "read")
        assertEquals(ReadingPosition(3, 7, 3, 7), ReadingPosition.fromProgress(anchored))
        // Legacy khi chưa có gì mới.
        val legacy = BookProgress("b", "p", position = 402, total = 500, chapterIdx = -1)
        assertEquals(4, ReadingPosition.fromProgress(legacy).chap)
        assertEquals(2, ReadingPosition.fromProgress(legacy).sent)
    }

    @Test
    fun resumeLabel_countsFromOne() {
        assertEquals("Chương 1 Câu 1", ReadingPosition(0, 0).resumeLabel())
        assertEquals("Chương 5 Câu 3", ReadingPosition(4, 2).resumeLabel())
    }
}
