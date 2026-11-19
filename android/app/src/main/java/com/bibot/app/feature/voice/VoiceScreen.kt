package com.bibot.app.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibot.app.data.store.BibotPreferences
import com.bibot.app.ui.adaptive.fabSize
import com.bibot.app.ui.adaptive.isTablet
import com.bibot.app.ui.components.BigButton
import com.bibot.app.ui.components.BigIconButton
import com.bibot.app.ui.player.PlayerViewModel

// Push-to-talk Modifier: long-press to start, VAD auto-ends on silence
private fun Modifier.pushToTalk(onPress: () -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(
            onLongPress = { onPress() }
        )
    }

@Composable
fun MicButton(
    handsFreeMode: HandsFreeMode,
    isListening: Boolean,
    continuousListening: Boolean,
    isSpeaking: Boolean,
    isStreaming: Boolean,
    onClick: (HandsFreeMode) -> Unit,
    onPushToTalkPress: () -> Unit,
    tablet: Boolean,
    fab: Dp
) {
    if (handsFreeMode == HandsFreeMode.PUSH_TO_TALK) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pushToTalk(onPushToTalkPress)
        ) {
            BigIconButton(
                onClick = { /* handled by Box pointerInput */ },
                size = fab,
                iconSize = if (tablet) 48.dp else 36.dp,
                containerColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                icon = {
                    Icon(
                        if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isListening) "Dừng" else "Nói to",
                        modifier = Modifier.size(if (tablet) 48.dp else 32.dp)
                    )
                }
            )
        }
    } else {
        BigIconButton(
            onClick = { onClick(handsFreeMode) },
            size = fab,
            iconSize = if (tablet) 48.dp else 36.dp,
            containerColor = when {
                isListening || continuousListening -> MaterialTheme.colorScheme.error
                isSpeaking -> MaterialTheme.colorScheme.tertiary
                isStreaming -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.primary
            },
            icon = {
                Icon(
                    when {
                        isListening || continuousListening -> Icons.Default.Stop
                        isSpeaking -> Icons.Default.VolumeUp
                        else -> Icons.Default.Mic
                    },
                    contentDescription = if (isListening || continuousListening) "Dừng" else "Nói to",
                    modifier = Modifier.size(if (tablet) 48.dp else 32.dp)
                )
            }
        )
    }
}

@OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun VoiceScreen(
    playerVm: PlayerViewModel = viewModel(),
    chatVm: VoiceChatViewModel = viewModel()
) {
    val context = LocalContext.current
    val ui by chatVm.uiState.collectAsState()
    val stats by chatVm.stats.collectAsState()

    val scrollState = rememberScrollState()
    val activity = context as? androidx.activity.ComponentActivity
    val windowSize = activity?.let { calculateWindowSizeClass(activity = it) }
    val tablet = isTablet(windowSize)
    val fab = fabSize(tablet)

    // voiceSpeed hiển thị (tablet subLabel) — đọc prefs, logic TTS nằm trong ViewModel
    val prefs = remember { BibotPreferences(context) }
    val prefsState by prefs.prefsFlow.collectAsState(initial = com.bibot.app.data.store.BibotPrefs())

    LaunchedEffect(playerVm) { chatVm.bindPlayer(playerVm) }

    // A3.2: rời màn Voice → dừng audio (ViewModel sống theo Activity nên job pipeline
    // phải tự hủy ở đây — giống NewsScreen.stopSpeaking khi rời màn Điểm tin).
    DisposableEffect(Unit) { onDispose { chatVm.stopChat() } }

    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) chatVm.beginStt()
        else chatVm.setSttError("Cần quyền micro để nghe — đã từ chối, vào Cài đặt Android để cấp lại.")
    }

    fun startStt() {
        chatVm.setSttError(null)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) chatVm.beginStt()
        else {
            chatVm.setSttError("Bi cần quyền micro để nghe bạn nói...")
            try { audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            catch (e: Exception) { chatVm.setSttError("Không mở được hộp xin quyền: ${e.message}") }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Card(modifier = Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f))) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(scrollState)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    // Mode toggle button
                    IconButton(onClick = {
                        chatVm.setHandsFreeMode(when (ui.handsFreeMode) {
                            HandsFreeMode.NONE -> HandsFreeMode.CONTINUOUS
                            HandsFreeMode.CONTINUOUS -> HandsFreeMode.PUSH_TO_TALK
                            HandsFreeMode.PUSH_TO_TALK -> HandsFreeMode.NONE
                        })
                    }) {
                        Icon(
                            imageVector = when (ui.handsFreeMode) {
                                HandsFreeMode.NONE -> Icons.Default.Mic
                                HandsFreeMode.CONTINUOUS -> Icons.Default.Repeat
                                HandsFreeMode.PUSH_TO_TALK -> Icons.Default.KeyboardVoice
                            },
                            contentDescription = "Chế độ mic: ${ui.handsFreeMode.name}",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "BiBot Voice Chat", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        // QA debug (release ẩn): long-press title = giả lập 1 lượt thoại không cần mic
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onLongPress = { chatVm.runQaTurn() })
                        }
                    )
                    if (ui.isSpeaking) Icon(Icons.Default.VolumeUp, contentDescription = "Đang nói", tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                if (ui.partialText.isNotEmpty()) {
                    Text("Đang nghe: ${ui.partialText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(8.dp))
                }
                Text(text = if (ui.isStreaming && ui.responseText.isEmpty()) "Em là Bi đang suy nghĩ..." else ui.responseText, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
                ui.sttError?.let { Spacer(Modifier.height(8.dp)); Text("STT: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                ui.ttsStatus?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
                if (stats.turns > 0 && stats.lastTtfaMs >= 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Lượt ${stats.turns} • TTFA ${stats.lastTtfaMs}ms" +
                            (if (stats.lastTurnMs >= 0) " • ${stats.lastTurnMs}ms" else "") +
                            (if (stats.avgTtfaMs >= 0) " • TB ${stats.avgTtfaMs}ms" else "") +
                            (if (stats.bargeInCount > 0) " • ngắt lời ×${stats.bargeInCount}" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = if (tablet) 32.dp else 0.dp)) {
            // Mode indicator
            if (ui.handsFreeMode != HandsFreeMode.NONE) {
                Text(
                    text = when (ui.handsFreeMode) {
                        HandsFreeMode.CONTINUOUS -> "Hands-free: Bấm để bật/tắt nghe liên tục"
                        HandsFreeMode.PUSH_TO_TALK -> "Push-to-talk: Giữ để nói, thả để gửi"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).padding(bottom = 8.dp)
)
            }
            MicButton(
                handsFreeMode = ui.handsFreeMode,
                isListening = ui.isListening,
                continuousListening = ui.continuousListening,
                isSpeaking = ui.isSpeaking,
                isStreaming = ui.isStreaming,
                onClick = { mode ->
                    when (mode) {
                        HandsFreeMode.NONE -> {
                            if (ui.isListening) chatVm.cancelListenFlag() else if (ui.isSpeaking || ui.isStreaming) chatVm.stopChat() else startStt()
                        }
                        HandsFreeMode.CONTINUOUS -> {
                            if (ui.continuousListening) chatVm.stopContinuousListening() else chatVm.startContinuousListening()
                        }
                        HandsFreeMode.PUSH_TO_TALK -> { /* handled by wrapper */ }
                    }
                },
                onPushToTalkPress = { chatVm.onPushToTalkPress() },
                tablet = tablet,
                fab = fab
            )
            Column(Modifier.weight(1f)) {
                BigButton(
                    onClick = {
                        when (ui.handsFreeMode) {
                            HandsFreeMode.NONE -> {
                                if (ui.isListening) chatVm.cancelListenFlag() else if (ui.isSpeaking || ui.isStreaming) chatVm.stopChat() else startStt()
                            }
                            HandsFreeMode.CONTINUOUS -> {
                                if (ui.continuousListening) chatVm.stopContinuousListening() else chatVm.startContinuousListening()
                            }
                            HandsFreeMode.PUSH_TO_TALK -> {
                                // Use mic icon button for push-to-talk
                            }
                        }
                    },
                    modifier = if (ui.handsFreeMode == HandsFreeMode.PUSH_TO_TALK) Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { chatVm.onPushToTalkPress() }
                        )
                    }.fillMaxWidth() else Modifier.fillMaxWidth(),
                    label = when {
                        ui.isListening || ui.continuousListening -> "Đang nghe... chạm để dừng"
                        ui.isSpeaking -> "Đang nói... chạm để dừng"
                        ui.isStreaming -> "Em Bi đang trả lời..."
                        else -> when (ui.handsFreeMode) {
                            HandsFreeMode.NONE -> "Chạm để nói"
                            HandsFreeMode.CONTINUOUS -> "Chạm để bật nghe liên tục"
                            HandsFreeMode.PUSH_TO_TALK -> "Giữ để nói, thả để gửi"
                        }
                    },
                    subLabel = when {
                        tablet && ui.isSpeaking -> "Đang phát ${prefsState.voiceSpeed}x"
                        tablet -> "Nút lớn 72dp — dễ bấm khi cầm tablet"
                        else -> null
                    },
                    icon = {
                        Icon(
                            imageVector = when {
                                ui.isListening || ui.continuousListening -> Icons.Default.StopCircle
                                ui.isSpeaking -> Icons.Default.VolumeUp
                                else -> Icons.Default.Mic
                            },
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    height = if (tablet) 72.dp else 64.dp
                )
                if (ui.isStreaming || ui.isSpeaking) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
        }
        if (ui.isStreaming || ui.isSpeaking) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(if (ui.isSpeaking) "Em là Bi đang nói..." else "Em là Bi đang trả lời...", style = MaterialTheme.typography.bodySmall)
            }
        }
Spacer(Modifier.height(16.dp))
    }
}
