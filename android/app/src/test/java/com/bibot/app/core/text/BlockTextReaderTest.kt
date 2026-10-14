package com.bibot.app.core.text

import org.junit.Assert.*
import org.junit.Test

/**
 * H2.2: hàm cấu trúc giữ lại trong code (không vào ruler) phải có test khóa hành vi.
 * isSkippableEpubTitle: điều kiện kép blank+title gắn spine, rulepack không biểu diễn được.
 */
class BlockTextReaderTest {

    @Test
    fun skippableTitles_coverTocCopyright() {
        assertTrue(BlockTextReader.isSkippableEpubTitle("Cover"))
        assertTrue(BlockTextReader.isSkippableEpubTitle("  bìa  "))
        assertTrue(BlockTextReader.isSkippableEpubTitle("Mục lục"))
        assertTrue(BlockTextReader.isSkippableEpubTitle("Table of Contents"))
        assertTrue(BlockTextReader.isSkippableEpubTitle("TOC"))
        assertTrue(BlockTextReader.isSkippableEpubTitle("Copyright"))
        assertTrue(BlockTextReader.isSkippableEpubTitle("Bản quyền"))
    }

    @Test
    fun skippableTitles_realChaptersKept() {
        assertFalse(BlockTextReader.isSkippableEpubTitle("Chương 1"))
        assertFalse(BlockTextReader.isSkippableEpubTitle("Lời giới thiệu"))
        assertFalse(BlockTextReader.isSkippableEpubTitle("How it Works"))
        assertFalse(BlockTextReader.isSkippableEpubTitle(""))
        // "cover" giữa câu không phải tiêu đề bìa (regex neo ^) — nhưng containsMatchIn
        // vẫn khớp vì ^ neo đầu chuỗi sau trim: "Recover" mở đầu "Reco..." không khớp "cover".
        assertFalse(BlockTextReader.isSkippableEpubTitle("Recover"))
    }
}
