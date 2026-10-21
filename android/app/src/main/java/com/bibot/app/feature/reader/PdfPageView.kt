package com.bibot.app.feature.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bibot.app.R
import com.bibot.app.ui.player.contentBottomInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * E1.1 — Đường DISPLAY thuần cho PDF (VIEW, không chứa logic TTS):
 * Android `PdfRenderer` framework (+0 lib, tránh GPL) + pool bitmap LRU +
 * prefetch ±1 trang. Controller TTS (ReaderScreen) đọc text tương ứng.
 *
 * - `pageCount` có ngay sau open (không parse hết — E1.2 mở nhanh file lớn).
 * - RAM (E1.5): chỉ giữ bitmap trang hiện tại ±1 + recycle khi evict.
 *   Lệch E1.5: PdfRenderer framework BẮT BUỘC ARGB_8888 (RGB_565 ném
 *   IllegalArgumentException) — bù lại bằng pool 3 trang.
 * - Trang lỗi render → ô lỗi, không crash cả list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfPageView(
    file: File,
    initialPage: Int = 0,
    selectedPage: Int? = null,
    // E2.3: bấm nhảy (TOC/panel/search/resume) → trang đích + tick: effect scroll chạy
    // lại cả khi selectedPage không đổi (user đã vuốt đi chỗ khác).
    jumpPage: Int? = null,
    jumpTick: Int = 0,
    onVisiblePage: (Int) -> Unit,
    onPageTap: () -> Unit,
    onPageLongPress: (Int) -> Unit,
    onPageCount: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val widthPx = ((config.screenWidthDp * density.density).toInt()).coerceIn(240, 1600)

    var renderer by remember(file.absolutePath) { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember(file.absolutePath) { mutableStateOf<ParcelFileDescriptor?>(null) }
    // B6.16: double-tap trang → zoom fullscreen (bitmap pool, đã render đúng).
    var zoomIdx by remember(file.absolutePath) { mutableIntStateOf(-1) }
    // PdfRenderer KHÔNG thread-safe (1 Page mở tại 1 thời điểm) — mọi render
    // (effect chính + fallback từng item) phải qua mutex này, nếu không
    // "IllegalStateException: Current page not closed" rải rác (vd grimm10 p18/19).
    val renderMutex = remember(file.absolutePath) { Mutex() }
    var pageCount by remember(file.absolutePath) { mutableIntStateOf(0) }
    var openError by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    val bitmaps = remember(file.absolutePath) { mutableStateMapOf<Int, Bitmap>() }
    val failed = remember(file.absolutePath) { mutableStateMapOf<Int, Boolean>() }
    // E2.3: tỉ lệ khung hình từng trang (PdfRenderer Page.getSize — chỉ parse
    // mediabox, KHÔNG render). Placeholder item dùng đúng chiều cao NGAY khi
    // LazyColumn đo list cho scrollToItem; nếu ô chờ là Text thấp sẵn thì
    // scroll cộng dồn chiều cao thiếu → nhảy rơi sai index (vd land 3 thay vì 8)
    // rồi bị anchor đứng yên ở đó khi bitmap render.
    val pageRatios = remember(file.absolutePath) { mutableStateMapOf<Int, Float>() }

    // Mở renderer 1 lần / file (IO — open nhanh, không inflate).
    LaunchedEffect(file.absolutePath) {
        withContext(Dispatchers.IO) {
            try {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(fd)
                pfd = fd
                renderer = r
                pageCount = r.pageCount
                onPageCount(r.pageCount)
                // Đọc tỉ lệ mọi trang (không render). Khóa renderMutex TỪNG trang
                // để renderPage đang chờ được xen lượt (PdfRenderer: 1 Page mở
                // tại 1 thời điểm).
                for (i in 0 until r.pageCount) {
                    renderMutex.withLock {
                        var p: PdfRenderer.Page? = null
                        try {
                            p = r.openPage(i)
                            if (p.width > 0 && p.height > 0) {
                                pageRatios[i] = p.width.toFloat() / p.height.toFloat()
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { p?.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                try { pfd?.close() } catch (_: Exception) {}
                pfd = null
                openError = e.message ?: ""
            }
        }
    }

    DisposableEffect(file.absolutePath) {
        onDispose {
            try {
                bitmaps.values.forEach { b -> try { b.recycle() } catch (_: Exception) {} }
                bitmaps.clear()
            } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            renderer = null
            pfd = null
        }
    }

    if (openError != null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                context.getString(R.string.reader_error, openError ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }
    if (pageCount <= 0) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPage.coerceIn(0, pageCount - 1)
    )

    // E2.3: nhảy tới trang neo (resume chip / TOC outline / panel search / book search) —
    // trước đây CHỈ header đổi, canvas đứng yên vì list không ai scroll (initialPage=0).
    LaunchedEffect(jumpPage, jumpTick) {
        val t = jumpPage ?: return@LaunchedEffect
        if (t in 0 until pageCount && t != listState.firstVisibleItemIndex) {
            // Chờ đủ tỉ lệ MỌI trang trước khi scroll: scrollToItem đo item
            // đang compose (placeholder) — thiếu tỉ lệ sẽ cộng dồn chiều cao
            // sai và rơi index (best-effort, timeout 4s rồi vẫn scroll).
            kotlinx.coroutines.withTimeoutOrNull(4000) {
                while (pageRatios.size < pageCount) kotlinx.coroutines.delay(50)
            }
            // scrollToItem với layout chưa đủ tỉ lệ từng rơi sai index rồi bị
            // anchor đứng yên (E2.3) — nuốt lỗi để không crash; nhảy hỏng chỉ
            // là đứng yên như trước khi có tính năng.
            try { listState.scrollToItem(t) } catch (_: Exception) {}
        }
    }

    suspend fun renderPage(idx: Int) {
        if (idx !in 0 until pageCount) return
        if (bitmaps.containsKey(idx) || failed.containsKey(idx)) return
        val r = renderer ?: return
        try {
            val bmp = renderMutex.withLock {
                withContext(Dispatchers.IO) {
                    renderPdfBitmap(r, idx, widthPx)
                        // Trang nặng (Type3/ảnh lớn, vd grimm10) OOM/lỗi ở full-width →
                        // thử lại nửa chiều rộng thay vì bỏ trang (tôn trọng bản gốc).
                        ?: renderPdfBitmap(r, idx, widthPx / 2)
                }
            }
            if (bmp != null) bitmaps[idx] = bmp else failed[idx] = true
        } catch (_: Exception) {
            failed[idx] = true
        }
    }

    suspend fun evictExcept(keep: Set<Int>) {
        val dead = bitmaps.keys.filter { it !in keep }
        for (k in dead) {
            val b = bitmaps.remove(k)
            try { b?.recycle() } catch (_: Exception) {}
        }
        for (k in failed.keys.filter { it !in keep }) failed.remove(k)
    }

    // Trang hiện tại + prefetch ±1 (port getPage(i,decode)+free của ReadEra).
    // Mọi render qua renderMutex (PdfRenderer không thread-safe: 1 Page mở tại
    // 1 thời điểm — effect chính + fallback từng item mà song song là
    // "IllegalStateException: Current page not closed"); đổi trang cancel effect cũ.
    LaunchedEffect(listState.firstVisibleItemIndex, pageCount) {
        val v = listState.firstVisibleItemIndex.coerceIn(0, pageCount - 1)
        onVisiblePage(v)
        renderPage(v)
        for (d in listOf(-1, 1)) {
            val n = v + d
            if (n in 0 until pageCount && !bitmaps.containsKey(n) && !failed.containsKey(n)) {
                renderPage(n)
            }
        }
        evictExcept(setOf(v - 1, v, v + 1))
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentBottomInset())
    ) {
        items(pageCount, key = { it }) { idx ->
            val bmp = bitmaps[idx]
            val isSel = selectedPage == idx
            Box(
                // Giả lập tờ giấy: lề hẹp 4dp (was 8dp) để ảnh trang tràn ngang
                // tối đa; viền trang 0.4dp outlineVariant giữ cảm giác tờ giấy.
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .border(
                        0.4.dp, MaterialTheme.colorScheme.outlineVariant,
                        androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                    )
                    .then(
                        if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary)
                        else Modifier
                    )
                    .combinedClickable(
                        onClick = onPageTap,
                        onDoubleClick = { zoomIdx = idx },
                        onLongClick = { onPageLongPress(idx) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null && !bmp.isRecycled) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.chrome_page_of, idx + 1, pageCount),
                        modifier = Modifier.fillMaxWidth()
                            .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                    )
                } else if (failed.containsKey(idx)) {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .aspectRatio(pageRatios[idx] ?: 0.707f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "⚠ ${idx + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                } else {
                    // Ô chờ render PHẢI cao đúng tỉ lệ trang (giống Image bitmap):
                    // Text thấp sẵn làm LazyColumn đo item thiếu → scrollToItem
                    // nhảy rơi sai index rồi đứng yên (bug E2.3 canvas không nhảy).
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .aspectRatio(pageRatios[idx] ?: 0.707f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${idx + 1} / $pageCount",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                    LaunchedEffect(idx) { renderPage(idx) }
                }
            }
        }
    }

    // B6.16: dialog zoom trang (pinch) — dùng bitmap pool đã render đúng
    // (render mới trong dialog cho bitmap đen); thiếu/recycled → render bù.
    if (zoomIdx in 0 until pageCount) {
        val zb = bitmaps[zoomIdx]
        if (zb != null && !zb.isRecycled) {
            ZoomableImageDialog(
                bitmap = zb,
                contentDescription = stringResource(R.string.chrome_page_of, zoomIdx + 1, pageCount),
                onDismiss = { zoomIdx = -1 }
            )
        } else {
            LaunchedEffect(file.absolutePath, zoomIdx) { renderPage(zoomIdx) }
        }
    }
}

/**
 * Render 1 trang PDF ở bề rộng [targetW]px, GIỮ đúng tỷ lệ khung hình
 * (bản cũ cap chiều cao 2400 làm trang dài bị bẹp). Null khi lỗi/OOM.
 */
private fun renderPdfBitmap(r: PdfRenderer, idx: Int, targetW: Int): Bitmap? {
    var page: PdfRenderer.Page? = null
    return try {
        page = r.openPage(idx)
        val w = page.width
        val h = page.height
        if (w <= 0 || h <= 0) return null
        var bw = targetW.coerceIn(240, 1600)
        var bh = (h * (bw.toFloat() / w)).toInt()
        if (bh > 2400) {
            // Trang quá dài: thu nhỏ CẢ 2 chiều (giữ aspect), tránh OOM.
            val s = 2400f / bh
            bw = (bw * s).toInt().coerceAtLeast(240)
            bh = 2400
        }
        if (bw <= 0 || bh <= 0) return null
        // PdfRenderer framework bắt buộc ARGB_8888 (xem kdoc E1.5).
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        b
    } catch (t: Throwable) {
        // Log nguyên nhân (Type3/OOM/...) để chẩn đoán trang lỗi — UI vẫn placeholder.
        try { android.util.Log.w("PdfPageView", "render p$idx @${targetW}px fail: ${t::class.java.simpleName}: ${t.message}") } catch (_: Exception) {}
        null
    } finally {
        try { page?.close() } catch (_: Exception) {}
    }
}
