package com.bibot.app.feature.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.bibot.app.core.text.EpubReader
import com.bibot.app.data.books.BookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * B1.2 — Bìa sách cho row thư viện, +0 lib (quyết định lệch checklist: KHÔNG thêm
 * Coil — PdfRenderer framework + decode EPUB cover sẵn có là đủ):
 *  - PDF đã tải: render trang 0 thu nhỏ (~160px) qua PdfRenderer
 *    (framework bắt buộc ARGB_8888 — thumb nhỏ nên RAM không đáng kể).
 *  - EPUB đã tải: `EpubReader.readCover` → decode sampled (tránh OOM).
 * - Còn lại (chưa tải / md/txt): null → UI vẽ ô chữ cái + icon định dạng.
 * Cache RAM LRU 24 bitmap (~160px ≈ 50KB/ảnh). Mọi render/decode trên IO.
 */
object LibraryCovers {
    private const val MAX_W = 160
    private const val CACHE_N = 24

    private val mem: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(CACHE_N) {
        override fun sizeOf(key: String, v: Bitmap): Int = 1
    }

    @Synchronized
    private fun get(key: String): Bitmap? = try { mem.get(key) } catch (_: Exception) { null }

    @Synchronized
    private fun put(key: String, bmp: Bitmap) = try { mem.put(key, bmp) } catch (_: Exception) { }

    /** Bitmap bìa hoặc null (caller vẽ placeholder). Key theo bookId + size file. */
    suspend fun coverFor(book: BookMeta): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val f = book.localFile?.takeIf { it.exists() } ?: return@withContext null
            val key = book.id + ":" + (f.length() / 1024)
            get(key)?.let { return@withContext it }
            val bmp = when {
                f.extension.equals("pdf", ignoreCase = true) -> renderPdfThumb(f)
                f.extension.equals("epub", ignoreCase = true) -> decodeEpubCover(f)
                else -> null
            }
            if (bmp != null) put(key, bmp)
            bmp
        } catch (_: Exception) { null }
    }

    private fun renderPdfThumb(f: File): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return null
            page = renderer.openPage(0)
            val w = page.width
            val h = page.height
            if (w <= 0 || h <= 0) return null
            val scale = MAX_W.toFloat() / w
            val bw = MAX_W
            val bh = (h * scale).toInt().coerceIn(1, 320)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } catch (_: Exception) {
            null
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun decodeEpubCover(f: File): Bitmap? {
        return try {
            val bytes = EpubReader.readCover(f) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > MAX_W * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }
}
