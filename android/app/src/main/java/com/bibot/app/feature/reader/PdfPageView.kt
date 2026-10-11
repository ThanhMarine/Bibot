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
    var pageCount by remember(file.absolutePath) { mutableIntStateOf(0) }
    var openError by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    val bitmaps = remember(file.absolutePath) { mutableStateMapOf<Int, Bitmap>() }
    val failed = remember(file.absolutePath) { mutableStateMapOf<Int, Boolean>() }

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

    suspend fun renderPage(idx: Int) {
        if (idx !in 0 until pageCount) return
        if (bitmaps.containsKey(idx) || failed.containsKey(idx)) return
        val r = renderer ?: return
        try {
            val bmp = withContext(Dispatchers.IO) {
                var page: PdfRenderer.Page? = null
                try {
                    page = r.openPage(idx)
                    val w = page.width
                    val h = page.height
                    if (w <= 0 || h <= 0) return@withContext null
                    val scale = widthPx.toFloat() / w
                    val bw = widthPx
                    val bh = (h * scale).toInt().coerceIn(1, 2400)
                    // PdfRenderer framework bắt buộc ARGB_8888 (xem kdoc E1.5).
                    val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                } catch (_: Exception) {
                    null
                } finally {
                    try { page?.close() } catch (_: Exception) {}
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
    // TUẦN TỰ trong 1 effect (PdfRenderer không thread-safe — từng race khi
    // prefetch song song); đổi trang sẽ cancel effect cũ rồi render lại.
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
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .then(
                        if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary)
                        else Modifier
                    )
                    .combinedClickable(
                        onClick = onPageTap,
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
                    Text(
                        "⚠ ${idx + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(32.dp)
                    )
                } else {
                    Text(
                        "${idx + 1} / $pageCount",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(32.dp)
                    )
                    LaunchedEffect(idx) { renderPage(idx) }
                }
            }
        }
    }
}
