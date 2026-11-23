package com.bibot.app.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.rounded.Cached
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
import androidx.compose.ui.draw.clip
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

    val listState = rememberLazyListState()
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

    val modeLabel = when (ui.handsFreeMode) {
        HandsFreeMode.NONE -> "Chế độ: Chạm một lần"
        HandsFreeMode.CONTINUOUS -> "Chế độ: Hands-free (nghe liên tục)"
        HandsFreeMode.PUSH_TO_TALK -> "Chế độ: PTT (giữ nút để nói)"
    }
    val nextModeLabel = when (ui.handsFreeMode) {
        HandsFreeMode.NONE -> "Hands-free"
        HandsFreeMode.CONTINUOUS -> "PTT"
        HandsFreeMode.PUSH_TO_TALK -> "Chạm một lần"
    }
    val modeIcon = when (ui.handsFreeMode) {
        HandsFreeMode.NONE -> Icons.Default.Mic
        HandsFreeMode.CONTINUOUS -> Icons.Default.Repeat
        HandsFreeMode.PUSH_TO_TALK -> Icons.Default.KeyboardVoice
    }
    val statusText = when {
        ui.isListening || ui.continuousListening -> "Em đang nghe..."
        ui.isStreaming -> "Em đang suy nghĩ..."
        ui.isSpeaking -> "Em đang nói..."
        else -> "Sẵn sàng"
    }
    val answer = if (ui.responseText.isNotBlank()) ui.responseText
    else if (ui.isStreaming) "Em đang suy nghĩ..." else ""

    val hasActiveUser = ui.lastUserText.isNotBlank()
    val hasActiveAnswer = answer.isNotBlank()
    val hasPartial = ui.partialText.isNotBlank()
    LaunchedEffect(ui.turns.size, ui.responseText, hasActiveUser, hasActiveAnswer) {
        val target = if (hasActiveUser || hasActiveAnswer || hasPartial) ui.turns.size * 2
        else (ui.turns.size * 2 - 1).coerceAtLeast(0)
        listState.animateScrollToItem(target)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header: thương hiệu + chế độ mic
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Mic, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Em là Bi",
                        style = MaterialTheme.typography.titleMedium,
                        // QA hook (debug): long-press tiêu đề = chạy 1 lượt từ files/qa.txt
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onLongPress = { chatVm.runQaTurn() })
                        }
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Cached,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(modeLabel, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(
                    onClick = {
                        chatVm.setHandsFreeMode(when (ui.handsFreeMode) {
                            HandsFreeMode.NONE -> HandsFreeMode.CONTINUOUS
                            HandsFreeMode.CONTINUOUS -> HandsFreeMode.PUSH_TO_TALK
                            HandsFreeMode.PUSH_TO_TALK -> HandsFreeMode.NONE
                        })
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(modeIcon, contentDescription = "Chế độ hiện tại: $modeLabel. Chạm để chuyển sang $nextModeLabel.",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Trạng thái ngắn: nghe / nghĩ / nói / sẵn sàng
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                when {
                    ui.isListening || ui.continuousListening -> Icons.Default.Mic
                    ui.isSpeaking -> Icons.Default.VolumeUp
                    else -> Icons.Default.Mic
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(statusText, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Hội thoại: mỗi lượt là item có key ổn định — không vẽ lại cả màn khi có tin mới
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(ui.turns, key = { it.id }) { turn ->
                    UserBubble(turn.user)
                    Spacer(Modifier.height(4.dp))
                    BotBubble(turn.assistant, turn.ttfaMs)
                }
                if (hasActiveUser) {
                    item(key = "active_user") { UserBubble(ui.lastUserText) }
                }
                if (hasPartial) {
                    item(key = "partial") {
                        Text("Đang nghe: ${ui.partialText}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (hasActiveAnswer) {
                    item(key = "active_answer") {
                        BotBubble(answer, -1L, streaming = ui.isStreaming)
                    }
                }
                item(key = "status") {
                    ui.ttsStatus?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ui.sttError?.let {
                        Text("STT: $it", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (stats.turns > 0 && stats.lastTtfaMs >= 0) {
                        Text(
                            "Lượt ${stats.turns} · TTFA ${stats.lastTtfaMs}ms" +
                                (if (stats.lastTurnMs >= 0) " · ${stats.lastTurnMs}ms" else "") +
                                (if (stats.avgTtfaMs >= 0) " · TB ${stats.avgTtfaMs}ms" else "") +
                                (if (stats.bargeInCount > 0) " · ngắt lời ×${stats.bargeInCount}" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Điều khiển: MỘT nút chính, mode-aware
        Column(modifier = Modifier.fillMaxWidth()) {
            if (ui.isStreaming || ui.isSpeaking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            val buttonModifier = if (ui.handsFreeMode == HandsFreeMode.PUSH_TO_TALK)
                Modifier.fillMaxWidth().pushToTalk { chatVm.onPushToTalkPress() }
            else Modifier.fillMaxWidth()
            Box(modifier = buttonModifier) {
                BigButton(
                    onClick = {
                        when (ui.handsFreeMode) {
                            HandsFreeMode.NONE -> {
                                if (ui.isListening) chatVm.cancelListenFlag()
                                else if (ui.isSpeaking || ui.isStreaming) chatVm.stopChat()
                                else startStt()
                            }
                            HandsFreeMode.CONTINUOUS -> {
                                if (ui.continuousListening) chatVm.stopContinuousListening()
                                else chatVm.startContinuousListening()
                            }
                            HandsFreeMode.PUSH_TO_TALK -> Unit // xử lý bởi pushToTalk
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = when {
                        ui.isListening || ui.continuousListening -> "Đang nghe · chạm để dừng"
                        ui.isSpeaking -> "Đang nói · chạm để dừng"
                        ui.isStreaming -> "Em Bi đang trả lời..."
                        else -> modeLabel
                    },
                    subLabel = if (tablet && ui.isSpeaking) "Đang phát ${prefsState.voiceSpeed}x" else null,
                    icon = {
                        Icon(
                            if (ui.isListening || ui.continuousListening) Icons.Default.StopCircle
                            else if (ui.isSpeaking) Icons.Default.VolumeUp else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    height = if (tablet) 72.dp else 64.dp
                )
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Box(
            Modifier.background(
                MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
            ).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun BotBubble(text: String, ttfaMs: Long, streaming: Boolean = false) {
    Column {
        Box(
            Modifier.fillMaxWidth().background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
            ).padding(14.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
        if (ttfaMs >= 0 || streaming) {
            Spacer(Modifier.height(2.dp))
            Text(
                if (streaming) "Đang trả lời..." else "TTFA ${ttfaMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
