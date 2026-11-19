package com.bibot.app.feature.reader

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * UX 2026-11-16 — dàn trang chữ cho màn đọc.
 *
 * Bề rộng tối đa của cột chữ: ~65–70 ký tự ở cỡ 18sp (đo chuẩn của sách giấy).
 * Trên tablet / màn ngang, cột được CĂN GIỮA và giới hạn bề rộng → hai lề luôn
 * bằng nhau, chữ không bị "bè" hết chiều ngang (nguyên nhân cột trông lệch trái
 * khi lề phải bị kéo rộng bởi Justify trên màn lớn).
 */
val ReaderTextMaxWidth: Dp = 720.dp

/** Lề trái = lề phải của cột chữ (dp) — luôn dùng cùng một hằng cho cả 2 phía. */
val ReaderTextGutter: Dp = 8.dp

/**
 * Modifier "cột chữ cân bằng": giới hạn bề rộng tối đa rồi căn giữa trong khung
 * cha. Dùng trực tiếp trên LazyColumn/Column (không cần thêm composable).
 *
 * Thứ tự modifier quan trọng: wrapContentWidth phải nằm NGOÀI widthIn để nó
 * đo được bề rộng thật của cột (≤ max) rồi mới căn giữa trong khung cha.
 */
fun Modifier.balancedTextColumn(): Modifier =
    this.fillMaxHeight()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = ReaderTextMaxWidth)
        .fillMaxWidth()
