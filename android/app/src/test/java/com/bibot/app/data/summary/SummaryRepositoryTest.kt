package com.bibot.app.data.summary

import org.junit.Assert.*
import org.junit.Test

/**
 * B5: key chia sẻ wiki (content-hash + model + promptVer) — pure JVM.
 * Quy ước khóa: cùng nội dung → cùng key (N người đọc free); model/ver khác → key khác.
 */
class SummaryRepositoryTest {

    @Test
    fun contentHash_stableAndNormalized() {
        val a = SummaryRepository.contentHash("Phụ lục VII tóm tắt.")
        val b = SummaryRepository.contentHash("Phụ  lục   VII\ntóm tắt.")
        assertEquals(a, b)
        assertEquals(64, a.length) // SHA-256 hex
        assertNotEquals(a, SummaryRepository.contentHash("Phụ lục VIII tóm tắt."))
    }

    @Test
    fun sanitizeModel_openRouterChars() {
        // OpenRouter "nex-agi/nex-n2.5-mini:free" có / và : (cấm trong docId).
        assertEquals("nex-agi-nex-n2-5-mini-free", SummaryRepository.sanitizeModel("nex-agi/nex-n2.5-mini:free"))
        assertEquals("gemini-3-5-flash-lite", SummaryRepository.sanitizeModel("gemini-3.5-flash-lite"))
        assertEquals("default", SummaryRepository.sanitizeModel("///"))
    }

    @Test
    fun docId_splitsModelAndVer() {
        val h = "abc123"
        val v1 = SummaryRepository.docId(h, "gemini-3.5-flash-lite", 1)
        val v2 = SummaryRepository.docId(h, "gemini-3.5-flash-lite", 2)
        val other = SummaryRepository.docId(h, "other-model", 1)
        // Prompt mới không đè bản cũ.
        assertNotEquals(v1, v2)
        assertNotEquals(v1, other)
        assertTrue(v1.startsWith("abc123_gemini-3-5-flash-lite_v1"))
        // DocId hợp lệ cho Firestore (không / :).
        assertFalse(v1.contains("/"))
        assertFalse(v1.contains(":"))
    }
}
