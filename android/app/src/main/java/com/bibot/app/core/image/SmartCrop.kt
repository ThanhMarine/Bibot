package com.bibot.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.File
import java.io.FileInputStream

/**
 * A3.4 SmartCrop — port from orecrop/smartcrop.cpp (IsRectWhite 1%) +
 * E2.3 crop lề trang PDF PAGE.
 *
 * Mục đích: cắt viền trắng xung quanh trang sách/comic (page margin crop).
 *
 * Thuật toán gốc (smartcrop.cpp): một rect coi là TRẮNG khi số pixel MỰC
 * (không phải trắng) < floor(diện tích × 1%) — gần như toàn trang giấy mới
 * tính là trắng, nên quét lề dừng NGAY ở cột/dòng đầu có chữ.
 *
 * **Fix semantics (E2.3):** bản A3.4 đếm pixel TRẮNG ≥ 1% → điều kiện gần
 * như luôn đúng với mọi trang sách (trang nào chẳng có ≥1% pixel trắng) →
 * quét lề đi hết trang, KHÔNG BAO GIỜ cắt tới chữ. Đã đảo về đúng gốc:
 * đếm pixel MỰC < 1%.
 *
 * Dùng cho:
 * - PDF PAGE (E2.3 crop margin — render-time trong PdfPageView)
 * - PDF scan / comic / EPUB cover (A3.4 processFile)
 *
 * Lõi duyệt chạy trên `IntArray` thuần JVM ([autoCropPixels] / [isRectWhite])
 * → unit test được không cần Robolectric; wrapper Bitmap chỉ getPixels + Rect.
 */

// Configurable constants (A3.4)
var WHITE_THRESHOLD = 240
    @Suppress("UNUSED_PARAMETER") set(value) { field = value.coerceIn(200, 255) }

var WHITE_PERCENTAGE = 0.01f
    @Suppress("UNUSED_PARAMETER") set(value) { field = value.coerceIn(0.001f, 0.1f) }

/**
 * Khung nội dung (chỉ số pixel, right/bottom exclusive) — thuần JVM.
 * Right/bottom kiểu exclusive như `Rect` Android để tạo bitmap cắt trực tiếp.
 */
data class CropBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object SmartCrop {

    // ------------------------------------------------------------- lõi pixel (JVM test)

    /**
     * Pixel coi là GIẤY (không phải mực): trong suốt (PdfRenderer có thể cho
     * nền alpha=0 — nếu coi là mực thì lề tối đen, không bao giờ cắt) hoặc
     * RGB >= [WHITE_THRESHOLD].
     */
    fun isWhitePixel(color: Int): Boolean {
        if ((color ushr 24) < 8) return true
        val t = WHITE_THRESHOLD
        return ((color shr 16) and 0xFF) >= t &&
            ((color shr 8) and 0xFF) >= t &&
            (color and 0xFF) >= t
    }

    /**
     * IsRectWhite (smartcrop.cpp): rect TRẮNG khi số pixel mực < floor(area × 1%).
     * [pixels] row-major, [stride] = bề rộng của mảng (có thể lớn hơn sx+sw).
     * Rect nhỏ (area < 100) → limit = 0 → luôn false — khớp bản gốc.
     */
    fun isRectWhite(pixels: IntArray, stride: Int, sx: Int, sy: Int, sw: Int, sh: Int): Boolean {
        if (sw <= 0 || sh <= 0) return false
        if (sx < 0 || sy < 0 || sx + sw > stride) return false
        val limit = (sw * sh * WHITE_PERCENTAGE).toInt().coerceAtLeast(0)
        var dark = 0
        for (y in sy until sy + sh) {
            val base = y * stride + sx
            for (x in 0 until sw) {
                if (!isWhitePixel(pixels[base + x])) {
                    dark++
                    if (dark >= limit) return false
                }
            }
        }
        return dark < limit
    }

    /**
     * Tính khung nội dung trên mảng pixel (E2.3 + A3.4): quét từ 4 mép tìm
     * cột/dòng đầu tiên KHÔNG trắng; bỏ qua dải ~5% mép (v_line_margin/
     * h_line_margin gốc) khi xét hàng/cột để đường kẻ/header/footer kẻ ngang
     * không làm lề dừng sớm.
     *
     * @return [CropBox] nội dung, hoặc null khi không nên cắt (toàn trắng,
     * mép đã chạm chữ, vùng chữ <10% trang — sanity A3.4).
     */
    fun autoCropPixels(pixels: IntArray, w: Int, h: Int): CropBox? {
        if (w < 4 || h < 4 || pixels.size < w * h) return null
        val mx = (w / 20).coerceAtLeast(1)   // ~5% mép ngang bỏ qua khi quét hàng
        val my = (h / 20).coerceAtLeast(1)   // ~5% mép dọc bỏ qua khi quét cột
        val innerW = w - 2 * mx
        val innerH = h - 2 * my
        if (innerW <= 0 || innerH <= 0) return null

        var left = -1
        for (x in 0 until w) {
            if (!isRectWhite(pixels, w, x, my, 1, innerH)) { left = x; break }
        }
        var right = -1
        for (x in w - 1 downTo 0) {
            if (!isRectWhite(pixels, w, x, my, 1, innerH)) { right = x + 1; break }
        }
        var top = -1
        for (y in 0 until h) {
            if (!isRectWhite(pixels, w, mx, y, innerW, 1)) { top = y; break }
        }
        var bottom = -1
        for (y in h - 1 downTo 0) {
            if (!isRectWhite(pixels, w, mx, y, innerW, 1)) { bottom = y + 1; break }
        }
        // Toàn trắng (một mép không tìm thấy mực) → không có gì để cắt.
        if (left < 0 || right < 0 || top < 0 || bottom < 0) return null
        if (left >= right || top >= bottom) return null
        // Sanity A3.4: vùng cắt phải có ý nghĩa (ít nhất 10% kích thước gốc).
        if (right - left < w / 10 || bottom - top < h / 10) return null
        // Chữ chạm mọi mép → không có lề trắng → không cắt.
        if (left == 0 && top == 0 && right == w && bottom == h) return null
        return CropBox(left, top, right, bottom)
    }

    // ------------------------------------------------------------- wrapper Bitmap (A3.4/E2.3)

    /**
     * Tính crop rect tự động cho 1 bitmap (trả về Rect nội dung, loại viền trắng).
     * Đọc toàn bộ pixel (một lần getPixels, mảng tạm ~w×h×4B) — OOM → null (giữ trang gốc).
     * @return Rect crop (left, top, right, bottom) hoặc null nếu không crop được
     */
    fun autoCrop(bmp: Bitmap): Rect? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val pixels = try {
            IntArray(w * h)
        } catch (_: OutOfMemoryError) {
            return null
        }
        return try {
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val box = autoCropPixels(pixels, w, h) ?: return null
            Rect(box.left, box.top, box.right, box.bottom)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Crop bitmap theo rect (trả về bitmap mới, bitmap gốc không đổi).
     * @param bmp bitmap nguồn
     * @param cropRect rect cắt (left, top, right, bottom)
     * @return bitmap đã cắt, hoặc bitmap gốc nếu cropRect null
     */
    fun applyCrop(bmp: Bitmap, cropRect: Rect?): Bitmap {
        return cropRect?.let {
            val w = it.width()
            val h = it.height()
            if (w > 0 && h > 0) Bitmap.createBitmap(bmp, it.left, it.top, w, h) else bmp
        } ?: bmp
    }

    /**
     * Xử lý file ảnh: đọc bitmap (có sample để tiết kiệm RAM), auto-crop, ghi đè hoặc lưu mới.
     * @param file file ảnh (jpg/png)
     * @param outputFile file output (null = ghi đè)
     * @param maxDim max dimension khi decode (để tiết kiệm RAM), mặc định 2048
     * @return true nếu đã crop thành công
     */
    fun processFile(file: File, outputFile: File? = null, maxDim: Int = 2048): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(FileInputStream(file), null, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return false

        // Tính sample size để không vượt quá maxDim
        var sampleSize = 1
        while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }

        opts.inJustDecodeBounds = false
        opts.inSampleSize = sampleSize
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888

        val bmp = BitmapFactory.decodeStream(FileInputStream(file), null, opts) ?: return false
        autoCrop(bmp)?.let { cropRect ->
            val cropped = applyCrop(bmp, cropRect)
            val out = outputFile ?: file
            try {
                out.outputStream().use { os -> cropped.compress(Bitmap.CompressFormat.PNG, 100, os) }
            } catch (_: Exception) { return false }
            bmp.recycle()
            if (cropped !== bmp) cropped.recycle()
            return true
        }
        bmp.recycle()
        return false
    }
}
