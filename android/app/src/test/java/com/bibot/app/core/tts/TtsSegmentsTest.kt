package com.bibot.app.core.tts

import org.junit.Assert.*
import org.junit.Test

/**
 * A1 — verify TtsSegments khớp hợp đồng openreader + parity Go
 * (internal/chunker/segments_test.go). Chạy: :app:testDemoDebugUnitTest.
 */
class TtsSegmentsTest {

    @Test
    fun stableHash_asciiVectors() {
        // Vector FNV-1a 32-bit chuẩn (ASCII: UTF-16 units == bytes).
        assertEquals("811c9dc5", TtsSegments.stableHash(""))
        assertEquals("e40c292c", TtsSegments.stableHash("a"))
        assertEquals("4f9f2cab", TtsSegments.stableHash("hello"))
    }

    @Test
    fun stableHash_surrogateMatchesGo() {
        // U+1D11E = D834 DD1E — khớp vector Go đã in ra (dae009c7).
        assertEquals("dae009c7", TtsSegments.stableHash("\uD834\uDD1E"))
        assertEquals(8, TtsSegments.stableHash("\uD834\uDD1E").length)
        assertNotEquals(TtsSegments.stableHash("\uD834\uDD1E"), TtsSegments.stableHash("a"))
    }

    @Test
    fun normalize() {
        assertEquals("héllo world", TtsSegments.normalizeIdentityText("  Héllo   WORLD\n"))
        assertEquals("Hello World", TtsSegments.normalizeContentText("  Hello   World\n"))
    }

    @Test
    fun segmentKey_prefixAndCaseInsensitive() {
        val k1 = TtsSegments.segmentKey("doc1:md:v1", "Xin chào.")
        val k2 = TtsSegments.segmentKey("doc1:md:v1", "  XIN CHÀO. ")
        assertTrue(k1.startsWith("doc1:md:v1:"))
        assertEquals(k1, k2)
        assertEquals(8, k1.substringAfterLast(":").length)
    }

    @Test
    fun planSegments_ordinalAnchorOwner() {
        val units = listOf(
            TtsSourceUnit("chap1", "Xin chào các bạn. Hôm nay trời đẹp quá!", 0, "md"),
            TtsSourceUnit("chap2", "Ngày mùng năm tháng ba.", 1, "md")
        )
        val segs = TtsSegments.planSegments(units, "doc1", "md") { VietnameseChunker.split(it) }
        assertTrue(segs.isNotEmpty())
        segs.forEachIndexed { i, s ->
            assertEquals(i, s.ordinal)
            assertTrue(s.key.startsWith("doc1:md:v1:"))
            assertTrue(s.text.isNotBlank())
            assertTrue(s.startAnchor.offset >= 0)
            assertTrue(s.endAnchor.offset >= s.startAnchor.offset)
            assertTrue(s.locator.charOffset >= 0)
        }
        assertEquals("chap1", segs.first().ownerSourceKey)
        // Cùng text ở unit khác → cùng key (dedup), ordinal khác.
        val dup = TtsSegments.planSegments(
            listOf(
                TtsSourceUnit("a", "Xin chào.", 0),
                TtsSourceUnit("b", "Xin chào.", 5)
            ),
            "doc1", "md"
        ) { VietnameseChunker.split(it) }
        assertEquals(2, dup.size)
        assertEquals(dup[0].key, dup[1].key)
        assertNotEquals(dup[0].ordinal, dup[1].ordinal)
    }

    @Test
    fun voiceResolve_matrix() {
        // Piper sẵn → PIPER, không degraded.
        var r = TtsVoiceResolve.resolvePure("vi", 0.88f, 0.85f, true, "auto", true, piperReady = true)
        assertEquals(TtsEngine.PIPER, r.engine)
        assertEquals("piper:vi", r.voiceId)
        assertEquals(0.88f, r.speed)
        assertEquals("wav", r.format)
        assertFalse(r.degraded)
        // Muốn Piper nhưng thiếu model, có key → CLOUD + degraded (báo rõ).
        r = TtsVoiceResolve.resolvePure("vi", 0.88f, 0.85f, true, "auto", true, piperReady = false)
        assertEquals(TtsEngine.CLOUD, r.engine)
        assertEquals("vi-VN-Neural2-A", r.voiceId)
        assertTrue(r.degraded)
        // Sách EN → voice/speed EN.
        r = TtsVoiceResolve.resolvePure("en", 0.88f, 0.85f, true, "auto", false, piperReady = false)
        assertEquals(TtsEngine.CLOUD, r.engine)
        assertEquals("en-US-Neural2-A", r.voiceId)
        assertEquals(0.85f, r.speed)
        assertFalse(r.degraded) // không prefer piper → đúng ý, không báo
        // Không key, không piper → OFFLINE + degraded (prefer piper).
        r = TtsVoiceResolve.resolvePure("vi", 0.88f, 0.85f, false, "auto", true, piperReady = false)
        assertEquals(TtsEngine.OFFLINE, r.engine)
        assertEquals("offline:vi", r.voiceId)
        assertTrue(r.degraded)
        // Priority offline + không prefer → OFFLINE đúng ý, không degraded.
        r = TtsVoiceResolve.resolvePure("vi", 0.88f, 0.85f, false, "offline", false, piperReady = false)
        assertEquals(TtsEngine.OFFLINE, r.engine)
        assertFalse(r.degraded)
        // VIP mở Cloud kể cả priority=offline (giữ hành vi cũ).
        r = TtsVoiceResolve.resolvePure("vi", 0.88f, 0.85f, true, "offline", false, piperReady = false, vipBypass = true)
        assertEquals(TtsEngine.CLOUD, r.engine)
        // Rewarded mở Cloud theo chương.
        r = TtsVoiceResolve.resolvePure("vi", 0.88f, 0.85f, true, "offline", false, piperReady = false, rewardedCloud = true)
        assertEquals(TtsEngine.CLOUD, r.engine)
        // Bất biến 0b4d559b: voiceId không bao giờ rỗng.
        for (e in listOf(
            TtsVoiceResolve.resolvePure("vi", 0.88f, 0.85f, false, "auto", false, false),
            TtsVoiceResolve.resolvePure("en", 0.88f, 0.85f, false, "offline", true, false)
        )) assertTrue(e.voiceId.isNotBlank())
    }

    @Test
    fun settingsHash_splitsByVoiceSpeed() {
        val seg = TtsSegmentation(maxBlockLength = 200, language = "vi")
        val base = TtsSettings("cloud:vi-VN-Neural2-A", "0.88", "mp3", "vi")
        val h1 = TtsSegments.settingsHash(base, seg)
        assertEquals(64, h1.length)
        assertEquals(h1, TtsSegments.settingsHash(base, seg))
        assertNotEquals(h1, TtsSegments.settingsHash(base.copy(voice = "cloud:en-US-Neural2-A"), seg))
        assertNotEquals(h1, TtsSegments.settingsHash(base.copy(speed = "1.0"), seg))
    }

    @Test
    fun audioContentHash_dedupAndSensitivity() {
        val a = TtsSegments.audioContentHash("doc1", 123, "sethash", "doc1:md:v1:abcd1234", "xin chào", "fp1")
        assertEquals(64, a.length)
        assertEquals(a, TtsSegments.audioContentHash("doc1", 123, "sethash", "doc1:md:v1:abcd1234", "xin chào", "fp1"))
        // Cùng key, locator khác → CÙNG hash (dedup chủ đích).
        assertEquals(a, TtsSegments.audioContentHash("doc1", 123, "sethash", "doc1:md:v1:abcd1234", "xin chào", "fp2"))
        // Text/key/version khác → khác hash.
        assertNotEquals(a, TtsSegments.audioContentHash("doc1", 123, "sethash", "doc1:md:v1:ffff0000", "tạm biệt", "fp1"))
        assertNotEquals(a, TtsSegments.audioContentHash("doc1", 999, "sethash", "doc1:md:v1:abcd1234", "xin chào", "fp1"))
    }

    @Test
    fun locatorFingerprint_andKey() {
        val fp1 = TtsSegments.locatorFingerprint(TtsSegmentLocator("md", 3, 10))
        val fp2 = TtsSegments.locatorFingerprint(TtsSegmentLocator("md", 4, 10))
        assertTrue(fp1.isNotEmpty())
        assertNotEquals(fp1, fp2)
        assertEquals("", TtsSegments.locatorFingerprint(TtsSegmentLocator()))
        assertEquals("sethash/audiohash", TtsSegments.audioKey("sethash", "audiohash"))
        assertEquals("pdf", TtsSegments.readerTypeFor("Sach.PDF"))
        assertEquals("epub", TtsSegments.readerTypeFor("sach.epub"))
        assertEquals("md", TtsSegments.readerTypeFor("truyen.md"))
    }
}
