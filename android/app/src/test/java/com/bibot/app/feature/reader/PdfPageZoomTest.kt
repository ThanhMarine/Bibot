package com.bibot.app.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UX 2026-11-16 — Test logic thuần zoom/pan trang PDF (không cần Compose runtime).
 */
class PdfPageZoomTest {

    private val view = Size(1000f, 2000f)

    @Test fun `1x khong cho pan`() {
        val clamped = clampZoomOffset(Offset(300f, -400f), PDF_ZOOM_MIN, view)
        assertEquals(0f, clamped.x, 0.001f)
        assertEquals(0f, clamped.y, 0.001f)
    }

    @Test fun `2x cho pan toi da nua kich thuoc`() {
        // (scale-1) * w / 2 = 500, (scale-1) * h / 2 = 1000
        assertEquals(500f, clampZoomOffset(Offset(900f, 0f), 2f, view).x, 0.001f)
        assertEquals(-500f, clampZoomOffset(Offset(-900f, 0f), 2f, view).x, 0.001f)
        assertEquals(1000f, clampZoomOffset(Offset(0f, 5000f), 2f, view).y, 0.001f)
        assertEquals(-1000f, clampZoomOffset(Offset(0f, -5000f), 2f, view).y, 0.001f)
    }

    @Test fun `pan trong gioi han giu nguyen`() {
        val clamped = clampZoomOffset(Offset(120f, -300f), 1.5f, view)
        assertEquals(120f, clamped.x, 0.001f)
        assertEquals(-300f, clamped.y, 0.001f)
    }

    @Test fun `view size 0 khong crash`() {
        assertEquals(Offset.Zero, clampZoomOffset(Offset(10f, 10f), 2f, Size.Zero))
    }

    @Test fun `transform clamp scale vao 1 den 4`() {
        val s = PdfPageZoomState()
        s.transform(zoomChange = 0.1f, panChange = Offset.Zero, viewSize = view)
        assertEquals(PDF_ZOOM_MIN, s.scale, 0.001f)
        s.transform(zoomChange = 100f, panChange = Offset.Zero, viewSize = view)
        assertEquals(PDF_ZOOM_MAX, s.scale, 0.001f)
    }

    @Test fun `zoom out ve 1x thi xoa offset`() {
        val s = PdfPageZoomState()
        s.transform(zoomChange = 2f, panChange = Offset(200f, 100f), viewSize = view)
        assertEquals(true, s.isZoomed)
        s.transform(zoomChange = 0.1f, panChange = Offset.Zero, viewSize = view)
        assertEquals(false, s.isZoomed)
        assertEquals(Offset.Zero, s.offset)
    }

    @Test fun `zoom giu diem duoi ngon tay`() {
        val s = PdfPageZoomState()
        // Phóng 2× ở mép phải (x=900, tâm khung x=500) → nội dung dịch sang trái 400px.
        s.transform(
            zoomChange = 2f,
            panChange = Offset.Zero,
            viewSize = view,
            centroid = Offset(900f, 1000f)
        )
        assertEquals(2f, s.scale, 0.001f)
        assertEquals(-400f, s.offset.x, 0.5f)
        assertEquals(0f, s.offset.y, 0.5f)
    }

    @Test fun `double tap bat roi tat zoom`() {
        val s = PdfPageZoomState()
        s.toggleDoubleTap()
        assertEquals(PDF_ZOOM_STEP, s.scale, 0.001f)
        s.toggleDoubleTap()
        assertEquals(PDF_ZOOM_MIN, s.scale, 0.001f)
        assertEquals(Offset.Zero, s.offset)
    }
}
