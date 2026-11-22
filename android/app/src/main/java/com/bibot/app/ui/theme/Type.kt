package com.bibot.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Bibot type scale — toàn bộ chữ đen, nét đậm (theo yêu cầu UI).
 * Mọi style dùng chung FontWeight.Bold; màu lấy từ onSurface = đen.
 */
private fun bold(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Bold
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp
)

val Typography = Typography(
    headlineSmall = bold(24, 32),
    titleLarge = bold(22, 28),
    titleMedium = bold(16, 22),
    titleSmall = bold(14, 20),
    bodyLarge = bold(16, 24),
    bodyMedium = bold(14, 20),
    bodySmall = bold(12, 16),
    labelLarge = bold(14, 20),
    labelSmall = bold(12, 16)
)
