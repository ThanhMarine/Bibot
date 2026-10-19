package com.bibot.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Pair
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

/**
 * A3.4 SmartCrop — port from orecrop/smartcrop.cpp (IsRectWhite 1%)
 *
 * Mục đích: cắt viền trắng xung quanh trang sách/comic (page margin crop).
 * Thuật toán: IsRectWhite(percentage=1%) — nếu phần lớn pixel trong rect là trắng
 * (R,G,B >= threshold) thì coi là vùng margin cần cắt.
 *
 * Dùng cho:
 * - PDF scan (gutenberg, sách giấy quét)
 * - Comic/CBZ (cắt viền trang)
 * - EPUB cover/cropped images
 */

// Configurable constants (A3.4)
var WHITE_THRESHOLD = 240
    @Suppress("UNUSED_PARAMETER") set(value) { field = value.coerceIn(200, 255) }

var WHITE_PERCENTAGE = 0.01f
    @Suppress("UNUSED_PARAMETER") set(value) { field = value.coerceIn(0.001f, 0.1f) }

object SmartCrop {

    /**
     * Kiểm tra rect có phải vùng trắng không (IsRectWhite 1%).
     * @param bmp bitmap nguồn
     * @param rect vùng cần kiểm tra
     * @return true nếu >= 1% pixel trong rect là trắng (R,G,B >= 240)
     */
    fun isRectWhite(bmp: Bitmap, rect: Rect): Boolean {
        if (rect.isEmpty) return false
        val w = rect.width()
        val h = rect.height()
        if (w <= 0 || h <= 0) return false

        // Lấy pixel trong rect (sample nhanh: stride để tránh O(N^2) quá lớn)
        val stride = max(1, (w * h / 1000).coerceAtLeast(1)) // ~1000 samples
        var whiteCount = 0
        var totalSampled = 0

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, rect.left, rect.top, w, h)

        for (y in 0 until h step stride) {
            val base = y * w
            for (x in 0 until w step stride) {
                val color = pixels[base + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                if (r >= WHITE_THRESHOLD && g >= WHITE_THRESHOLD && b >= WHITE_THRESHOLD) {
                    whiteCount++
                }
                totalSampled++
            }
        }
        return if (totalSampled > 0) (whiteCount.toFloat() / totalSampled) >= WHITE_PERCENTAGE else false
    }

    /**
     * Tìm biên phải (right crop) - quét từ phải sang trái tìm cột KHÔNG trắng.
     * @return x coordinate của biên phải nội dung (exclusive), hoặc width nếu toàn trang trắng
     */
    fun findRightCrop(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        val rect = Rect(0, 0, w, h)
        // Quét từ phải sang trái, bước nhỏ để chính xác
        for (x in (w - 1) downTo 0) {
            val colRect = Rect(x, 0, x + 1, h)
            if (!isRectWhite(bmp, colRect)) {
                return x + 1 // exclusive
            }
        }
        return w
    }

    /**
     * Tìm biên trái (left crop) - quét từ trái sang phải.
     * @return x coordinate của biên trái nội dung (inclusive), hoặc 0
     */
    fun findLeftCrop(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        for (x in 0 until w) {
            val colRect = Rect(x, 0, x + 1, h)
            if (!isRectWhite(bmp, colRect)) {
                return x
            }
        }
        return 0
    }

    /**
     * Tìm biên dưới (bottom crop) - quét từ dưới lên.
     * @return y coordinate của biên dưới nội dung (exclusive), hoặc height
     */
    fun findBottomCrop(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        for (y in (h - 1) downTo 0) {
            val rowRect = Rect(0, y, w, y + 1)
            if (!isRectWhite(bmp, rowRect)) {
                return y + 1
            }
        }
        return h
    }

    /**
     * Tìm biên trên (top crop) - quét từ trên xuống.
     * @return y coordinate của biên trên nội dung (inclusive), hoặc 0
     */
    fun findTopCrop(bmp: Bitmap): Int {
        val h = bmp.height
        for (y in 0 until h) {
            val rowRect = Rect(0, y, bmp.width, y + 1)
            if (!isRectWhite(bmp, rowRect)) {
                return y
            }
        }
        return 0
    }

    /**
     * Tính crop rect tự động cho 1 bitmap (trả về Rect nội dung, loại viền trắng).
     * @return Rect crop (left, top, right, bottom) hoặc null nếu không crop được
     */
    fun autoCrop(bmp: Bitmap): Rect? {
        val left = findLeftCrop(bmp)
        val right = findRightCrop(bmp)
        val top = findTopCrop(bmp)
        val bottom = findBottomCrop(bmp)

        // Sanity check: crop phải có ý nghĩa (ít nhất 10% kích thước gốc)
        if (left >= right || top >= bottom) return null
        if ((right - left) < bmp.width / 10 || (bottom - top) < bmp.height / 10) return null

        return Rect(left, top, right, bottom)
    }

    /**
     * Crop bitmap theo rect (trả về bitmap mới, bitmap gốc không đổi).
     * @param bmp bitmap gốc
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