package com.bibot.app.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Floating control bar DÙNG CHUNG cho mọi module (Story / Book / News / Weather / ...):
 * - Nằm overlay phía đáy màn hình → KHÔNG trừ chiều cao layout, dọc & ngang đều lấy lại
 *   toàn bộ không gian (lỗi landscape cũ: Card điều khiển nuốt 1 hàng màn hình).
 * - Nền trong suốt (surface alpha) + bo tròn pill + viền mảnh → chữ trôi bên dưới vẫn thấy.
 * - Mọi module dùng chung [ControlIconButton]/[ControlPlayButton] → mọi nút CÙNG kích thước.
 */
object FloatingControlDefaults {
    /** Kích thước TẤT CẢ nút điều khiển — phone & tablet như nhau. */
    val ButtonSize = 52.dp
    val IconSize = 26.dp
    val BarElevation = 8.dp
    val Margin = 12.dp
    /** Khoảng nội dung cuộn chừa ở đáy để dòng cuối không bị pill che. */
    val ContentBottomInset = 96.dp
    /** Độ trong của nền pill (1 = đục). */
    const val BackgroundAlpha = 0.55f
}

/** Nút điều khiển phụ — cùng [FloatingControlDefaults.ButtonSize] ở mọi module. */
@Composable
fun ControlIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(FloatingControlDefaults.ButtonSize)
    ) {
        Icon(
            imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(FloatingControlDefaults.IconSize)
        )
    }
}

/** Nút Play/Pause — CÙNG kích thước nút phụ, chỉ khác màu cho nổi bật. */
@Composable
fun ControlPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(FloatingControlDefaults.ButtonSize)
    ) {
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = contentDescription ?: if (isPlaying) "Tạm dừng" else "Phát",
            modifier = Modifier.size(FloatingControlDefaults.IconSize)
        )
    }
}

/**
 * Thanh điều khiển nổi, nền trong suốt — nhận nút từ module (dùng chung [ControlIconButton]).
 * @param info chữ/trạng thái phụ bên phải nút (VD "Chương 1/5 • Câu 3/20")
 */
@Composable
fun FloatingControlBar(
    controls: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    info: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = FloatingControlDefaults.BackgroundAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        tonalElevation = 0.dp,
        shadowElevation = FloatingControlDefaults.BarElevation,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            controls()
            if (info != null) {
                Box(Modifier.padding(start = 6.dp, end = 4.dp)) { info() }
            }
        }
    }
}

/**
 * Bọc nội dung module thành lớp tràn viền + thanh điều khiển nổi đè lên trên.
 * Nhận `content` theo [ColumnScope] nên module thay `Column(...) { ... }` bằng composable này
 * là xong — nội dung không bị trừ không gian → dùng mọi orientation, mọi module.
 *
 * @param alignment vị trí pill: mặc định đáy giữa; module đọc dọc ngang có thể
 *                  chuyển BottomEnd để khỏi che giữa cột chữ.
 * @param barVisible ẩn pill (VD module chưa có dữ liệu) nhưng vẫn giữ layout tràn viền.
 */
@Composable
fun FloatingControlScaffold(
    controls: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomCenter,
    info: (@Composable () -> Unit)? = null,
    barVisible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), content = content)
        if (barVisible) {
            FloatingControlBar(
                controls = controls,
                info = info,
                modifier = Modifier
                    .align(alignment)
                    .navigationBarsPadding()
                    .padding(horizontal = FloatingControlDefaults.Margin, vertical = FloatingControlDefaults.Margin)
            )
        }
    }
}

/** Dòng chữ nhỏ của pill — cùng style cho mọi module. */
@Composable
fun ControlInfoText(text: String, secondary: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 11.sp,
        maxLines = 2,
        color = if (secondary) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
    )
}

/**
 * Padding đáy cho list/cột nội dung: [FloatingControlDefaults.ContentBottomInset] + cao nav bar
 * (app edge-to-edge → pill lùi khỏi nav bar nên dòng cuối cũng phải lùi thêm).
 */
@Composable
fun contentBottomInset(): Dp =
    FloatingControlDefaults.ContentBottomInset +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
