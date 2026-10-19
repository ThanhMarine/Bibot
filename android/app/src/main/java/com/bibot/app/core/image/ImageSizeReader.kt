package com.bibot.app.core.image

import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * A3.4 ImageSizeReader — đọc kích thước ảnh (width/height) KHÔNG decode full bitmap.
 * Dùng cho: comic/CBZ, EPUB cover, PDF page preview — lấy size nhanh để sort/layout.
 */
object ImageSizeReader {

    /**
     * Đọc width/height từ file ảnh (jpg/png/bmp/webp) chỉ đọc header.
     * @return Pair(width, height) hoặc null nếu lỗi
     */
    fun readSize(file: File): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            return Pair(opts.outWidth, opts.outHeight)
        }
        return null
    }

    /**
     * Đọc width/height từ InputStream (chỉ đọc header).
     * Lưu ý: InputStream phải support mark/reset hoặc caller tự quản lý.
     */
    fun readSize(stream: InputStream): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Cần mark/reset nếu stream không phải FileInputStream
        if (stream.markSupported()) {
            stream.mark(Int.MAX_VALUE)
            BitmapFactory.decodeStream(stream, null, opts)
            stream.reset()
        } else {
            BitmapFactory.decodeStream(stream, null, opts)
        }
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            return Pair(opts.outWidth, opts.outHeight)
        }
        return null
    }

    /**
     * Đọc size từ mảng byte (header).
     */
    fun readSize(bytes: ByteArray): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            return Pair(opts.outWidth, opts.outHeight)
        }
        return null
    }

    /** Helper: aspect ratio (width/height). */
    fun aspectRatio(file: File): Float? {
        readSize(file)?.let { (w, h) -> return if (h > 0) w.toFloat() / h else null }
        return null
    }

    /** Helper: orientation. */
    enum class Orientation { PORTRAIT, LANDSCAPE, SQUARE }

    fun orientation(file: File): Orientation? {
        readSize(file)?.let { (w, h) ->
            return when {
                w > h -> Orientation.LANDSCAPE
                w < h -> Orientation.PORTRAIT
                else -> Orientation.SQUARE
            }
        }
        return null
    }
}