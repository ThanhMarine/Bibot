package com.bibot.app.feature.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bibot.app.R
import com.bibot.app.core.image.SmartCrop
import com.bibot.app.ui.player.contentBottomInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// E2.4: ma trận màu 4x5 (cùng định dạng android ColorMatrix) — sepia cổ điển
// và đảo màu chế độ Đêm (white→black, black→white — như night mode ReadEra).
private val SEPIA_COLOR_MATRIX = floatArrayOf(
    0.393f, 0.769f, 0.189f, 0f, 0f,
    0.349f, 0.686f, 0.168f, 0f, 0f,
    0.272f, 0.534f, 0.131f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f
)
private val NIGHT_COLOR_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
)

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
 * - E2.4 display modes (PdfDisplayMode.kt): fit-width/fit-page, continuous/
 *   single(snap)/dual, xoay lúc render, theme sepia/night (ColorFilter).
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
    // E2.4: display modes — hằng ở PdfDisplayMode.kt (persist qua BibotPreferences).
    fitMode: String = FIT_WIDTH,
    pagingMode: String = PAGING_CONTINUOUS,
    pageTheme: String = THEME_ORIGINAL,
    pageRotation: Int = 0,
    // E2.3: crop lề trắng (SmartCrop IsRectWhite) — cắt lúc render, khung giữ
    // tỉ lệ trang gốc (ảnh crop chỉ letterbox) → placeholder KHÔNG đổi chiều cao.
    cropMargins: Boolean = false,
    // E2.4: ghim vị trí khi đổi paging mode (target + tick set trong onPagingChange).
    pagingAlignTarget: Int = 0,
    pagingAlignTick: Int = 0,
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
    // E2.3: số đo crop — render đang chạy với cờ crop CŨ khi user đổi toggle sẽ
    // bị bỏ (ép recycle) thay vì chèn bitmap sai trạng thái vào pool.
    var cropEpoch by remember(file.absolutePath) { mutableIntStateOf(0) }

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

    // E2.4: đổi XOAY → render lại mọi bitmap theo tỉ lệ mới (xoay lúc render
    // giữ pixel; placeholder đổi tỉ lệ eff cùng lúc → không lệch chiều cao).
    LaunchedEffect(pageRotation) {
        if (bitmaps.isNotEmpty()) {
            for (b in bitmaps.values) { try { b.recycle() } catch (_: Exception) {} }
            bitmaps.clear()
        }
        if (failed.isNotEmpty()) failed.clear()
    }

    // E2.3: đổi CROP LỀ → render lại pool theo cờ mới. KHÔNG đụng pageRatios
    // (tỉ lệ khung hình trang gốc giữ nguyên) → placeholder giữ nguyên chiều cao,
    // ảnh crop letterbox trong khung → anchor/scroll đứng yên (bài học E2.3).
    LaunchedEffect(cropMargins) {
        cropEpoch++
        if (bitmaps.isNotEmpty()) {
            for (b in bitmaps.values) { try { b.recycle() } catch (_: Exception) {} }
            bitmaps.clear()
        }
        if (failed.isNotEmpty()) failed.clear()
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

    // E2.4: jump tới trang LẺ trong cặp dual → header phải hiện đúng trang đó
    // (không phải trang trái của cặp). Set trước khi scroll, consume khi báo.
    var pendingJumpReport by remember(file.absolutePath) { mutableIntStateOf(-1) }
    // E2.4: trang cuối header từng báo — đổi paging mode ghim lại theo trang
    // NÀY (không dùng pageIndex ở ReaderScreen: visible effect re-key chạy
    // trước mọi bump → pageIndex đã bị đọc lại theo ghép mới → clobber).
    var lastReportedPage by remember(file.absolutePath) { mutableIntStateOf(initialPage.coerceIn(0, pageCount - 1)) }

    // E2.4: ghim vị trí khi đổi paging mode. Target do ReaderScreen set NGAY
    // TRONG onPagingChange (pageIndex cũ còn đúng, trước khi visible effect
    // re-key đọc lại firstVisible theo ghép mới → clobber). Effect keyed
    // (tick, mode): chạy lần 1 với mode cũ (no-op), lần 2 khi mode mới tới
    // → scroll + pending báo header đúng trang.
    LaunchedEffect(pagingAlignTick, pagingMode) {
        if (pagingAlignTick <= 0) return@LaunchedEffect
        if (pageCount <= 0) return@LaunchedEffect
        val t = pagingAlignTarget.coerceIn(0, pageCount - 1)
        val dualNew = pagingMode == PAGING_DUAL
        val targetItem = if (dualNew) t / 2 else t
        if (dualNew) pendingJumpReport = t else pendingJumpReport = -1
        if (listState.firstVisibleItemIndex == targetItem) return@LaunchedEffect
        try { listState.scrollToItem(targetItem) } catch (_: Exception) {}
    }

    // E2.3: nhảy tới trang neo (resume chip / TOC outline / panel search / book search) —
    // trước đây CHỈ header đổi, canvas đứng yên vì list không ai scroll (initialPage=0).
    // E2.4 dual: item = cặp (trang/2) — nhảy trang lẻ vẫn thấy trang đó (nửa phải).
    LaunchedEffect(jumpPage, jumpTick) {
        val t = jumpPage ?: return@LaunchedEffect
        if (t !in 0 until pageCount) return@LaunchedEffect
        val dual = pagingMode == PAGING_DUAL
        val curPage = if (dual) listState.firstVisibleItemIndex * 2 else listState.firstVisibleItemIndex
        if (t != curPage) {
            if (dual) pendingJumpReport = t
            // Chờ đủ tỉ lệ MỌI trang trước khi scroll: scrollToItem đo item
            // đang compose (placeholder) — thiếu tỉ lệ sẽ cộng dồn chiều cao
            // sai và rơi index (best-effort, timeout 4s rồi vẫn scroll).
            kotlinx.coroutines.withTimeoutOrNull(4000) {
                while (pageRatios.size < pageCount) kotlinx.coroutines.delay(50)
            }
            // scrollToItem với layout chưa đủ tỉ lệ từng rơi sai index rồi bị
            // anchor đứng yên (E2.3) — nuốt lỗi để không crash; nhảy hỏng chỉ
            // là đứng yên như trước khi có tính năng.
            try { listState.scrollToItem(if (dual) t / 2 else t) } catch (_: Exception) {}
        } else if (dual) {
            // Đã đúng cặp nhưng header có thể đang hiện trang kia → báo lại.
            pendingJumpReport = t
        }
    }

    suspend fun renderPage(idx: Int) {
        if (idx !in 0 until pageCount) return
        if (bitmaps.containsKey(idx) || failed.containsKey(idx)) return
        val r = renderer ?: return
        val epoch = cropEpoch
        try {
            val bmp = renderMutex.withLock {
                withContext(Dispatchers.IO) {
                    val raw = renderPdfBitmap(r, idx, widthPx, pageRotation)
                        // Trang nặng (Type3/ảnh lớn, vd grimm10) OOM/lỗi ở full-width →
                        // thử lại nửa chiều rộng thay vì bỏ trang (tôn trọng bản gốc).
                        ?: renderPdfBitmap(r, idx, widthPx / 2, pageRotation)
                    // E2.3: crop lề trắng lúc render — SmartCrop IsRectWhite; null =
                    // không cắt được → giữ nguyên trang gốc (an toàn).
                    if (raw != null && cropMargins) cropBitmapMargins(raw) else raw
                }
            }
            when {
                bmp == null -> failed[idx] = true
                // Toggle crop giữa chừng → kết quả sinh với cờ CŨ bị bỏ (recycle),
                // tránh hiển thị bitmap không khớp trạng thái hiện tại.
                epoch != cropEpoch -> try { bmp.recycle() } catch (_: Exception) {}
                else -> bitmaps[idx] = bmp
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Effect khác (đổi crop/xoay) hủy giữa chừng ≠ trang lỗi — không đánh dấu failed.
            throw e
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
    // E2.4 dual: item = cặp → v = trang trái của cặp, prefetch thêm trang phải.
    // jumpTick trong key: jump trong CÙNG cặp (không đổi firstVisible) vẫn phải
    // báo header lại đúng trang đích (pendingJumpReport).
    // cropMargins trong key: đổi crop → render lại visible ±1 theo cờ mới.
    LaunchedEffect(listState.firstVisibleItemIndex, pageCount, pagingMode, jumpTick, cropMargins) {
        val dual = pagingMode == PAGING_DUAL
        val first = listState.firstVisibleItemIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val v = if (dual) (first * 2).coerceAtMost(pageCount - 1) else first
        // Jump dual tới trang lẻ (nửa phải của cặp) → báo đúng trang đích.
        // Chỉ consume pending KHI THỰC SỰ report: skip (rep==last) mà consume
        // là mất pending → run vis sau report v=trang trái cặp → header nhảy
        // sai sau khi đổi paging mode (single→dual thấy Page1 thay vì Page2).
        val wantRep = if (dual && pendingJumpReport in v..(v + 1)) pendingJumpReport else v
        if (wantRep != lastReportedPage) {
            pendingJumpReport = -1
            onVisiblePage(wantRep)
            lastReportedPage = wantRep
        }
        renderPage(v)
        val deltas = if (dual) listOf(-1, 1, 2) else listOf(-1, 1)
        for (d in deltas) {
            val n = v + d
            if (n in 0 until pageCount && !bitmaps.containsKey(n) && !failed.containsKey(n)) {
                renderPage(n)
            }
        }
        evictExcept(deltas.map { v + it }.toSet() + v)
    }

    // E2.4: filter màu trang + nền khung (theme) — tính 1 lần/setting.
    val themeFilter = remember(pageTheme) {
        when (pageTheme) {
            THEME_SEPIA -> ColorFilter.colorMatrix(ColorMatrix(SEPIA_COLOR_MATRIX))
            THEME_NIGHT -> ColorFilter.colorMatrix(ColorMatrix(NIGHT_COLOR_MATRIX))
            else -> null
        }
    }
    val paneBg = when (pageTheme) {
        THEME_SEPIA -> Color(0xFFEFE3C8)
        THEME_NIGHT -> Color(0xFF0E0E0E)
        else -> Color.Transparent
    }
    // E2.4 single: snap 1 trang/lần vuốt (chỉ dùng khi mode single — còn lại
    // fling mặc định của LazyColumn).
    val snapFling = rememberSnapFlingBehavior(lazyListState = listState)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Khung thật của list — fit-page tính chiều cao theo khung này.
        val boxW = maxWidth
        val boxH = maxHeight
        val dual = pagingMode == PAGING_DUAL

        // Kích thước trang CHUNG cho bitmap VÀ placeholder với tỉ lệ HIỂNG THỊ
        // (ảnh đã xoay → bmp w/h tự là tỉ lệ hiệu dụng; placeholder → effPageRatio).
        // E2.3: 2 bên PHẢI trùng nhau, không là scrollToItem rơi index.
        fun pageSizeDp(ratioEff: Float, availW: Dp): Pair<Dp, Dp> {
            val maxW = availW - 8.dp       // padding khung giấy 4dp × 2
            val maxH = boxH - 24.dp        // chừa nhịp trên/dưới
            val (w, h) = fitPageSize(maxW.value, maxH.value, ratioEff, fitMode)
            return w.dp to h.dp
        }

        @Composable
        fun PagePane(idx: Int, availW: Dp, paneModifier: Modifier) {
            if (idx !in 0 until pageCount) {
                Spacer(paneModifier)
                return
            }
            val bmp = bitmaps[idx]
            val isSel = selectedPage == idx
            // E2.3 crop: khung LUÔN theo tỉ lệ trang GỐC (pageRatios) — bitmap đã
            // cắt letterbox trong khung → placeholder == kích thước khi render
            // (cùng invariant với E2.3, không xê dịch anchor). Không crop: giữ
            // nhịp cũ, bmp thắng (trùng tỉ lệ gốc ± làm tròn).
            val ratioEff = if (!cropMargins && bmp != null && !bmp.isRecycled) {
                bmp.width.toFloat() / bmp.height.toFloat()
            } else {
                effPageRatio(pageRatios[idx] ?: 0.707f, pageRotation)
            }
            val (pw, ph) = pageSizeDp(ratioEff, availW)
            Box(
                modifier = paneModifier
                    .background(paneBg)
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
                        modifier = Modifier.size(pw, ph),
                        colorFilter = themeFilter
                    )
                } else if (failed.containsKey(idx)) {
                    Box(modifier = Modifier.size(pw, ph), contentAlignment = Alignment.Center) {
                        Text(
                            "⚠ ${idx + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                } else {
                    // Ô chờ render PHẢI đúng kích thước trang (như Image bitmap):
                    // Text thấp sẵn làm LazyColumn đo item thiếu → scrollToItem
                    // nhảy rơi sai index rồi đứng yên (bug E2.3 canvas không nhảy).
                    Box(modifier = Modifier.size(pw, ph), contentAlignment = Alignment.Center) {
                        Text(
                            "${idx + 1} / $pageCount",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                    LaunchedEffect(idx, pageRotation, cropMargins) { renderPage(idx) }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentBottomInset()),
            flingBehavior = if (pagingMode == PAGING_SINGLE) snapFling
            else ScrollableDefaults.flingBehavior()
        ) {
            val pairCount = if (dual) (pageCount + 1) / 2 else pageCount
            items(pairCount, key = { it }) { pair ->
                if (dual) {
                    val left = pair * 2
                    val right = left + 1
                    // Hai cột đều nhau: mỗi cột (khung - khe) / 2 — trang tính theo
                    // pageSizeDp(availW) nên bitmap + placeholder vẫn khớp nhau.
                    val availW = (boxW - 12.dp) / 2
                    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp)) {
                        PagePane(left, availW, Modifier.weight(1f).padding(horizontal = 2.dp))
                        if (right < pageCount) {
                            PagePane(right, availW, Modifier.weight(1f).padding(horizontal = 2.dp))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    PagePane(
                        pair, boxW,
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                    )
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
 * E2.3 crop lề (SmartCrop IsRectWhite 1%): cắt viền trắng quanh trang ĐÃ render
 * (kể cả sau xoay — crop theo hướng hiển thị). Trả về bitmap MỚI (nguồn bị
 * recycle) hoặc nguồn khi không cắt được — không bao giờ làm mất trang.
 */
private fun cropBitmapMargins(src: Bitmap): Bitmap {
    return try {
        val rect = SmartCrop.autoCrop(src) ?: return src
        val out = Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height())
        if (out !== src) {
            try { src.recycle() } catch (_: Exception) {}
        }
        out
    } catch (_: Throwable) {
        src
    }
}

/**
 * Render 1 trang PDF ở bề rộng [targetW]px (đã áp [rotation] — E2.4), GIỮ đúng
 * tỷ lệ khung hình qua `displaySizeFor` (E1.5 trần 2400) + `sourceSizeForRotation`
 * (nguồn bitmap trước xoay) rồi quay Matrix. Null khi lỗi/OOM.
 */
private fun renderPdfBitmap(r: PdfRenderer, idx: Int, targetW: Int, rotation: Int = 0): Bitmap? {
    var page: PdfRenderer.Page? = null
    return try {
        page = r.openPage(idx)
        val w = page.width
        val h = page.height
        val (dw, dh) = displaySizeFor(w, h, targetW, rotation)
        if (dw <= 0 || dh <= 0) return null
        val (sw, sh) = sourceSizeForRotation(dw, dh, rotation)
        if (sw <= 0 || sh <= 0) return null
        // PdfRenderer framework bắt buộc ARGB_8888 (xem kdoc E1.5).
        val src = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        page.render(src, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val rot = ((rotation % 360) + 360) % 360
        if (rot == 0) return src
        val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, sw, sh, m, true)
        if (out !== src) { try { src.recycle() } catch (_: Exception) {} }
        out
    } catch (t: Throwable) {
        // Log nguyên nhân (Type3/OOM/...) để chẩn đoán trang lỗi — UI vẫn placeholder.
        try { android.util.Log.w("PdfPageView", "render p$idx @${targetW}px rot=$rotation fail: ${t::class.java.simpleName}: ${t.message}") } catch (_: Exception) {}
        null
    } finally {
        try { page?.close() } catch (_: Exception) {}
    }
}
