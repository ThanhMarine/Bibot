package com.bibot.app.ui.theme

import androidx.compose.ui.unit.dp

/** Spacing scale — chỉ dùng các giá trị này, không invent số lẻ. */
object BibotSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

/** Bo góc thống nhất: card > button > badge. */
object BibotRadius {
    val Sm = 12.dp
    val Md = 16.dp
    val Lg = 20.dp
}

/** Kích thước icon & vùng chạm. Hình lớn – chữ nhỏ đủ nhìn. */
object BibotIconSize {
    val Section = 20.dp   // icon tiêu đề mục trong badge 32dp
    val Action = 24.dp    // icon nút/action
    val Tile = 96.dp      // THỬ NGHIỆM: icon ô Home phóng 3× (32dp -> 96dp)
    val Badge = 56.dp     // huy hiệu icon Home
    val Touch = 48.dp     // vùng chạm tối thiểu
}
