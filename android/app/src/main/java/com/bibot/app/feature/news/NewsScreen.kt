package com.bibot.app.feature.news

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibot.app.R
import com.bibot.app.ui.player.ControlIconButton
import com.bibot.app.ui.player.PlayerViewModel
import com.bibot.app.ui.player.ControlInfoText
import com.bibot.app.ui.player.ControlPlayButton
import com.bibot.app.ui.player.FloatingControlScaffold
import com.bibot.app.ui.player.contentBottomInset
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(
    modifier: Modifier = Modifier,
    // A3.2: controller audio chung (ExoPlayer + engine TTS) — đọc/dừng Điểm tin đi qua đây.
    playerVm: PlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<NewsViewModel>()
    LaunchedEffect(playerVm) { viewModel.bindPlayer(playerVm) }
    val scope = rememberCoroutineScope()
    var newsItems by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceId by remember { mutableStateOf("vnexpress") }
    var categoryId by remember { mutableStateOf("moi_nhat") }
    var actualSource by remember { mutableStateOf("") }
    var fellBack by remember { mutableStateOf(false) }

    suspend fun loadNews(forceRefresh: Boolean = false) {
        viewModel.resetIndex()
        isLoading = true
        error = null
        val result = NewsApi.fetchCached(context, sourceId, categoryId, true)
        result.fold(
            onSuccess = { r ->
                newsItems = r.items
                viewModel.setNewsItems(r.items)
                viewModel.updateSourceName(r.source.name)
                actualSource = r.source.name
                fellBack = r.fellBack
            },
            onFailure = {
                newsItems = emptyList()
                viewModel.setNewsItems(emptyList())
                viewModel.updateSourceName("")
                error = it.message ?: context.getString(R.string.news_unknown_err)
            }
        )
        isLoading = false
    }

    LaunchedEffect(sourceId, categoryId) { loadNews() }

    // Rời màn Điểm tin → dừng đọc nền (ViewModel sống theo Activity nên phải dừng tay,
    // nếu không tin vẫn đọc khi đã về Home — giống BibotNavSuite.goHome dừng Player).
    DisposableEffect(Unit) { onDispose { viewModel.stopSpeaking() } }

    // 12.x: thanh điều khiển nổi + nền trong suốt (dùng chung) — không trừ chiều cao,
    // dọc & ngang đều lấy lại toàn bộ không gian danh sách.
    val isLandscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    FloatingControlScaffold(
        alignment = if (isLandscape) Alignment.BottomEnd else Alignment.BottomCenter,
        barVisible = newsItems.isNotEmpty(),
        info = {
            ControlInfoText(
                if (viewModel.isSpeaking) stringResource(R.string.news_pos_reading, viewModel.currentIndex + 1, newsItems.size)
                else stringResource(R.string.news_pos, viewModel.currentIndex + 1, newsItems.size)
            )
        },
        controls = {
            ControlIconButton(
                onClick = { viewModel.playPrevious() },
                enabled = viewModel.currentIndex > 0 && !viewModel.autoReadMode,
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.news_prev)
            )
            ControlPlayButton(
                isPlaying = viewModel.isSpeaking,
                onClick = { if (viewModel.autoReadMode) viewModel.toggleAutoRead() else if (viewModel.isSpeaking) viewModel.stopSpeaking() else viewModel.speakCurrentNews(viewModel.currentIndex) },
                contentDescription = if (viewModel.autoReadMode) stringResource(R.string.news_cd_stop_all) else if (viewModel.isSpeaking) stringResource(R.string.news_cd_stop) else stringResource(R.string.news_cd_read_one)
            )
            ControlIconButton(
                onClick = { viewModel.playNext() },
                enabled = viewModel.currentIndex < newsItems.size - 1 && !viewModel.autoReadMode,
                imageVector = Icons.Default.SkipNext,
                contentDescription = stringResource(R.string.news_next)
            )
            ControlIconButton(
                onClick = { viewModel.toggleAutoRead() },
                enabled = newsItems.isNotEmpty(),
                imageVector = if (viewModel.autoReadMode) Icons.Default.StopCircle else Icons.Default.RepeatOn,
                contentDescription = if (viewModel.autoReadMode) stringResource(R.string.news_cd_stop_all) else stringResource(R.string.news_cd_read_all)
            )
        }
    ) {
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(16.dp),
            contentPadding = PaddingValues(bottom = contentBottomInset()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("📡 ${NewsApi.sourceName(sourceId)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { loadNews(forceRefresh = true) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_refresh), fontSize = 12.sp)
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NewsApi.SOURCES.forEach { s ->
                        FilterChip(
                            selected = s.id == sourceId,
                            onClick = { if (s.id != sourceId) { sourceId = s.id; viewModel.stopSpeaking() } },
                            label = { Text(s.name, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NewsApi.CATEGORIES.forEach { (id, labelRes) ->
                        FilterChip(
                            selected = id == categoryId,
                            onClick = { if (id != categoryId) { categoryId = id; viewModel.stopSpeaking() } },
                            label = { Text(stringResource(labelRes), fontSize = 12.sp) }
                        )
                    }
                }
                if (fellBack) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.news_fallback, NewsApi.sourceName(sourceId), actualSource),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                viewModel.speakError?.let { msg ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "🔊 $msg",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (newsItems.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.news_hint),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (isLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }

            error?.let {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("❌ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.news_check_net), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            itemsIndexed(newsItems) { idx, item ->
                val isCurrent = idx == viewModel.currentIndex
                val isReadingThis = isCurrent && (viewModel.isSpeaking || viewModel.autoReadMode)
                // B6.5: tap để mở rộng tin (tròn text_block thay vì cắt ngầm).
                var expanded by remember(item.title, item.publishedAt) { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (expanded) viewModel.speakCurrentNews(idx) else expanded = true
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isReadingThis) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                modifier = Modifier.weight(1f)
                            )
                            if (isReadingThis) {
                                Spacer(Modifier.width(6.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(stringResource(R.string.news_badge), fontSize = 9.sp) }
                            }
                        }
                        if (item.description.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                item.description,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { expanded = !expanded }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        val sourceText = stringResource(R.string.news_source, item.source, if (item.publishedAt.isNotEmpty()) " • " + item.publishedAt else "")
                        Text(
                            sourceText,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            if (!isLoading && newsItems.isEmpty() && error == null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.news_empty), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}