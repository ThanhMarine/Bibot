package com.bibot.app.feature.reader

/**
 * E2.4 — Logic THUẦN cho display modes PDF (tách khỏi Compose để test JVM):
 * fit-width/fit-page, lật trang continuous/single/dual, xoay trang 0/90/180/270,
 * theme màu (màu gốc/sepia/night). PdfPageView chỉ render theo kết quả này;
 * popup ⚙ dùng các hàm next* để chuỗi Setting.
 */

// Giá trị lưu trong BibotPreferences (readerFitMode/readerPagingMode/...).
const val FIT_WIDTH = "width" // fit-width: rộng = khung, cao theo tỉ lệ (mặc định)
const val FIT_PAGE = "height" // fit-page: cao = khung, rộng theo tỉ lệ (trang ngang → kẹp lại)
const val PAGING_CONTINUOUS = "continuous" // cuộn liên tục (mặc định)
const val PAGING_SINGLE = "single" // 1 trang/lần (snap fling)
const val PAGING_DUAL = "dual" // 2 trang/cặp (Row 2 cột)
const val THEME_ORIGINAL = "normal" // màu gốc (mặc định)
const val THEME_SEPIA = "sepia" // nâu cổ
const val THEME_NIGHT = "night" // đảo màu + nền tối
const val THEME_WHITE = "white" // nền trắng + chữ đen (mới)

fun nextFitMode(cur: String): String = if (cur == FIT_PAGE) FIT_WIDTH else FIT_PAGE

fun nextPagingMode(cur: String): String = when (cur) {
    PAGING_CONTINUOUS -> PAGING_SINGLE
    PAGING_SINGLE -> PAGING_DUAL
    else -> PAGING_CONTINUOUS
}

fun nextPageTheme(cur: String): String = when (cur) {
    THEME_ORIGINAL -> THEME_SEPIA
    THEME_SEPIA -> THEME_NIGHT
    THEME_NIGHT -> THEME_WHITE
    else -> THEME_ORIGINAL
}

fun nextRotation(deg: Int): Int = ((deg % 360) + 90) % 360

/** Tỉ lệ khung hình SAU xoay (hiển thị w/h): 90/270 đảo tỉ lệ, 0/180 giữ. */
fun effPageRatio(raw: Float, rotation: Int): Float {
    val r = if (raw <= 0f) 0.707f else raw
    val rot = ((rotation % 360) + 360) % 360
    return if (rot % 180 == 90) 1f / r else r
}

/**
 * Kích thước trang trong khung (cùng đơn vị inout, dp/px đều được) theo mode fit:
 * - fit-width: rộng = [maxW], cao = maxW / tỉ lệ.
 * - fit-page: cao = [maxH] trước; nếu quá rộng (trang ngang vượt [maxW]) →
 *   nghịch đảo về width-driven (vẫn "vừa khung" cả 2 chiều).
 * CHUNG cho bitmap VÀ placeholder (E2.3: lệch 1 bên là scrollToItem rơi index).
 */
fun fitPageSize(maxW: Float, maxH: Float, ratio: Float, fitMode: String): Pair<Float, Float> {
    val r = if (ratio <= 0f) 0.707f else ratio
    if (fitMode == FIT_PAGE && maxH > 0f) {
        val h = maxH
        val w = h * r
        if (w <= maxW) return w to h
    }
    val w = if (maxW > 0f) maxW else maxH * r
    return w to (w / r)
}

/**
 * Kích thước bitmap NGUỒN (trước xoay) cho trang hiển thị (dw,dh) đã xoay [rotation]:
 * 90/270 swap (nguồn w = hiển thị h). Dùng trong renderPdfBitmap.
 */
fun sourceSizeForRotation(dispW: Int, dispH: Int, rotation: Int): Pair<Int, Int> {
    val rot = ((rotation % 360) + 360) % 360
    return if (rot == 90 || rot == 270) dispH to dispW else dispW to dispH
}

/**
 * Chọn kích thước HIỂNG THỊ (đã xoay) từ page w/h + bề rộng đích — giữ nguyên
 * hành vi E1.5: tỉ lệ thật, trần cao 2400px (chống OOM trang quá dài), bề rộng
 * ≥240px. Trả (dw, dh); (0,0) khi page hỏng/tỉ lệ ≤0.
 */
fun displaySizeFor(pageW: Int, pageH: Int, targetW: Int, rotation: Int): Pair<Int, Int> {
    if (pageW <= 0 || pageH <= 0) return 0 to 0
    val rot = ((rotation % 360) + 360) % 360
    val swap = rot == 90 || rot == 270
    val dispRatio = if (swap) pageH.toFloat() / pageW else pageW.toFloat() / pageH
    if (dispRatio <= 0f) return 0 to 0
    var dw = targetW.coerceIn(240, 1600)
    var dh = (dw / dispRatio).toInt()
    if (dh > 2400) {
        val s = 2400f / dh
        dw = (dw * s).toInt().coerceAtLeast(240)
        dh = 2400
    }
    if (dw <= 0 || dh <= 0) return 0 to 0
    return dw to dh
}
