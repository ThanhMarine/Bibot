package com.bibot.app.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@Composable
fun BibotPlayerBar(
    viewModel: PlayerViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.playerState.collectAsState()

    Surface(
        tonalElevation = 6.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Row 1: lùi / phát-tạm dừng / tới, seek bar, thời gian — nút to 56dp tablet 64dp
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { viewModel.seekBack() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Tua lại 10s", modifier = Modifier.size(28.dp))
                }
                FilledIconButton(onClick = { viewModel.togglePlayPause() }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Tạm dừng" else "Phát",
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = { viewModel.seekForward() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.FastForward, contentDescription = "Tua tiếp 10s", modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = if (state.durationMs > 0) state.currentPositionMs.toFloat() / state.durationMs.toFloat() else 0f,
                    onValueChange = { ratio -> viewModel.seekTo((ratio * state.durationMs).toLong()) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${formatTime(state.currentPositionMs)}/${formatTime(state.durationMs)}",
                    style = MaterialTheme.typography.bodySmall, fontSize = 12.sp
                )
            }
            // Row 2: Tốc độ: 0.85x [0.75x | 0.85x | 1.0x | 1.25x] + âm lượng
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
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
                IconButton(onClick = { viewModel.setSpeed(1.0f) }) {
                    Icon(Icons.Default.VolumeUp, contentDescription = "Âm lượng")
                }
            }
            if (state.title != "Bibot Player" || state.subtitle != "Sẵn sàng phát") {
                Text(state.title, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(state.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
}
