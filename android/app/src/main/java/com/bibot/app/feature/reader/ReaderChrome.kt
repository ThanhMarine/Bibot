package com.bibot.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibot.app.R

/**
 * B3+E1.1 — Chrome màn đọc dùng chung cho 2 chế độ TEXT/PAGE (F1.1):
 * top bar (back/TTS/search/mục lục/⚙) + brightness + đáy (x of N + ‹ › +
 * scrubber vạch chương + lịch sử) + dialogs + TOC sheet. Không chứa logic TTS.
 */

// B3.3/F2.5: nhảy chương/câu khi đang READ, hoặc neo anchor mới khi đang READ.
data class JumpConfirm(
    val title: String,
    val message: String,
    val onYes: () -> Unit
)

@Composable
fun ReaderTopBar(
    title: String,
    isReading: Boolean,
    playEnabled: Boolean,
    pageMode: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSearch: () -> Unit,
    onToc: () -> Unit,
    onToggleView: () -> Unit,
    playLabel: String
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.reader_back))
            }
            Text(
                title,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(
                onClick = onTogglePlay, enabled = playEnabled, modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    if (isReading) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = playLabel
                )
            }
            IconButton(onClick = onSearch, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.chrome_search))
            }
            IconButton(onClick = onToc, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.MenuBook, contentDescription = stringResource(R.string.chrome_toc))
            }
            // ⚙: chuyển TEXT ↔ PAGE (F1.1). Sách không-PDF không có PAGE.
            IconButton(onClick = onToggleView, enabled = pageMode, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(
                        if (pageMode) R.string.chrome_view_page else R.string.chrome_view_text
                    )
                )
            }
        }
    }
}

@Composable
fun ReaderBrightnessRow(
    brightness: Float?,
    onBrightness: (Float?) -> Unit
) {    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.chrome_brightness),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(110.dp)
        )
        Slider(
            value = brightness ?: 0.7f,
            onValueChange = { onBrightness(it) },
            valueRange = 0.1f..1.0f,
            enabled = brightness != null,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = brightness == null,
            onCheckedChange = { auto -> onBrightness(if (auto) null else 0.7f) }
        )
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.chrome_brightness_auto), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * B6.2 — Popup nút ⚙: toggle Xem chữ/Xem trang + brightness (gọn 1 chỗ,
 * thay hàng brightness cố định chiếm vùng đọc).
 */
@Composable
fun ReaderSettingsPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewMode: ReaderView,
    pageModeEnabled: Boolean,
    onToggleView: () -> Unit,
    brightness: Float?,
    onBrightness: (Float?) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(280.dp)
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (viewMode == ReaderView.TEXT) R.string.chrome_view_page
                        else R.string.chrome_view_text
                    )
                )
            },
            onClick = { onToggleView(); onDismiss() },
            enabled = pageModeEnabled
        )
        HorizontalDivider()
        ReaderBrightnessRow(brightness = brightness, onBrightness = onBrightness)
    }
}

@Composable
fun ReaderBottomBar(
    chapterText: String,
    fraction: Float,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onScrubFraction: (Float) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevChapter, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "‹")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    chapterText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                ScrubLine(fraction = fraction, onScrubFraction = onScrubFraction)
            }
            IconButton(onClick = onNextChapter, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.ChevronRight, contentDescription = "›")
            }
        }
    }
}

/**
 * Vạch xanh mỏng (3dp) chạm để nhảy: thay slider + ticks cồng kềnh —
 * cùng 1 fraction vị trí tổng thể, không che text.
 */
@Composable
fun ScrubLine(fraction: Float, onScrubFraction: (Float) -> Unit) {
    var w by remember { mutableStateOf(0) }
    Box(
        Modifier.fillMaxWidth().height(14.dp)
            .onSizeChanged { w = it.width }
            .pointerInput(w) {
                detectTapGestures { offset ->
                    if (w > 0) onScrubFraction((offset.x / w).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {}
        )
    }
}


/**
 * B6.7 — Thanh chỉ báo mảnh (3dp) sát dưới top-bar: vị trí đọc tổng thể,
 * hiển thị-only (điều khiển vẫn ở scrubber đáy). Gần như không che text.
 */
@Composable
fun ReaderPositionBar(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(3.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {}
    )
}

@Composable
fun JumpConfirmDialog(confirm: JumpConfirm, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(confirm.title, fontWeight = FontWeight.Bold) },
        text = { Text(confirm.message) },
        confirmButton = {
            TextButton(onClick = confirm.onYes) { Text(stringResource(R.string.chrome_yes)) }
        },
        dismissButton = {
            TextButton(onClick = onNo) { Text(stringResource(R.string.chrome_no)) }
        }
    )
}

/** Thanh neo điểm đọc (F2.2): marker + nút xác nhận, tránh chạm nhầm khi vuốt. */
@Composable
fun AnchorBar(
    label: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.chrome_anchor_read_from), fontSize = 12.sp)
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dialog_close))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTocSheet(
    titles: List<String>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.chrome_toc),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            itemsIndexed(titles) { idx, t ->
                TextButton(
                    onClick = { onPick(idx) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "${idx + 1}. $t",
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        color = if (idx == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (idx == current) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
