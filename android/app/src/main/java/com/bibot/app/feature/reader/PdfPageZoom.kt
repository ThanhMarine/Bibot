package com.bibot.app.feature.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput

/** UX 2026-11-16: giới hạn zoom trang PDF (pinch / chạm đúp). */
const val PDF_ZOOM_MIN = 1f
const val PDF_ZOOM_MAX = 4f

/** Mức zoom khi chạm đúp (2.25× — đọc được chữ nhỏ mà vẫn thấy ngữ cảnh). */
const val PDF_ZOOM_STEP = 2.25f

/**
 * Trạng thái zoom + pan của MỘT trang PDF.
 *
 * Tách khỏi `PdfPageView` để: (1) giữ file đó ngắn, (2) logic clamp là hàm thuần
 * [clampZoomOffset] → unit test được, (3) mỗi trang một state riêng (dual mode
 * zoom độc lập, đổi trang không lẫn offset).
 *
 * Quy ước: [offset] là dịch chuyển **px màn hình** áp sau scale (graphicsLayer
 * translate), gốc ở tâm trang → clamp ±(scale-1)·kích thước/2 để mép trang không
 * hở khỏi khung.
 */
@Stable
class PdfPageZoomState {
    var scale by mutableFloatStateOf(PDF_ZOOM_MIN)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean get() = scale > PDF_ZOOM_MIN + 0.001f

    /** Chạm đúp: đang 1× → [PDF_ZOOM_STEP]; đang zoom → về 1× (reset pan). */
    fun toggleDoubleTap() {
        if (isZoomed) reset() else {
            scale = PDF_ZOOM_STEP
            offset = Offset.Zero
        }
    }

    fun reset() {
        scale = PDF_ZOOM_MIN
        offset = Offset.Zero
    }

    /**
     * Áp một bước gesture: [zoomChange] là tỉ lệ (1 = không đổi), [panChange] là
     * delta px của ngón tay, [viewSize] là kích thước trang đang hiển thị (px),
     * [centroid] là tâm 2 ngón (toạ độ trong khung) — zoom giữ đúng điểm dưới tay.
     */
    fun transform(
        zoomChange: Float,
        panChange: Offset,
        viewSize: Size,
        centroid: Offset = Offset(viewSize.width / 2f, viewSize.height / 2f)
    ) {
        if (viewSize.width <= 0f || viewSize.height <= 0f) return
        val target = (scale * zoomChange).coerceIn(PDF_ZOOM_MIN, PDF_ZOOM_MAX)
        if (target <= PDF_ZOOM_MIN + 0.001f) {
            // Về 1× → không còn gì để pan (tránh trang bị đẩy lệch khi zoom out).
            scale = PDF_ZOOM_MIN
            offset = Offset.Zero
            return
        }
        // Giữ điểm dưới ngón tay đứng yên khi phóng: dịch tâm theo tỉ lệ zoom
        // quanh centroid (gốc toạ độ = tâm khung) rồi mới cộng pan.
        val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
        val arm = centroid - center
        val ratio = target / scale
        val next = arm - (arm - offset) * ratio + panChange
        scale = target
        offset = clampZoomOffset(next, target, viewSize)
    }
}

/**
 * Giới hạn offset pan để mép trang luôn phủ kín khung: |x| ≤ (scale-1)·w/2,
 * |y| ≤ (scale-1)·h/2. Hàm thuần — có unit test.
 */
internal fun clampZoomOffset(offset: Offset, scale: Float, viewSize: Size): Offset {
    if (viewSize.width <= 0f || viewSize.height <= 0f) return Offset.Zero
    val maxX = ((scale - PDF_ZOOM_MIN) * viewSize.width / 2f).coerceAtLeast(0f)
    val maxY = ((scale - PDF_ZOOM_MIN) * viewSize.height / 2f).coerceAtLeast(0f)
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY)
    )
}

/**
 * Gesture zoom/pan cho trang PDF:
 * - 2 ngón → pinch zoom (1×–4×) + pan cùng lúc;
 * - 1 ngón khi ĐANG zoom → pan trang;
 * - 1 ngón khi 1× → KHÔNG consume → LazyColumn vẫn cuộn dọc / chuyển trang bình thường.
 *
 * Nhờ chỉ consume khi thực sự zoom, `combinedClickable` (tap / double tap / long
 * press) bên ngoài vẫn hoạt động, và list không bị "khoá" ở mức 1×.
 */
fun Modifier.pdfPageZoomGestures(state: PdfPageZoomState): Modifier = pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        // Đang zoom thì một ngón đã là pan; nếu chưa zoom chỉ bật khi có 2 ngón.
        var gestureOwned = state.isZoomed
        var pastSlop = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) gestureOwned = true
            if (!gestureOwned) continue
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastSlop) {
                pastSlop = zoomChange != 1f || panChange.getDistance() > viewConfiguration.touchSlop
            }
            if (pastSlop) {
                val view = Size(size.width.toFloat(), size.height.toFloat())
                val rawCentroid = event.calculateCentroid()
                val centroid = if (rawCentroid.x.isFinite() && rawCentroid.y.isFinite()) rawCentroid
                else Offset(view.width / 2f, view.height / 2f)
                state.transform(
                    zoomChange = zoomChange,
                    panChange = panChange,
                    viewSize = view,
                    centroid = centroid
                )
                event.changes.forEach { change -> if (change.pressed) change.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}
