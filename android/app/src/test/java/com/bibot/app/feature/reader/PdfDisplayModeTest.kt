package com.bibot.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2.4 — Test logic thuần display modes PDF: chuỗi Setting (cycle), tỉ lệ sau
 * xoay, kích thước fit-width/fit-page, kích thước nguồn bitmap khi xoay render.
 */
class PdfDisplayModeTest {

    // --- Chuỗi Setting (popup ⚙) ---
    @Test fun `fit mode xoay 2 nut`() {
        assertEquals(FIT_PAGE, nextFitMode(FIT_WIDTH))
        assertEquals(FIT_WIDTH, nextFitMode(FIT_PAGE))
        assertEquals(FIT_PAGE, nextFitMode("unknown")) // giá trị lạ → về mặc định an toàn
    }

    @Test fun `paging mode cycle continuous-single-dual`() {
        assertEquals(PAGING_SINGLE, nextPagingMode(PAGING_CONTINUOUS))
        assertEquals(PAGING_DUAL, nextPagingMode(PAGING_SINGLE))
        assertEquals(PAGING_CONTINUOUS, nextPagingMode(PAGING_DUAL))
        assertEquals(PAGING_CONTINUOUS, nextPagingMode("unknown")) // lạ → về mặc định an toàn
    }

    @Test fun `theme cycle normal-sepia-night`() {
        assertEquals(THEME_SEPIA, nextPageTheme(THEME_ORIGINAL))
        assertEquals(THEME_NIGHT, nextPageTheme(THEME_SEPIA))
        assertEquals(THEME_ORIGINAL, nextPageTheme(THEME_NIGHT))
        assertEquals(THEME_ORIGINAL, nextPageTheme("unknown")) // lạ → về mặc định an toàn
    }

    @Test fun `rotation cong 90 va wrap`() {
        assertEquals(90, nextRotation(0))
        assertEquals(180, nextRotation(90))
        assertEquals(270, nextRotation(180))
        assertEquals(0, nextRotation(270))
    }

    // --- Tỉ lệ sau xoay ---
    @Test fun `eff ratio chi dao khi xoay 90-270`() {
        val r = 612f / 792f // A4 dọc
        assertEquals(r, effPageRatio(r, 0), 1e-6f)
        assertEquals(r, effPageRatio(r, 180), 1e-6f)
        assertEquals(1f / r, effPageRatio(r, 90), 1e-6f)
        assertEquals(1f / r, effPageRatio(r, 270), 1e-6f)
    }

    @Test fun `eff ratio triet loi khi raw am hoac 0`() {
        assertTrue(effPageRatio(0f, 0) > 0f)
        assertEquals(1f / 0.707f, effPageRatio(-1f, 90), 1e-4f)
    }

    // --- Fit width/page ---
    @Test fun `fit width luon dung maxW`() {
        val (w, h) = fitPageSize(1000f, 1600f, 0.75f, FIT_WIDTH)
        assertEquals(1000f, w, 1e-6f)
        assertEquals(1000f / 0.75f, h, 1e-6f)
    }

    @Test fun `fit page dung maxH khi dung danh`() {
        // trang dọc 0.6: h=1500 → w=900 ≤ maxW 1000 → height-driven (đúng ý fit-page)
        val (w1, h1) = fitPageSize(1000f, 1500f, 0.6f, FIT_PAGE)
        assertEquals(1500f, h1, 1e-6f)
        // 0.6f không exact binary → chấp nhận sai số nhỏ
        assertEquals(900f, w1, 1e-2f)
        // trang ngang (ratio 1.5): h=1500 → w=2250 > 1000 → nghịch đảo về width
        val (w2, h2) = fitPageSize(1000f, 1500f, 1.5f, FIT_PAGE)
        assertEquals(1000f, w2, 1e-6f)
        assertEquals(1000f / 1.5f, h2, 1e-6f)
    }

    @Test fun `fit page khip maxW khi tran khung`() {
        val (w, h) = fitPageSize(800f, 1700f, 0.707f, FIT_PAGE)
        // h=1700 → w=1202 > 800 → width-driven: w=800, h≈1131 (≤1700)
        assertEquals(800f, w, 1e-6f)
        assertEquals(800f / 0.707f, h, 1e-6f)
        assertTrue(h <= 1700f)
    }

    // --- Kích thước bitmap theo xoay ---
    @Test fun `source size chi swap khi 90-270`() {
        assertEquals(800 to 1036, sourceSizeForRotation(800, 1036, 0))
        assertEquals(800 to 1036, sourceSizeForRotation(800, 1036, 180))
        assertEquals(1036 to 800, sourceSizeForRotation(800, 1036, 90))
        assertEquals(1036 to 800, sourceSizeForRotation(800, 1036, 270))
    }

    @Test fun `display size giu ti le va tran cao 2400`() {
        // A4 612x792 không xoay, đích 800px → 800 x 1036 (bỏ lẻ)
        val (dw, dh) = displaySizeFor(612, 792, 800, 0)
        assertEquals(800, dw)
        assertEquals((792f * 800f / 612f).toInt(), dh)
        // xoay 90 → tỉ lệ đảo: dh = 800 / (792/612) ≈ 618
        val (dw90, dh90) = displaySizeFor(612, 792, 800, 90)
        assertEquals(800, dw90)
        assertEquals((612f * 800f / 792f).toInt(), dh90)
        // trang cực dài 612x40000 → trần cao 2400, bề rộng ≥240 (hành vi E1.5)
        val (dwl, dhl) = displaySizeFor(612, 40000, 800, 0)
        assertEquals(2400, dhl)
        assertTrue(dwl >= 240)
        // page hỏng
        assertEquals(0 to 0, displaySizeFor(0, 100, 800, 0))
    }
}
