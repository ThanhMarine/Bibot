package com.bibot.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.bibot.app.R

/**
 * Nền trống đồng FULL MÀN HÌNH — Home / Cài đặt / Trò chuyện AI, phone & tablet.
 * - `ContentScale.Crop` + fillMaxSize → ảnh phóng to phủ kín mọi tỷ lệ màn hình
 *   (20:9 phone, 4:3 tablet, landscape) — không còn viền trống 2 bên như bản cũ
 *   (bản cũ: 92% cạnh nhỏ nhất, cap 560dp → giữa màn hình, 2 bên hụt).
 * - Ảnh `bg_trong_dong.png` (drawable-nodpi, nền trong) blend tốt trên cả light/dark.
 */
@Composable
fun TrongDongWatermark(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_trong_dong),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Giảm độ sáng nền (gốc: light 1.0 / dark 0.55) → chữ nội dung truyện/sách
            // nổi rõ hơn: light pha nhẹ với Surface sáng, dark pha nền tối.
            alpha = if (dark) 0.38f else 0.45f,
            modifier = Modifier.fillMaxSize()
        )
    }
}
