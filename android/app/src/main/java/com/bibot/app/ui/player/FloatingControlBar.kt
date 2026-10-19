package com.bibot.app.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
 * A3.3: Enhanced floating control bar for Reader — includes:
 * - Row 1: SkipPrevious (undo 10s), Play/Pause, SkipNext (forward 10s), Slider (overall progress), Time
 * - Row 2: Speed chips (0.75x, 0.85x, 1.0x, 1.25x), Volume icon
 * - Info: Chapter X/Y • Sentence Z/W
 */
@Composable
fun ReaderFloatingControlBar(
    viewModel: PlayerViewModel,
    chapterText: String,
    sentenceText: String,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state: PlayerState by viewModel.playerState.collectAsState()

    FloatingControlBar(
        controls = {
            // Row 1: controls + slider + time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                ControlIconButton(
                    onClick = { viewModel.seekBack() },
                    imageVector = Icons.Default.FastRewind,
                    contentDescription = "Tua lại 10s"
                )
                ControlPlayButton(
                    isPlaying = state.isPlaying,
                    onClick = { viewModel.togglePlayPause() }
                )
                ControlIconButton(
                    onClick = { viewModel.seekForward() },
                    imageVector = Icons.Default.FastForward,
                    contentDescription = "Tua tiếp 10s"
                )
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = if (state.durationMs > 0) state.currentPositionMs.toFloat() / state.durationMs.toFloat() else 0f,
                    onValueChange = { ratio -> viewModel.seekTo((ratio * state.durationMs).toLong()) },
                    enabled = state.durationMs > 0,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${formatTime(state.currentPositionMs)}/${formatTime(state.durationMs)}",
                    style = MaterialTheme.typography.bodySmall, fontSize = 12.sp
                )
            }
            // Row 2: Speed chips + volume
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Tốc độ: ", style = MaterialTheme.typography.labelSmall)
                    listOf(0.75f, 0.85f, 1.0f, 1.25f).forEach { speed ->
                        FilterChip(
                            selected = state.playbackSpeed == speed,
                            onClick = { viewModel.setSpeed(speed) },
                            label = { Text("${speed}x", fontSize = 11.sp) },
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }
                ControlIconButton(
                    onClick = { viewModel.setSpeed(1.0f) },
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Âm lượng"
                )
            }
        },
        modifier = modifier,
        info = {
            Column(
                modifier = Modifier.padding(vertical = 2.dp),
                horizontalAlignment = Alignment.Start
            ) {
                ControlInfoText(chapterText)
                ControlInfoText(sentenceText, secondary = true)
            }
        }
    )
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(java.util.Locale.getDefault(), "%02d:%02d", min, sec)
}

/**
 * Padding đáy cho list/cột nội dung: [FloatingControlDefaults.ContentBottomInset] + cao nav bar
 * (app edge-to-edge → pill lùi khỏi nav bar nên dòng cuối cũng phải lùi thêm).
 */
@Composable
fun contentBottomInset(): Dp =
    FloatingControlDefaults.ContentBottomInset +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
