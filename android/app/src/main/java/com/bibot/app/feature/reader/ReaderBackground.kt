package com.bibot.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.bibot.app.ui.components.TrongDongWatermark

/** B6.19 — Nền màn đọc: mặc định giữ nền trống đồng, tùy chọn nền đơn sắc cùng tông. */
const val READER_BG_DRUM = "drum"
const val READER_BG_SOLID = "solid"

// Trung bình pixel của bg_trong_dong.png (đo bằng PIL: mean toàn ảnh 179,133,65 —
// góc đậm #8B5E3C, giữa #D3A34C). Lấy từ palette watermark, KHÔNG chọn màu mới.
private val DrumTint = Color(0xFFB38541)

/**
 * Màu đơn sắc = tint trống đồng blend đúng lên surface với CÙNG alpha mà
 * [TrongDongWatermark] đang dùng (light 0.45 / dark 0.38) → nền "solid" bằng đúng
 * tông nền trống đồng đang thấy, chỉ bỏ hoa văn. Opaque (alpha 1) để che watermark.
 */
@Composable
fun readerSolidBackground(): Color {
    val alpha = if (isSystemInDarkTheme()) 0.38f else 0.45f
    return DrumTint.copy(alpha = alpha).compositeOver(MaterialTheme.colorScheme.surface)
}

/**
 * Lớp nền màn đọc — đặt ở root của [com.bibot.app.ui.menu.BibotNavSuite] (TRƯỚC Scaffold
 * trong suốt) nên phủ luôn vùng statusBar/navBar; TEXT + EPUB rich + PDF khung đều hưởng
 * (PDF giữ viền tờ giấy B6.18). "drum" = không vẽ gì, watermark toàn cục xuyên qua.
 */
@Composable
fun ReaderBackgroundLayer(readerBackground: String) {
    if (readerBackground != READER_BG_SOLID) return
    Box(Modifier.fillMaxSize().background(readerSolidBackground()))
}
