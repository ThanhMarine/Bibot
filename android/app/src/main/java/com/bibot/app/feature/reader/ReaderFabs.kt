package com.bibot.app.feature.reader

/**
 * UX 2026-11-15: 2 FAB của màn đọc (Back + Play/Pause) — quản lý độ ẩn tự động trong file này.
 *
 * - Khi đang đọc (isReading=true) và menu mở → FAB tự ẩn sau 3.5s để không che chữ.
 * - Khi không đọc (BROWSE / dừng) → giữ hiển thị (chọn câu).
 * - Bật lại bằng cách tap vào nội dung (menuVisible chuyển) hoặc play/pause.
 *
 * Tách khỏi ReaderScreen (2.300 dòng, hàng trăm state) — thêm state/lambda vào thân nó
 * vượt ngưỡng DEX verifier → crash mở sách. File này nhỏ nên an toàn.
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun BoxScope.ReaderFabPair(
    menuVisible: Boolean,
    isReading: Boolean,
    backDesc: String,
    playDesc: String,
    playBottomPadding: Dp,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit
) {
    // UX: auto-hide sau 3.5s khi đang đọc; reset ngay khi menu/độ đọc đổi (tap vào
    // nội dung sẽ bật menuVisible → effect restart → FAB hiện lại).
    var fabAutoHidden by remember { mutableStateOf(false) }
    LaunchedEffect(menuVisible, isReading) {
        if (!menuVisible || !isReading) {
            fabAutoHidden = false
            return@LaunchedEffect
        }
        fabAutoHidden = false
        delay(3500L)
        fabAutoHidden = true
    }
    if (menuVisible && !fabAutoHidden) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
        ) {
            FloatingActionButton(
                onClick = onBack,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = backDesc)
            }
        }
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = playBottomPadding)
        ) {
            FloatingActionButton(
                onClick = onTogglePlay,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    if (isReading) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = playDesc
                )
            }
        }
    }
}
